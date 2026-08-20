package ai.chat2db.community.runtime.cli;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.runtime.provider.DefaultProviderProcessLauncher;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderEventSink;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderFailureKind;
import ai.chat2db.community.runtime.provider.ProviderLifecycleSink;
import ai.chat2db.community.runtime.provider.ProviderProcessLauncher;
import ai.chat2db.community.runtime.provider.RuntimePromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared process, timeout, cancellation and event plumbing for JSON-lines Agent CLIs. */
abstract class JsonLineCliAdapter implements ExternalProviderAdapter {

    protected static final String TASK_TOKEN_ENV = "CHAT2DB_AGENT_TASK_TOKEN";
    protected static final int DEFAULT_TIMEOUT_SECONDS = 900;

    protected final ObjectMapper mapper;
    private final ProviderProcessLauncher processLauncher;
    protected final RuntimePromptBuilder promptBuilder = new RuntimePromptBuilder();
    private final Map<String, RunningProcess> running = new ConcurrentHashMap<>();

    protected JsonLineCliAdapter() {
        this(new ObjectMapper(), new DefaultProviderProcessLauncher());
    }

    protected JsonLineCliAdapter(ObjectMapper mapper, ProviderProcessLauncher processLauncher) {
        this.mapper = mapper;
        this.processLauncher = processLauncher;
    }

    @Override
    public final ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink eventSink,
                                                  ProviderLifecycleSink lifecycleSink) {
        validate(request, eventSink, lifecycleSink);
        PreparedInvocation invocation = prepare(request);
        ManagedProviderProcess process;
        try {
            process = processLauncher.start(invocation.command(), request.getWorkingDirectory(), invocation.environment());
        } catch (IOException exception) {
            invocation.cleanup().run();
            throw failure(ProviderFailureKind.PROCESS_EXIT, "failed to start " + displayName(), exception);
        }

        RunningProcess execution = new RunningProcess(process);
        if (running.putIfAbsent(request.getRunId(), execution) != null) {
            terminate(process);
            invocation.cleanup().run();
            throw new IllegalStateException(displayName() + " Run is already executing: " + request.getRunId());
        }

        ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat2db-" + provider().name().toLowerCase() + "-timeout");
            thread.setDaemon(true);
            return thread;
        });
        int timeoutSeconds = request.getRuntimeProfile().getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : request.getRuntimeProfile().getTimeoutSeconds();
        timeout.schedule(() -> {
            execution.timedOut.set(true);
            terminate(process);
        }, timeoutSeconds, TimeUnit.SECONDS);

        BoundedText stderr = new BoundedText(16_384);
        Thread stderrReader = new Thread(() -> drainStderr(process, stderr),
                "chat2db-" + provider().name().toLowerCase() + "-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        try {
            lifecycleSink.processStarted(provider().name().toLowerCase() + "-process-" + UUID.randomUUID(),
                    process.pid(), process.startInstant(), invocation.command().get(0));
            writeInitialInput(process.stdin(), invocation.initialInput(), invocation.keepStdinOpen());
            ParseState state = new ParseState();
            state.sessionId = invocation.sessionIdHint();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.stdout(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode event;
                    try {
                        event = mapper.readTree(line);
                    } catch (IOException ignored) {
                        continue;
                    }
                    handleEvent(event, state, eventSink, process.stdin());
                    if (state.terminal && invocation.keepStdinOpen()) {
                        closeQuietly(process.stdin());
                    }
                }
            } catch (IOException exception) {
                if (!execution.cancelled.get() && !execution.timedOut.get()) {
                    throw failure(ProviderFailureKind.PROTOCOL_ERROR,
                            displayName() + " output stream failed", exception);
                }
            }

            int exitCode = waitFor(process);
            if (execution.cancelled.get()) {
                throw failure(ProviderFailureKind.CANCELLED, displayName() + " Run was cancelled", null);
            }
            if (execution.timedOut.get()) {
                throw failure(ProviderFailureKind.INACTIVITY_TIMEOUT,
                        displayName() + " exceeded its " + timeoutSeconds + " second execution timeout", null);
            }
            if (state.failure != null) {
                throw failure(ProviderFailureKind.PROTOCOL_ERROR, state.failure, null);
            }
            if (exitCode != 0) {
                throw failure(ProviderFailureKind.PROCESS_EXIT,
                        displayName() + " exited with code " + exitCode + stderrSuffix(stderr), null);
            }
            if (requiresTerminalEvent() && !state.terminal) {
                throw failure(ProviderFailureKind.PROTOCOL_ERROR,
                        displayName() + " stream ended without a terminal event", null);
            }
            ProviderExecutionResult result = new ProviderExecutionResult();
            result.setSessionId(StringUtils.trimToNull(state.sessionId));
            result.setTurnId(StringUtils.trimToNull(state.turnId));
            result.setFinalResponse(state.finalResponse.toString());
            result.setUsage(new LinkedHashMap<>(state.usage));
            return result;
        } finally {
            timeout.shutdownNow();
            running.remove(request.getRunId(), execution);
            closeQuietly(process.stdin());
            terminate(process);
            invocation.cleanup().run();
        }
    }

    @Override
    public final void cancel(String runId) {
        RunningProcess execution = running.get(runId);
        if (execution != null && execution.cancelled.compareAndSet(false, true)) {
            terminate(execution.process);
        }
    }

    protected abstract PreparedInvocation prepare(ProviderExecutionRequest request);

    protected abstract void handleEvent(JsonNode event, ParseState state, ProviderEventSink sink,
                                        OutputStream stdin);

    protected boolean requiresTerminalEvent() {
        return true;
    }

    protected abstract String displayName();

    protected void emit(ProviderEventSink sink, AgentRuntimeEventTypeEnum type, String content, JsonNode payload) {
        AgentRuntimeEvent event = new AgentRuntimeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setType(type);
        event.setContent(StringUtils.defaultString(content));
        event.setPayload(payload == null || payload.isNull()
                ? Map.of() : mapper.convertValue(payload, new TypeReference<Map<String, Object>>() { }));
        event.setOccurredAt(java.util.Date.from(Instant.now()));
        sink.emit(event);
    }

    protected List<String> customArguments(ProviderExecutionRequest request, Set<String> blocked) {
        List<String> custom = request.getRuntimeProfile().getCustomArguments() == null
                ? List.of() : request.getRuntimeProfile().getCustomArguments();
        for (String argument : custom) {
            String key = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
            if (blocked.contains(key)) {
                throw new IllegalArgumentException(displayName() + " custom arguments may not override " + key);
            }
        }
        return List.copyOf(custom);
    }

    protected void writeJsonLine(OutputStream stream, Object value) {
        try {
            stream.write(mapper.writeValueAsBytes(value));
            stream.write('\n');
            stream.flush();
        } catch (IOException exception) {
            throw failure(ProviderFailureKind.PROTOCOL_ERROR,
                    "failed to write " + displayName() + " protocol input", exception);
        }
    }

    protected static Map<String, String> mutableEnvironment(ProviderExecutionRequest request) {
        return new LinkedHashMap<>(request.getEnvironment());
    }

    private void validate(ProviderExecutionRequest request, ProviderEventSink sink,
                          ProviderLifecycleSink lifecycleSink) {
        if (request == null || sink == null || lifecycleSink == null || request.getRuntimeProfile() == null
                || request.getRuntimeProfile().getProvider() != provider()
                || request.getStartRequest() == null || request.getWorkingDirectory() == null
                || !request.getWorkingDirectory().isAbsolute()
                || StringUtils.isBlank(request.getRuntimeProfile().getExecutable())
                || !Path.of(request.getRuntimeProfile().getExecutable()).isAbsolute()) {
            throw new IllegalArgumentException(displayName() + " execution request is incomplete");
        }
        Integer timeout = request.getRuntimeProfile().getTimeoutSeconds();
        if (timeout != null && timeout <= 0) {
            throw new IllegalArgumentException(displayName() + " timeout must be positive");
        }
    }

    private int waitFor(ManagedProviderProcess process) {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(ProviderFailureKind.CANCELLED, displayName() + " execution was interrupted", exception);
        }
    }

    private void writeInitialInput(OutputStream stdin, byte[] input, boolean keepOpen) {
        try {
            if (input != null && input.length > 0) {
                stdin.write(input);
                stdin.flush();
            }
            if (!keepOpen) {
                stdin.close();
            }
        } catch (IOException exception) {
            throw failure(ProviderFailureKind.PROTOCOL_ERROR,
                    "failed to write " + displayName() + " input", exception);
        }
    }

    private void drainStderr(ManagedProviderProcess process, BoundedText stderr) {
        try (InputStreamReader reader = new InputStreamReader(process.stderr(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                stderr.append(buffer, count);
            }
        } catch (IOException ignored) {
            // Process termination commonly closes stderr while this reader is blocked.
        }
    }

    private String stderrSuffix(BoundedText stderr) {
        String value = stderr.value().trim();
        return value.isEmpty() ? "" : ": " + value;
    }

    private ProviderExecutionException failure(ProviderFailureKind kind, String message, Throwable cause) {
        return cause == null ? new ProviderExecutionException(kind, message)
                : new ProviderExecutionException(kind, message, cause);
    }

    private void terminate(ManagedProviderProcess process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(3))) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    protected record PreparedInvocation(List<String> command, Map<String, String> environment,
                                        byte[] initialInput, boolean keepStdinOpen, String sessionIdHint,
                                        Runnable cleanup) {
        protected PreparedInvocation {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            cleanup = cleanup == null ? () -> { } : cleanup;
        }
    }

    protected static final class ParseState {
        protected final StringBuilder finalResponse = new StringBuilder();
        protected final Map<String, Object> usage = new LinkedHashMap<>();
        protected String sessionId;
        protected String turnId;
        protected String failure;
        protected boolean terminal;
        protected boolean stepOpen;
        protected boolean continuationExpected;
    }

    private record RunningProcess(ManagedProviderProcess process, AtomicBoolean cancelled,
                                  AtomicBoolean timedOut) {
        private RunningProcess(ManagedProviderProcess process) {
            this(process, new AtomicBoolean(), new AtomicBoolean());
        }
    }

    private static final class BoundedText {
        private final int limit;
        private final StringBuilder value = new StringBuilder();

        private BoundedText(int limit) {
            this.limit = limit;
        }

        private synchronized void append(char[] chars, int count) {
            value.append(chars, 0, count);
            if (value.length() > limit) {
                value.delete(0, value.length() - limit);
            }
        }

        private synchronized String value() {
            return value.toString();
        }
    }
}
