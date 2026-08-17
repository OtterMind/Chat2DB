package ai.chat2db.community.runtime.dsh;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.runtime.provider.DefaultProviderProcessLauncher;
import ai.chat2db.community.runtime.provider.ExternalProviderAdapter;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
import ai.chat2db.community.runtime.provider.ProviderApprovalRequest;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a bundled, versioned JSON-RPC bridge over stdio. The bridge owns the
 * loopback-only DSH Web Host and translates its HTTP/WebSocket contract into a
 * stable Chat2DB Runtime protocol.
 */
public class DshRuntimeBridgeAdapter implements ExternalProviderAdapter {

    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(45);
    static final int DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 900;
    private static final String BRIDGE_RESOURCE = "/agent-runtime/dsh-runtime-bridge.mjs";
    private static final Set<String> RESERVED_ARGUMENTS = Set.of(
            "--profile", "--patch", "--host", "--port", "--trusted-host");

    private final ObjectMapper mapper;
    private final ProviderProcessLauncher processLauncher;
    private final Clock clock;
    private final RuntimePromptBuilder promptBuilder = new RuntimePromptBuilder();
    private final Map<String, RunningExecution> running = new ConcurrentHashMap<>();

    public DshRuntimeBridgeAdapter() {
        this(new ObjectMapper(), new DefaultProviderProcessLauncher(), Clock.systemUTC());
    }

    DshRuntimeBridgeAdapter(ObjectMapper mapper, ProviderProcessLauncher processLauncher, Clock clock) {
        this.mapper = mapper;
        this.processLauncher = processLauncher;
        this.clock = clock;
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.DSH;
    }

    @Override
    public ProviderExecutionResult execute(ProviderExecutionRequest request, ProviderEventSink eventSink,
                                           ProviderLifecycleSink lifecycleSink) {
        validate(request, eventSink, lifecycleSink);
        List<String> customArguments = safeArguments(request.getRuntimeProfile());
        Path bridge = materializeBridge(request.getWorkingDirectory());
        Path patch = writeMcpPatch(request);
        List<String> command = List.of(resolveNode(request.getRuntimeProfile()).toString(), bridge.toString());
        ManagedProviderProcess process;
        try {
            process = processLauncher.start(command, request.getWorkingDirectory(), Map.copyOf(request.getEnvironment()));
        } catch (IOException exception) {
            throw new ProviderExecutionException(ProviderFailureKind.PROCESS_EXIT,
                    "failed to start DSH Runtime Bridge", exception);
        }

        ExecutionState state = new ExecutionState(request.getRunId(), eventSink);
        RunningExecution execution = new RunningExecution(process, state);
        if (running.putIfAbsent(request.getRunId(), execution) != null) {
            terminate(process);
            throw new IllegalStateException("DSH Run is already executing: " + request.getRunId());
        }
        drainStderr(process);
        try {
            lifecycleSink.processStarted("dsh-bridge-" + UUID.randomUUID(), process.pid(),
                    process.startInstant(), command.get(0));
            try (DshBridgeJsonRpcClient client = new DshBridgeJsonRpcClient(
                    mapper, process.stdout(), process.stdin(),
                    message -> handleNotification(request, lifecycleSink, state, execution, message),
                    failure -> state.failure.complete(failure))) {
                execution.client.set(client);
                initialize(client, request, patch, customArguments);
                JsonNode result;
                try {
                    result = client.request("turn/start", turnParams(request), executionTimeout(request));
                } catch (DshBridgeJsonRpcClient.DshBridgeException exception) {
                    ProviderFailureKind kind = exception.code() == -32800
                            ? ProviderFailureKind.CANCELLED : ProviderFailureKind.PROTOCOL_ERROR;
                    throw new ProviderExecutionException(kind, exception.getMessage(), exception);
                } catch (RuntimeException exception) {
                    if (state.failure.isDone()) {
                        throw providerFailure(state.failure.join());
                    }
                    throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                            "DSH Runtime Bridge request failed", exception);
                }
                if (state.failure.isDone()) {
                    throw providerFailure(state.failure.join());
                }
                return result(result);
            }
        } finally {
            running.remove(request.getRunId(), execution);
            terminate(process);
        }
    }

    @Override
    public void cancel(String runId) {
        RunningExecution execution = running.get(runId);
        if (execution == null || !execution.cancelled.compareAndSet(false, true)) return;
        DshBridgeJsonRpcClient client = execution.client.get();
        if (client != null) {
            try {
                client.request("turn/cancel", mapper.createObjectNode(), Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // Killing the owned bridge process below remains the final cancellation fence.
            }
        }
        terminate(execution.process);
        execution.state.failure.complete(new ProviderExecutionException(
                ProviderFailureKind.CANCELLED, "DSH Run was cancelled"));
    }

    private void initialize(DshBridgeJsonRpcClient client, ProviderExecutionRequest request, Path patch,
                            List<String> customArguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("executable", request.getRuntimeProfile().getExecutable());
        params.put("cwd", request.getWorkingDirectory().toString());
        if (patch != null) params.putArray("patches").add(patch.toString());
        var arguments = params.putArray("customArguments");
        for (String argument : customArguments) arguments.add(argument);
        JsonNode response = client.request("initialize", params, HANDSHAKE_TIMEOUT);
        if (!"chat2db-dsh-bridge-v1".equals(response.path("protocolVersion").asText())) {
            throw new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                    "DSH Runtime Bridge returned an unsupported protocol version");
        }
    }

    private ObjectNode turnParams(ProviderExecutionRequest request) {
        ObjectNode params = mapper.createObjectNode();
        params.put("cwd", request.getWorkingDirectory().toString());
        params.put("prompt", promptBuilder.build(request.getStartRequest()));
        if (StringUtils.isNotBlank(request.getResumeSessionId())) {
            params.put("resumeSessionId", request.getResumeSessionId().trim());
        }
        return params;
    }

    private void handleNotification(ProviderExecutionRequest request, ProviderLifecycleSink lifecycleSink,
                                    ExecutionState state, RunningExecution execution, JsonNode message) {
        String method = message.path("method").asText();
        JsonNode params = message.path("params");
        switch (method) {
            case "runtime/event" -> emitProviderEvent(state, params);
            case "runtime/session-updated" -> {
                state.sessionId.set(params.path("sessionId").asText());
                emit(state, AgentRuntimeEventTypeEnum.SESSION_UPDATED, "DSH session updated", params);
            }
            case "runtime/turn-started" -> {
                String sessionId = params.path("sessionId").asText();
                String turnId = params.path("turnId").asText();
                state.sessionId.set(sessionId);
                state.turnId.set(turnId);
                lifecycleSink.turnStarted(sessionId, turnId);
            }
            case "runtime/approval-requested" -> CompletableFuture.runAsync(
                    () -> handleApproval(request, execution, state, params));
            case "bridge/error" -> {
                ProviderExecutionException failure = new ProviderExecutionException(
                        ProviderFailureKind.PROTOCOL_ERROR,
                        params.path("message").asText("DSH Runtime Bridge failed"));
                if (state.failure.complete(failure)) terminate(execution.process);
            }
            default -> {
                // Unknown bridge notifications are forward-compatible.
            }
        }
    }

    private void handleApproval(ProviderExecutionRequest request, RunningExecution execution,
                                ExecutionState state, JsonNode params) {
        try {
            ProviderApprovalRequest approval = new ProviderApprovalRequest();
            approval.setProviderRequestId(params.path("approvalId").asText());
            approval.setToolCallId(StringUtils.trimToNull(params.path("callId").asText()));
            approval.setTitle("DSH tool approval: " + params.path("toolName").asText("tool"));
            approval.setPayload(mapper.convertValue(params, new TypeReference<>() { }));
            approval.setAllowOptionId("allowed-once");
            approval.setRejectOptionId("rejected");
            ProviderApprovalDecision decision = request.getApprovalHandler().request(approval);
            ObjectNode response = mapper.createObjectNode();
            response.put("rpcId", params.path("rpcId").asText());
            response.put("sessionId", params.path("sessionId").asText());
            response.put("approvalId", params.path("approvalId").asText());
            response.put("approved", decision != null && decision.isApproved());
            DshBridgeJsonRpcClient client = execution.client.get();
            if (client != null) client.request("approval/respond", response, Duration.ofSeconds(15));
        } catch (RuntimeException failure) {
            state.failure.complete(failure);
            cancel(request.getRunId());
        }
    }

    private void emitProviderEvent(ExecutionState state, JsonNode params) {
        AgentRuntimeEventTypeEnum type;
        try {
            type = AgentRuntimeEventTypeEnum.valueOf(params.path("type").asText());
        } catch (IllegalArgumentException exception) {
            return;
        }
        JsonNode payload = params.path("payload");
        String content = params.path("content").asText("");
        if (type == AgentRuntimeEventTypeEnum.TOOL_CALL || type == AgentRuntimeEventTypeEnum.TOOL_RESULT) {
            ToolEvent toolEvent = normalizeToolEvent(state, type, content, payload);
            content = toolEvent.content();
            payload = toolEvent.payload();
        }
        emit(state, type, content, payload);
    }

    private ToolEvent normalizeToolEvent(ExecutionState state, AgentRuntimeEventTypeEnum type,
                                         String content, JsonNode providerPayload) {
        JsonNode event = providerPayload.path("event");
        JsonNode data = event.path("data");
        ObjectNode normalized = mapper.createObjectNode();
        if (providerPayload.isObject()) normalized.setAll((ObjectNode) providerPayload.deepCopy());

        String callId;
        String name;
        if (type == AgentRuntimeEventTypeEnum.TOOL_CALL) {
            callId = data.path("callId").asText("");
            name = data.path("name").asText("tool");
            if (StringUtils.isNotBlank(callId)) state.toolNames.put(callId, name);
            normalized.put("arguments", data.path("arguments").asText(""));
        } else {
            JsonNode message = data.path("message");
            callId = message.path("source").path("callId").asText("");
            if (StringUtils.isBlank(callId)) callId = nestedToolCallId(message.path("content"));
            name = StringUtils.defaultIfBlank(state.toolNames.get(callId), "tool");
            boolean success = !containsToolError(message.path("content"));
            normalized.put("success", success);
            normalized.put("status", success ? "COMPLETED" : "FAILED");
            if (StringUtils.isBlank(content)) content = toolResultText(message.path("content"));
            if (StringUtils.isBlank(content)) content = providerPayload.path("view").path("view")
                    .path("output").asText("");
            if (StringUtils.isBlank(content) && providerPayload.path("view").path("view").isObject()) {
                content = providerPayload.path("view").path("view").toString();
            }
        }
        normalized.put("toolCallId", callId);
        normalized.put("name", name);
        return new ToolEvent(content, normalized);
    }

    private String nestedToolCallId(JsonNode blocks) {
        if (!blocks.isArray()) return "";
        for (JsonNode block : blocks) {
            String id = block.path("toolCallId").asText("");
            if (StringUtils.isNotBlank(id)) return id;
        }
        return "";
    }

    private boolean containsToolError(JsonNode blocks) {
        if (!blocks.isArray()) return false;
        for (JsonNode block : blocks) {
            if (block.path("isError").asBoolean(false)) return true;
        }
        return false;
    }

    private String toolResultText(JsonNode blocks) {
        StringBuilder text = new StringBuilder();
        appendToolText(blocks, text);
        return text.toString();
    }

    private void appendToolText(JsonNode node, StringBuilder target) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode child : node) appendToolText(child, target);
            return;
        }
        if (!node.isObject()) return;
        if ("text".equals(node.path("type").asText()) && node.has("text")) {
            target.append(node.path("text").asText(""));
        }
        if (node.has("content")) appendToolText(node.path("content"), target);
    }

    private void emit(ExecutionState state, AgentRuntimeEventTypeEnum type,
                      String content, JsonNode providerPayload) {
        AgentRuntimeEvent event = new AgentRuntimeEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRunId(state.runId);
        event.setType(type);
        event.setContent(content);
        Map<String, Object> payload = providerPayload == null || providerPayload.isMissingNode()
                ? Map.of() : mapper.convertValue(providerPayload, new TypeReference<>() { });
        event.setPayload(new LinkedHashMap<>(payload));
        event.setOccurredAt(Date.from(clock.instant()));
        state.eventSink.emit(event);
    }

    private ProviderExecutionResult result(JsonNode result) {
        ProviderExecutionResult executionResult = new ProviderExecutionResult();
        executionResult.setSessionId(result.path("sessionId").asText());
        executionResult.setTurnId(result.path("turnId").asText());
        executionResult.setFinalResponse(result.path("finalResponse").asText(""));
        executionResult.setUsage(mapper.convertValue(result.path("usage"), new TypeReference<>() { }));
        return executionResult;
    }

    private Path materializeBridge(Path workspace) {
        Path target = workspace.resolve(".chat2db-dsh-runtime-bridge.mjs");
        try (InputStream input = DshRuntimeBridgeAdapter.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Bundled DSH Runtime Bridge is missing");
            Files.write(target, input.readAllBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to materialize DSH Runtime Bridge", exception);
        }
    }

    private Path writeMcpPatch(ProviderExecutionRequest request) {
        if (request.getMcpEndpoints().isEmpty()) return null;
        StringBuilder yaml = new StringBuilder();
        int index = 0;
        for (ProviderMcpEndpoint endpoint : request.getMcpEndpoints()) {
            String serverName = endpoint.getName();
            String tokenEnvironmentVariable = endpoint.getBearerTokenEnvironmentVariable();
            if (serverName == null || !serverName.matches("[A-Za-z0-9_-]{1,32}")
                    || tokenEnvironmentVariable == null
                    || !tokenEnvironmentVariable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("DSH MCP endpoint contains an invalid name");
            }
            yaml.append("- id: chat2db-mcp-").append(index++).append('\n')
                    .append("  name: '@deepseek-ai/dsh-mcp-client'\n")
                    .append("  config:\n")
                    .append("    serverName: ").append(jsonString(serverName)).append('\n')
                    .append("    transport: streamable-http\n")
                    .append("    url: ").append(jsonString(endpoint.getUrl().toString())).append('\n')
                    .append("    headers:\n")
                    .append("      Authorization: !!js '`Bearer ${process.env.")
                    .append(tokenEnvironmentVariable).append("}`'\n")
                    .append("    failOnStartupError: true\n");
        }
        Path patch = request.getWorkingDirectory().resolve(".chat2db-dsh-mcp.patch.yml");
        try {
            Files.writeString(patch, yaml.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return patch;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write DSH MCP patch", exception);
        }
    }

    private String jsonString(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode DSH MCP endpoint", exception);
        }
    }

    private Path resolveNode(AgentRuntimeProfile profile) {
        Path dsh = Path.of(profile.getExecutable());
        Path sibling = dsh.getParent() == null ? null : dsh.getParent().resolve("node");
        if (sibling != null && Files.isRegularFile(sibling) && Files.isExecutable(sibling)) return sibling;
        return Path.of("node");
    }

    private List<String> safeArguments(AgentRuntimeProfile profile) {
        List<String> result = new ArrayList<>();
        for (String argument : profile.getCustomArguments() == null ? List.<String>of()
                : profile.getCustomArguments()) {
            String value = StringUtils.trimToNull(argument);
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || RESERVED_ARGUMENTS.contains(value) || value.startsWith("--profile=")
                    || value.startsWith("--patch=") || value.startsWith("--host=")
                    || value.startsWith("--port=") || value.startsWith("--trusted-host=")) {
                throw new IllegalArgumentException("DSH custom argument may not override bridge transport settings");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private void validate(ProviderExecutionRequest request, ProviderEventSink eventSink,
                          ProviderLifecycleSink lifecycleSink) {
        if (request == null || eventSink == null || lifecycleSink == null
                || request.getRuntimeProfile() == null || request.getStartRequest() == null
                || request.getWorkingDirectory() == null || request.getEnvironment() == null
                || request.getApprovalHandler() == null || StringUtils.isBlank(request.getRunId())) {
            throw new IllegalArgumentException("DSH execution request is incomplete");
        }
        if (request.getRuntimeProfile().getProvider() != AgentRuntimeProviderEnum.DSH) {
            throw new IllegalArgumentException("DSH adapter requires a DSH Runtime Profile");
        }
        Path executable = Path.of(request.getRuntimeProfile().getExecutable());
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("DSH executable must be an absolute path");
        }
    }

    private Duration executionTimeout(ProviderExecutionRequest request) {
        int seconds = request.getRuntimeProfile().getTimeoutSeconds() == null
                ? DEFAULT_INACTIVITY_TIMEOUT_SECONDS : request.getRuntimeProfile().getTimeoutSeconds();
        return Duration.ofSeconds(seconds);
    }

    private ProviderExecutionException providerFailure(Throwable failure) {
        if (failure instanceof ProviderExecutionException providerFailure) return providerFailure;
        return new ProviderExecutionException(ProviderFailureKind.PROTOCOL_ERROR,
                StringUtils.defaultIfBlank(failure.getMessage(), "DSH Runtime Bridge failed"), failure);
    }

    private void drainStderr(ManagedProviderProcess process) {
        Thread thread = new Thread(() -> {
            try {
                process.stderr().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
                // Diagnostics are intentionally not copied because they can contain provider data.
            }
        }, "chat2db-dsh-bridge-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void terminate(ManagedProviderProcess process) {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(2))) process.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static final class ExecutionState {
        private final String runId;
        private final ProviderEventSink eventSink;
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicReference<String> turnId = new AtomicReference<>();
        private final Map<String, String> toolNames = new ConcurrentHashMap<>();
        private final CompletableFuture<Throwable> failure = new CompletableFuture<>();

        private ExecutionState(String runId, ProviderEventSink eventSink) {
            this.runId = runId;
            this.eventSink = eventSink;
        }
    }

    private record ToolEvent(String content, JsonNode payload) { }

    private static final class RunningExecution {
        private final ManagedProviderProcess process;
        private final ExecutionState state;
        private final AtomicReference<DshBridgeJsonRpcClient> client = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private RunningExecution(ManagedProviderProcess process, ExecutionState state) {
            this.process = process;
            this.state = state;
        }
    }
}
