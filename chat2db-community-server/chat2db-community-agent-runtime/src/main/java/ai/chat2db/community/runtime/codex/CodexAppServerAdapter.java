package ai.chat2db.community.runtime.codex;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
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
import ai.chat2db.community.runtime.provider.ProviderMcpEndpoint;
import ai.chat2db.community.runtime.provider.ProviderProcessLauncher;
import ai.chat2db.community.runtime.provider.RuntimePromptBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CodexAppServerAdapter implements ExternalProviderAdapter {

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final int DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 900;
    private static final Set<String> REASONING_EFFORTS = Set.of(
            "minimal", "low", "medium", "high", "xhigh", "max", "ultra");

    private final ObjectMapper mapper;
    private final ProviderProcessLauncher processLauncher;
    private final Clock clock;
    private final RuntimePromptBuilder promptBuilder = new RuntimePromptBuilder();
    private final Map<String, RunningExecution> running = new ConcurrentHashMap<>();

    public CodexAppServerAdapter() {
        this(new ObjectMapper(), new DefaultProviderProcessLauncher(), Clock.systemUTC());
    }

    CodexAppServerAdapter(ObjectMapper mapper, ProviderProcessLauncher processLauncher, Clock clock) {
        this.mapper = mapper;
        this.processLauncher = processLauncher;
        this.clock = clock;
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.CODEX;
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink eventSink,
                                           ProviderLifecycleSink lifecycleSink) {
        validate(request, eventSink, lifecycleSink);
        List<String> command = command(request.getRuntimeProfile());
        ManagedProviderProcess process;
        try {
            process = processLauncher.start(command, request.getWorkingDirectory(),
                    Map.copyOf(request.getEnvironment()));
        } catch (IOException exception) {
            throw new ProviderExecutionException(ProviderFailureKind.PROCESS_EXIT,
                    "failed to start Codex app-server", exception);
        }

        ExecutionState state = new ExecutionState(request.getRunId(), eventSink, nowMillis());
        RunningExecution execution = new RunningExecution(process, state);
        if (running.putIfAbsent(request.getRunId(), execution) != null) {
            terminate(process);
            throw new IllegalStateException("Codex Run is already executing: " + request.getRunId());
        }
        drainStderr(process);
        watchProcess(execution);

        try {
            lifecycleSink.processStarted("codex-process-" + UUID.randomUUID(), process.pid(),
                    process.startInstant(), command.get(0));
        } catch (RuntimeException exception) {
            running.remove(request.getRunId(), execution);
            terminate(process);
            throw exception;
        }

        try (CodexJsonRpcClient client = new CodexJsonRpcClient(
                mapper, process.stdout(), process.stdin(),
                message -> handleNotification(state, message),
                (method, params) -> handleServerRequest(state, method, params),
                failure -> {
                    if (process.isAlive()) {
                        state.failure.complete(failure);
                    }
                })) {
            execution.client.set(client);
            initialize(client);
            String threadId = startOrResumeThread(client, request);
            state.threadId.set(threadId);
            execution.threadId.set(threadId);
            ObjectNode session = mapper.createObjectNode();
            session.put("threadId", threadId);
            session.put("resumed", StringUtils.isNotBlank(request.getResumeSessionId()));
            emit(state, AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                    "Codex session updated", session);
            String turnId = startTurn(client, request, threadId);
            state.turnId.set(turnId);
            execution.turnId.set(turnId);
            lifecycleSink.turnStarted(threadId, turnId);
            return awaitCompletion(request, state);
        } finally {
            running.remove(request.getRunId(), execution);
            terminate(process);
        }
    }

    @Override
    public void cancel(String runId) {
        RunningExecution execution = running.get(runId);
        if (execution == null || !execution.cancelled.compareAndSet(false, true)) {
            return;
        }
        CodexJsonRpcClient client = execution.client.get();
        String threadId = execution.threadId.get();
        String turnId = execution.turnId.get();
        if (client != null && StringUtils.isNotBlank(threadId) && StringUtils.isNotBlank(turnId)) {
            ObjectNode params = mapper.createObjectNode();
            params.put("threadId", threadId);
            params.put("turnId", turnId);
            try {
                client.request("turn/interrupt", params, Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // Process termination below remains the authoritative cancellation fence.
            }
        }
        terminate(execution.process);
        execution.state.failure.complete(new ProviderExecutionException(
                ProviderFailureKind.CANCELLED, "Codex Run was cancelled"));
    }

    private void initialize(CodexJsonRpcClient client) {
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "chat2db-runtime-daemon");
        clientInfo.put("title", "Chat2DB Runtime Daemon");
        clientInfo.put("version", "1");
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.put("experimentalApi", true);
        ObjectNode params = mapper.createObjectNode();
        params.set("clientInfo", clientInfo);
        params.set("capabilities", capabilities);
        JsonNode response = client.request("initialize", params, REQUEST_TIMEOUT);
        if (!response.hasNonNull("userAgent")) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Codex initialize response is missing userAgent");
        }
        client.notify("initialized", mapper.createObjectNode());
    }

    private String startOrResumeThread(CodexJsonRpcClient client, ProviderExecutionRequest request) {
        ObjectNode params = commonThreadParams(request);
        JsonNode result;
        if (StringUtils.isNotBlank(request.getResumeSessionId())) {
            params.put("threadId", request.getResumeSessionId().trim());
            result = client.request("thread/resume", params, REQUEST_TIMEOUT);
        } else {
            params.put("ephemeral", false);
            params.put("sandbox", "read-only");
            params.put("approvalPolicy", "never");
            result = client.request("thread/start", params, REQUEST_TIMEOUT);
        }
        String threadId = result.path("thread").path("id").asText(null);
        if (StringUtils.isBlank(threadId)) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Codex thread response is missing thread.id");
        }
        return threadId;
    }

    private ObjectNode commonThreadParams(ProviderExecutionRequest request) {
        AgentRuntimeProfile profile = request.getRuntimeProfile();
        ObjectNode params = mapper.createObjectNode();
        params.put("cwd", request.getWorkingDirectory().toString());
        params.put("developerInstructions",
                "Obey the immutable Chat2DB task context and use only control-plane authorized tools. "
                        + "Never search for or expose database credentials.");
        params.putArray("runtimeWorkspaceRoots").add(request.getWorkingDirectory().toString());
        if (!request.getMcpEndpoints().isEmpty()) {
            ObjectNode mcpServers = params.putObject("config").putObject("mcp_servers");
            for (ProviderMcpEndpoint endpoint : request.getMcpEndpoints()) {
                ObjectNode server = mcpServers.putObject(endpoint.getName());
                server.put("url", endpoint.getUrl().toString());
                server.put("bearer_token_env_var", endpoint.getBearerTokenEnvironmentVariable());
                server.put("startup_timeout_sec", 10);
                server.put("tool_timeout_sec", Math.max(30,
                        profile.getTimeoutSeconds() == null ? 300 : profile.getTimeoutSeconds()));
            }
        }
        putIfNotBlank(params, "model", profile.getModel());
        putIfNotBlank(params, "serviceTier", profile.getServiceTier());
        return params;
    }

    private String startTurn(CodexJsonRpcClient client, ProviderExecutionRequest request, String threadId) {
        AgentRuntimeProfile profile = request.getRuntimeProfile();
        ObjectNode params = mapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("cwd", request.getWorkingDirectory().toString());
        params.put("approvalPolicy", "never");
        putIfNotBlank(params, "model", profile.getModel());
        putIfNotBlank(params, "serviceTier", profile.getServiceTier());
        String effort = StringUtils.lowerCase(StringUtils.trim(profile.getThinkingMode()));
        if (effort != null && REASONING_EFFORTS.contains(effort)) {
            params.put("effort", effort);
        }
        ArrayNode input = params.putArray("input");
        ObjectNode text = input.addObject();
        text.put("type", "text");
        text.put("text", promptBuilder.build(request.getStartRequest()));
        JsonNode result = client.request("turn/start", params, REQUEST_TIMEOUT);
        String turnId = result.path("turn").path("id").asText(null);
        if (StringUtils.isBlank(turnId)) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Codex turn/start response is missing turn.id");
        }
        return turnId;
    }

    private ProviderExecutionResult awaitCompletion(ProviderExecutionRequest request, ExecutionState state) {
        int timeoutSeconds = request.getRuntimeProfile().getTimeoutSeconds() == null
                ? DEFAULT_INACTIVITY_TIMEOUT_SECONDS : request.getRuntimeProfile().getTimeoutSeconds();
        while (true) {
            if (state.failure.isDone()) {
                throw failure(state.failure.join());
            }
            if (state.completed.isDone()) {
                JsonNode turn = state.completed.join();
                String status = turn.path("status").asText();
                if ("interrupted".equals(status)) {
                    throw new ProviderExecutionException(ProviderFailureKind.CANCELLED,
                            "Codex turn was interrupted");
                }
                if (!"completed".equals(status)) {
                    String message = turn.path("error").path("message")
                            .asText("Codex turn failed with status " + status);
                    throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR, message);
                }
                ProviderExecutionResult result = new ProviderExecutionResult();
                result.setSessionId(state.threadId.get());
                result.setTurnId(state.turnId.get());
                result.setFinalResponse(state.finalResponse.toString());
                result.setUsage(new LinkedHashMap<>(state.usage.get()));
                return result;
            }
            if (nowMillis() - state.lastActivity.get() > timeoutSeconds * 1000L) {
                throw new ProviderExecutionException(ProviderFailureKind.INACTIVITY_TIMEOUT,
                        "Codex app-server produced no activity for " + timeoutSeconds + " seconds");
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ProviderExecutionException(ProviderFailureKind.CANCELLED,
                        "Codex execution thread was interrupted", exception);
            }
        }
    }

    private void handleNotification(ExecutionState state, JsonNode message) {
        state.lastActivity.set(nowMillis());
        String method = message.path("method").asText();
        JsonNode params = message.path("params");
        switch (method) {
            case "item/agentMessage/delta" -> {
                String delta = params.path("delta").asText("");
                state.finalResponse.append(delta);
                emit(state, AgentRuntimeEventTypeEnum.MESSAGE_DELTA, delta, params);
            }
            case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" ->
                    emit(state, AgentRuntimeEventTypeEnum.REASONING_DELTA,
                            params.path("delta").asText(""), params);
            case "item/started" -> emitItem(state, AgentRuntimeEventTypeEnum.TOOL_CALL, params);
            case "item/completed" -> {
                JsonNode item = params.path("item");
                if ("agentMessage".equals(item.path("type").asText())
                        && state.finalResponse.isEmpty()) {
                    state.finalResponse.append(item.path("text").asText(""));
                } else {
                    emitItem(state, AgentRuntimeEventTypeEnum.TOOL_RESULT, params);
                }
            }
            case "thread/tokenUsage/updated" -> {
                Map<String, Object> usage = mapper.convertValue(params.path("tokenUsage"),
                        new TypeReference<>() { });
                state.usage.set(Collections.unmodifiableMap(new LinkedHashMap<>(usage)));
                emit(state, AgentRuntimeEventTypeEnum.USAGE, "Codex token usage updated", params);
            }
            case "error" -> {
                emit(state, AgentRuntimeEventTypeEnum.ERROR,
                        params.path("error").path("message").asText("Codex protocol error"), params);
                if (!params.path("willRetry").asBoolean(false)) {
                    state.failure.complete(new ProviderExecutionException(
                            ProviderFailureKind.PROTOCOL_ERROR,
                            params.path("error").path("message").asText("Codex protocol error")));
                }
            }
            case "turn/completed" -> state.completed.complete(params.path("turn"));
            default -> {
                // Unknown notifications are forward-compatible and intentionally ignored.
            }
        }
    }

    private void emitItem(ExecutionState state, AgentRuntimeEventTypeEnum type, JsonNode params) {
        JsonNode item = params.path("item");
        String itemType = item.path("type").asText();
        if (Set.of("mcpToolCall", "dynamicToolCall", "commandExecution", "fileChange")
                .contains(itemType)) {
            emit(state, type, itemType + ": " + item.path("id").asText(), params);
        }
    }

    private JsonNode handleServerRequest(ExecutionState state, String method, JsonNode params) {
        state.lastActivity.set(nowMillis());
        if (method.endsWith("requestApproval") || method.endsWith("Approval")
                || "mcpServer/elicitation/request".equals(method)) {
            emit(state, AgentRuntimeEventTypeEnum.APPROVAL_REQUIRED,
                    "Codex requested approval; the current adapter denied it", params);
            ObjectNode response = mapper.createObjectNode();
            response.put("decision", "decline");
            return response;
        }
        if ("item/tool/call".equals(method)) {
            ObjectNode response = mapper.createObjectNode();
            response.put("success", false);
            ArrayNode content = response.putArray("contentItems");
            content.addObject().put("type", "inputText")
                    .put("text", "Chat2DB dynamic tool bridge is not enabled for this Runtime Profile");
            return response;
        }
        throw new UnsupportedOperationException(method);
    }

    private void emit(ExecutionState state, AgentRuntimeEventTypeEnum type,
                      String content, JsonNode providerPayload) {
        AgentRuntimeEvent event = new AgentRuntimeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(state.runId);
        event.setType(type);
        event.setContent(content);
        Map<String, Object> payload = mapper.convertValue(providerPayload, new TypeReference<>() { });
        event.setPayload(new LinkedHashMap<>(payload));
        event.setOccurredAt(Date.from(clock.instant()));
        state.eventSink.emit(event);
    }

    private List<String> command(AgentRuntimeProfile profile) {
        String executable = StringUtils.trimToNull(profile.getExecutable());
        if (executable == null || !Path.of(executable).isAbsolute()) {
            throw new IllegalArgumentException("Codex executable must be an absolute path");
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("app-server");
        command.add("--listen");
        command.add("stdio://");
        for (String argument : profile.getCustomArguments() == null ? List.<String>of()
                : profile.getCustomArguments()) {
            String normalized = validateArgument(argument);
            if (Set.of("app-server", "--listen", "--stdio", "daemon", "proxy")
                    .contains(normalized)) {
                throw new IllegalArgumentException("Codex custom argument may not override app-server transport");
            }
            command.add(normalized);
        }
        return List.copyOf(command);
    }

    private String validateArgument(String argument) {
        String normalized = StringUtils.trimToNull(argument);
        if (normalized == null || normalized.indexOf('\0') >= 0
                || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException("Codex custom arguments must be non-empty single-line values");
        }
        return normalized;
    }

    private void validate(ProviderExecutionRequest request, ProviderEventSink sink,
                          ProviderLifecycleSink lifecycleSink) {
        if (request == null || StringUtils.isBlank(request.getRunId()) || request.getLeaseAttempt() <= 0
                || request.getRuntimeProfile() == null || request.getStartRequest() == null
                || request.getWorkingDirectory() == null || request.getEnvironment() == null
                || request.getMcpEndpoints() == null
                || sink == null || lifecycleSink == null) {
            throw new IllegalArgumentException("Codex execution requires Run, attempt, profile, context, workspace and event sink");
        }
        if (request.getRuntimeProfile().getProvider() != AgentRuntimeProviderEnum.CODEX) {
            throw new IllegalArgumentException("Codex adapter requires a CODEX Runtime Profile");
        }
        for (ProviderMcpEndpoint endpoint : request.getMcpEndpoints()) {
            if (endpoint == null || StringUtils.isBlank(endpoint.getName())
                    || !endpoint.getName().matches("[A-Za-z0-9_-]+") || endpoint.getUrl() == null
                    || !"http".equalsIgnoreCase(endpoint.getUrl().getScheme())
                    || endpoint.getUrl().getHost() == null
                    || !endpoint.getUrl().getHost().matches("(?i)(localhost|127\\.0\\.0\\.1|::1)")
                    || StringUtils.isBlank(endpoint.getBearerTokenEnvironmentVariable())
                    || !request.getEnvironment().containsKey(endpoint.getBearerTokenEnvironmentVariable())) {
                throw new IllegalArgumentException(
                        "Codex MCP endpoints must be loopback and use an injected bearer token");
            }
        }
        Path workspace = request.getWorkingDirectory().normalize();
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Codex workspace must be an existing absolute directory");
        }
        request.getEnvironment().forEach((name, value) -> {
            if (StringUtils.isBlank(name) || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0
                    || value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Codex process environment contains an invalid entry");
            }
        });
    }

    private void watchProcess(RunningExecution execution) {
        Thread watcher = new Thread(() -> {
            try {
                int exitCode = execution.process.waitFor();
                if (!execution.state.completed.isDone() && !execution.cancelled.get()) {
                    execution.state.failure.complete(new ProviderExecutionException(
                            ProviderFailureKind.PROCESS_EXIT,
                            "Codex app-server exited before turn completion with code " + exitCode));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "chat2db-codex-process-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void drainStderr(ManagedProviderProcess process) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.stderr(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Never relay provider stderr: it can contain prompts or credentials.
                }
            } catch (IOException ignored) {
                // Process exit will be classified by the watcher.
            }
        }, "chat2db-codex-stderr-drainer");
        drainer.setDaemon(true);
        drainer.start();
    }

    private void terminate(ManagedProviderProcess process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(2))) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(2));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private ProviderExecutionException failure(Throwable failure) {
        if (failure instanceof ProviderExecutionException providerFailure) {
            return providerFailure;
        }
        return new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                "Codex app-server protocol failed", failure);
    }

    private void putIfNotBlank(ObjectNode target, String field, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(field, value.trim());
        }
    }

    private long nowMillis() {
        return Instant.now(clock).toEpochMilli();
    }

    private static final class ExecutionState {
        private final String runId;
        private final ProviderEventSink eventSink;
        private final AtomicLong lastActivity;
        private final AtomicReference<String> threadId = new AtomicReference<>();
        private final AtomicReference<String> turnId = new AtomicReference<>();
        private final StringBuilder finalResponse = new StringBuilder();
        private final AtomicReference<Map<String, Object>> usage = new AtomicReference<>(Map.of());
        private final CompletableFuture<JsonNode> completed = new CompletableFuture<>();
        private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

        private ExecutionState(String runId, ProviderEventSink eventSink, long now) {
            this.runId = runId;
            this.eventSink = eventSink;
            this.lastActivity = new AtomicLong(now);
        }
    }

    private static final class RunningExecution {
        private final ManagedProviderProcess process;
        private final ExecutionState state;
        private final AtomicReference<CodexJsonRpcClient> client = new AtomicReference<>();
        private final AtomicReference<String> threadId = new AtomicReference<>();
        private final AtomicReference<String> turnId = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RunningExecution(ManagedProviderProcess process, ExecutionState state) {
            this.process = process;
            this.state = state;
        }
    }
}
