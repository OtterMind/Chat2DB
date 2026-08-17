package ai.chat2db.community.runtime.hermes;

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
import ai.chat2db.community.runtime.provider.ProviderApprovalRequest;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
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
import java.util.ArrayList;
import java.util.Date;
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

/** Executes Hermes through the ACP v1 stdio protocol. */
public class HermesAcpAdapter implements ExternalProviderAdapter {

    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);
    static final Duration SESSION_START_TIMEOUT = Duration.ofSeconds(60);
    static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final Set<String> BLOCKED_ARGUMENTS = Set.of(
            "acp", "--accept-hooks", "--yes", "-y", "--setup", "--setup-browser");
    private static final Set<String> GRANT_KINDS = Set.of("allow_once", "allow_always");
    private static final Set<String> REJECT_KINDS = Set.of("reject_once", "deny");

    private final ObjectMapper mapper;
    private final ProviderProcessLauncher processLauncher;
    private final Clock clock;
    private final RuntimePromptBuilder promptBuilder = new RuntimePromptBuilder();
    private final Map<String, RunningExecution> running = new ConcurrentHashMap<>();

    public HermesAcpAdapter() {
        this(new ObjectMapper(), new DefaultProviderProcessLauncher(), Clock.systemUTC());
    }

    HermesAcpAdapter(ObjectMapper mapper, ProviderProcessLauncher processLauncher, Clock clock) {
        this.mapper = mapper;
        this.processLauncher = processLauncher;
        this.clock = clock;
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.HERMES;
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink eventSink,
                                           ProviderLifecycleSink lifecycleSink) {
        validate(request, eventSink, lifecycleSink);
        Map<String, String> environment = safeEnvironment(request.getEnvironment());
        ManagedProviderProcess process;
        try {
            process = processLauncher.start(command(request.getRuntimeProfile()),
                    request.getWorkingDirectory(), environment);
        } catch (IOException exception) {
            throw new ProviderExecutionException(ProviderFailureKind.PROCESS_EXIT,
                    "failed to start Hermes ACP", exception);
        }

        ExecutionState state = new ExecutionState(request.getRunId(), eventSink, clock.millis());
        RunningExecution execution = new RunningExecution(process, state);
        if (running.putIfAbsent(request.getRunId(), execution) != null) {
            terminate(process);
            throw new IllegalStateException("Hermes Run is already executing: " + request.getRunId());
        }
        drainStderr(process);
        watchProcess(execution);
        try {
            lifecycleSink.processStarted("hermes-process-" + UUID.randomUUID(), process.pid(),
                    process.startInstant(), request.getRuntimeProfile().getExecutable());
        } catch (RuntimeException exception) {
            running.remove(request.getRunId(), execution);
            terminate(process);
            throw exception;
        }

        try (AcpJsonRpcClient client = new AcpJsonRpcClient(
                mapper, process.stdout(), process.stdin(),
                message -> handleNotification(state, message),
                (requestId, method, params) -> handleAgentRequest(request, state, requestId, method, params),
                failure -> {
                    if (process.isAlive()) {
                        state.failure.complete(failure);
                    }
                })) {
            execution.client.set(client);
            initialize(client);
            String sessionId = startOrResumeSession(client, request, environment);
            state.sessionId.set(sessionId);
            execution.sessionId.set(sessionId);
            ObjectNode sessionPayload = mapper.createObjectNode();
            sessionPayload.put("sessionId", sessionId);
            sessionPayload.put("resumed", StringUtils.isNotBlank(request.getResumeSessionId()));
            emit(state, AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                    "Hermes session updated", sessionPayload);
            lifecycleSink.turnStarted(sessionId, "hermes-turn-" + UUID.randomUUID());
            JsonNode promptResult = prompt(client, request, sessionId);
            return result(state, promptResult);
        } catch (ProviderExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (execution.cancelled.get()) {
                throw new ProviderExecutionException(ProviderFailureKind.CANCELLED,
                        "Hermes Run was cancelled", exception);
            }
            if (state.failure.isDone()) {
                throw failure(state.failure.join());
            }
            if (!process.isAlive()) {
                throw new ProviderExecutionException(ProviderFailureKind.PROCESS_EXIT,
                        "Hermes ACP exited before prompt completion", exception);
            }
            if (StringUtils.contains(exception.getMessage(), "Hermes ACP request timed out: session/prompt")) {
                throw new ProviderExecutionException(ProviderFailureKind.INACTIVITY_TIMEOUT,
                        "Hermes ACP prompt exceeded the Runtime Profile timeout", exception);
            }
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Hermes ACP protocol failed", exception);
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
        AcpJsonRpcClient client = execution.client.get();
        String sessionId = execution.sessionId.get();
        if (client != null && StringUtils.isNotBlank(sessionId)) {
            ObjectNode params = mapper.createObjectNode();
            params.put("sessionId", sessionId);
            try {
                client.notify("session/cancel", params);
            } catch (RuntimeException ignored) {
                // Process termination below is the cancellation fence.
            }
        }
        terminate(execution.process);
        execution.state.failure.complete(new ProviderExecutionException(
                ProviderFailureKind.CANCELLED, "Hermes Run was cancelled"));
    }

    private void initialize(AcpJsonRpcClient client) {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", 1);
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", "chat2db-runtime-daemon");
        info.put("version", "1");
        params.putObject("clientCapabilities");
        JsonNode result = client.request("initialize", params, HANDSHAKE_TIMEOUT);
        if (!result.isObject()) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Hermes initialize returned an invalid response");
        }
    }

    private String startOrResumeSession(AcpJsonRpcClient client, ProviderExecutionRequest request,
                                        Map<String, String> environment) {
        ObjectNode params = mapper.createObjectNode();
        params.put("cwd", request.getWorkingDirectory().toString());
        params.set("mcpServers", mcpServers(request, environment));
        String method;
        if (StringUtils.isNotBlank(request.getResumeSessionId())) {
            method = "session/resume";
            params.put("sessionId", request.getResumeSessionId().trim());
        } else {
            method = "session/new";
            if (StringUtils.isNotBlank(request.getRuntimeProfile().getModel())) {
                params.put("model", request.getRuntimeProfile().getModel().trim());
            }
        }
        JsonNode result = client.request(method, params, SESSION_START_TIMEOUT);
        String sessionId = result.path("sessionId").asText(null);
        if (StringUtils.isBlank(sessionId)) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Hermes " + method + " returned no sessionId");
        }
        return sessionId;
    }

    private JsonNode prompt(AcpJsonRpcClient client, ProviderExecutionRequest request, String sessionId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        ArrayNode prompt = params.putArray("prompt");
        prompt.addObject().put("type", "text")
                .put("text", promptBuilder.build(request.getStartRequest()));
        int timeoutSeconds = request.getRuntimeProfile().getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : request.getRuntimeProfile().getTimeoutSeconds();
        return client.request("session/prompt", params, Duration.ofSeconds(timeoutSeconds));
    }

    private ProviderExecutionResult result(ExecutionState state, JsonNode promptResult) {
        if (state.failure.isDone()) {
            throw failure(state.failure.join());
        }
        String stopReason = promptResult.path("stopReason").asText("end_turn");
        if (Set.of("cancelled", "canceled").contains(stopReason)) {
            throw new ProviderExecutionException(ProviderFailureKind.CANCELLED,
                    "Hermes cancelled the prompt");
        }
        ProviderExecutionResult result = new ProviderExecutionResult();
        result.setSessionId(state.sessionId.get());
        result.setTurnId("hermes-turn");
        result.setFinalResponse(state.finalResponse.toString());
        Map<String, Object> usage = usage(promptResult.path("usage"));
        if (usage.isEmpty()) {
            usage = state.usage.get();
        }
        result.setUsage(new LinkedHashMap<>(usage));
        return result;
    }

    private void handleNotification(ExecutionState state, JsonNode message) {
        state.lastActivity.set(clock.millis());
        String method = message.path("method").asText();
        if (!Set.of("session/update", "session/notification").contains(method)) {
            return;
        }
        JsonNode update = message.path("params").path("update");
        String type = normalizeUpdateType(update);
        switch (type) {
            case "agent_message_chunk" -> {
                String text = update.path("content").path("text").asText("");
                state.finalResponse.append(text);
                emit(state, AgentRuntimeEventTypeEnum.MESSAGE_DELTA, text, update);
            }
            case "agent_thought_chunk" -> emit(state, AgentRuntimeEventTypeEnum.REASONING_DELTA,
                    update.path("content").path("text").asText(""), update);
            case "tool_call" -> emit(state, AgentRuntimeEventTypeEnum.TOOL_CALL,
                    toolSummary(update), update);
            case "tool_call_update" -> {
                String status = update.path("status").asText();
                if (Set.of("completed", "failed").contains(status)) {
                    emit(state, AgentRuntimeEventTypeEnum.TOOL_RESULT, toolSummary(update), update);
                }
            }
            case "usage_update" -> {
                Map<String, Object> usage = usage(update.path("usage"));
                state.usage.set(Map.copyOf(usage));
                emit(state, AgentRuntimeEventTypeEnum.USAGE, "Hermes token usage updated", update);
            }
            default -> {
                // Unknown ACP updates are forward-compatible.
            }
        }
    }

    private JsonNode handleAgentRequest(ProviderExecutionRequest executionRequest, ExecutionState state,
                                        JsonNode requestId, String method, JsonNode params) {
        if (!"session/request_permission".equals(method)) {
            throw new UnsupportedOperationException(method);
        }
        emit(state, AgentRuntimeEventTypeEnum.APPROVAL_REQUIRED,
                "Hermes requested permission; no automatic approval was granted", params);
        String rejectOption = null;
        String allowOption = null;
        for (JsonNode option : params.path("options")) {
            String kind = StringUtils.lowerCase(option.path("kind").asText());
            if (GRANT_KINDS.contains(kind) && option.hasNonNull("optionId") && allowOption == null) {
                allowOption = option.path("optionId").asText();
            }
            if (REJECT_KINDS.contains(kind) && option.hasNonNull("optionId")) {
                rejectOption = option.path("optionId").asText();
            }
        }
        if (StringUtils.isBlank(allowOption) || StringUtils.isBlank(rejectOption)
                || executionRequest.getApprovalHandler() == null) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Hermes permission request cannot be represented by the Approval Bridge");
        }
        ProviderApprovalRequest approvalRequest = new ProviderApprovalRequest();
        approvalRequest.setProviderRequestId(requestId.asText());
        approvalRequest.setToolCallId(StringUtils.trimToNull(params.path("toolCall").path("toolCallId").asText()));
        approvalRequest.setTitle(StringUtils.defaultIfBlank(
                params.path("toolCall").path("title").asText(), "Hermes tool permission"));
        approvalRequest.setPayload(mapper.convertValue(params, new TypeReference<>() { }));
        approvalRequest.setAllowOptionId(allowOption);
        approvalRequest.setRejectOptionId(rejectOption);
        ProviderApprovalDecision decision = executionRequest.getApprovalHandler().request(approvalRequest);
        if (decision == null || StringUtils.isBlank(decision.getSelectedOptionId())
                || (!decision.getSelectedOptionId().equals(allowOption)
                && !decision.getSelectedOptionId().equals(rejectOption))) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "Approval Bridge returned an option Hermes did not offer");
        }
        ObjectNode result = mapper.createObjectNode();
        ObjectNode outcome = result.putObject("outcome");
        outcome.put("outcome", "selected");
        outcome.put("optionId", decision.getSelectedOptionId());
        return result;
    }

    private ArrayNode mcpServers(ProviderExecutionRequest request, Map<String, String> environment) {
        ArrayNode servers = mapper.createArrayNode();
        for (ProviderMcpEndpoint endpoint : request.getMcpEndpoints()) {
            ObjectNode server = servers.addObject();
            server.put("type", "http");
            server.put("name", endpoint.getName());
            server.put("url", endpoint.getUrl().toString());
            ArrayNode headers = server.putArray("headers");
            String token = environment.get(endpoint.getBearerTokenEnvironmentVariable());
            headers.addObject().put("name", "Authorization").put("value", "Bearer " + token);
        }
        return servers;
    }

    private Map<String, Object> usage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<>() { });
    }

    private String normalizeUpdateType(JsonNode update) {
        String value = update.path("sessionUpdate").asText(update.path("type").asText(""));
        return value.replace('-', '_').replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String toolSummary(JsonNode update) {
        String name = update.path("name").asText(update.path("title").asText("tool"));
        String callId = update.path("toolCallId").asText("");
        return callId.isBlank() ? name : name + ": " + callId;
    }

    private void emit(ExecutionState state, AgentRuntimeEventTypeEnum type,
                      String content, JsonNode providerPayload) {
        AgentRuntimeEvent event = new AgentRuntimeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(state.runId);
        event.setType(type);
        event.setContent(content);
        event.setPayload(mapper.convertValue(providerPayload, new TypeReference<>() { }));
        event.setOccurredAt(Date.from(clock.instant()));
        state.eventSink.emit(event);
    }

    private List<String> command(AgentRuntimeProfile profile) {
        String executable = StringUtils.trimToNull(profile.getExecutable());
        if (executable == null || !Path.of(executable).isAbsolute()) {
            throw new IllegalArgumentException("Hermes executable must be an absolute path");
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("acp");
        for (String argument : profile.getCustomArguments() == null
                ? List.<String>of() : profile.getCustomArguments()) {
            String normalized = validateArgument(argument);
            if (BLOCKED_ARGUMENTS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "Hermes custom arguments may not override ACP transport or approval policy");
            }
            command.add(normalized);
        }
        return List.copyOf(command);
    }

    private String validateArgument(String argument) {
        String normalized = StringUtils.trimToNull(argument);
        if (normalized == null || normalized.indexOf('\0') >= 0
                || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException("Hermes custom arguments must be non-empty single-line values");
        }
        return normalized;
    }

    private Map<String, String> safeEnvironment(Map<String, String> requested) {
        LinkedHashMap<String, String> environment = new LinkedHashMap<>(requested);
        environment.remove("HERMES_YOLO_MODE");
        environment.remove("HERMES_ACCEPT_HOOKS");
        return Map.copyOf(environment);
    }

    private void validate(ProviderExecutionRequest request, ProviderEventSink sink,
                          ProviderLifecycleSink lifecycleSink) {
        if (request == null || StringUtils.isBlank(request.getRunId()) || request.getLeaseAttempt() <= 0
                || request.getRuntimeProfile() == null || request.getStartRequest() == null
                || request.getWorkingDirectory() == null || request.getEnvironment() == null
                || request.getMcpEndpoints() == null || sink == null || lifecycleSink == null) {
            throw new IllegalArgumentException(
                    "Hermes execution requires Run, attempt, profile, context, workspace and event sink");
        }
        if (request.getRuntimeProfile().getProvider() != AgentRuntimeProviderEnum.HERMES) {
            throw new IllegalArgumentException("Hermes adapter requires a HERMES Runtime Profile");
        }
        if (!Boolean.TRUE.equals(request.getRuntimeProfile().getApprovalBridgeEnabled())
                || request.getApprovalHandler() == null) {
            throw new IllegalArgumentException("Hermes execution requires the Chat2DB Approval Bridge");
        }
        Path workspace = request.getWorkingDirectory().normalize();
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Hermes workspace must be an existing absolute directory");
        }
        for (ProviderMcpEndpoint endpoint : request.getMcpEndpoints()) {
            if (endpoint == null || StringUtils.isBlank(endpoint.getName()) || endpoint.getUrl() == null
                    || !"http".equalsIgnoreCase(endpoint.getUrl().getScheme())
                    || endpoint.getUrl().getHost() == null
                    || !endpoint.getUrl().getHost().matches("(?i)(localhost|127\\.0\\.0\\.1|::1)")
                    || StringUtils.isBlank(endpoint.getBearerTokenEnvironmentVariable())
                    || !request.getEnvironment().containsKey(endpoint.getBearerTokenEnvironmentVariable())) {
                throw new IllegalArgumentException(
                        "Hermes MCP endpoints must be loopback and use an injected bearer token");
            }
        }
    }

    private void watchProcess(RunningExecution execution) {
        Thread watcher = new Thread(() -> {
            try {
                int exitCode = execution.process.waitFor();
                if (!execution.cancelled.get()) {
                    execution.state.failure.complete(new ProviderExecutionException(
                            ProviderFailureKind.PROCESS_EXIT,
                            "Hermes ACP exited before completion with code " + exitCode));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "chat2db-hermes-process-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void drainStderr(ManagedProviderProcess process) {
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.stderr(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Provider stderr may contain prompts or credentials and is never relayed.
                }
            } catch (IOException ignored) {
                // Process exit is classified by the watcher.
            }
        }, "chat2db-hermes-stderr-drainer");
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
                "Hermes ACP protocol failed", failure);
    }

    private static final class ExecutionState {
        private final String runId;
        private final ProviderEventSink eventSink;
        private final AtomicLong lastActivity;
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final StringBuilder finalResponse = new StringBuilder();
        private final AtomicReference<Map<String, Object>> usage = new AtomicReference<>(Map.of());
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
        private final AtomicReference<AcpJsonRpcClient> client = new AtomicReference<>();
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RunningExecution(ManagedProviderProcess process, ExecutionState state) {
            this.process = process;
            this.state = state;
        }
    }
}
