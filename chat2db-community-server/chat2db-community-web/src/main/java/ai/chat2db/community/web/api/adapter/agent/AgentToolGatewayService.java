package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.domain.api.service.agent.*;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.community.web.api.adapter.ai.AiToolAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentToolGatewayService implements AgentToolAccessService {
    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();
    private final Map<String, Access> tickets = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final AgentSessionStorage sessions;
    private final AgentRunStorage runs;
    private final IIdentityService identity;
    private final AgentApprovalService approvals;
    private final List<AgentFeatureService> features;
    private final List<AgentShellExecutor> shells;
    private final int port;

    public AgentToolGatewayService(AiToolAdapter adapter, AgentSessionStorage sessions, AgentRunStorage runs,
            IIdentityService identity, AgentApprovalService approvals, List<AgentFeatureService> features,
            List<AgentShellExecutor> shells, @Value("${server.port:10825}") int port) {
        for (ToolCallback callback : MethodToolCallbackProvider.builder().toolObjects(adapter).build().getToolCallbacks()) {
            tools.put(callback.getToolDefinition().name(), callback);
        }
        this.sessions = sessions;
        this.runs = runs;
        this.identity = identity;
        this.approvals = approvals;
        this.features = features;
        this.shells = shells;
        this.port = port;
    }

    @Override
    public AgentToolAccess issue(String sessionId, AgentRuntimeEventSink eventSink) {
        Long userId = identity.currentUserId();
        if (sessions.get(sessionId, userId) == null) throw new IllegalArgumentException("Agent session does not exist");
        Context context = Objects.requireNonNull(ContextUtils.queryThreadContext(), "Agent request context is unavailable");
        String ticket = UUID.randomUUID() + "-" + UUID.randomUUID();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(Instant.now()));
        tickets.put(ticket, new Access(sessionId, userId, context, eventSink));
        AgentTrace.record("tools.access.issued", sessionId, null, Map.of("userId", userId));
        try {
            List<AgentToolAccess.Tool> catalog = new ArrayList<>();
            for (ToolCallback callback : tools.values()) {
                var definition = callback.getToolDefinition();
                catalog.add(new AgentToolAccess.Tool(definition.name(), definition.description(),
                        json.readValue(definition.inputSchema(), new TypeReference<>() { })));
            }
            catalog.add(new AgentToolAccess.Tool("bash",
                    "Run a shell command in this conversation's isolated workspace after user approval.",
                    Map.of("type", "object", "properties", Map.of("command",
                            Map.of("type", "string", "description", "The shell command to run")),
                            "required", List.of("command"), "additionalProperties", false)));
            return new AgentToolAccess("http://127.0.0.1:" + port + "/api/v3/ai/agent-tools", ticket, List.copyOf(catalog));
        } catch (Exception error) {
            tickets.remove(ticket);
            throw new IllegalStateException("Cannot prepare Agent tools", error);
        }
    }

    @Override
    public void revoke(String ticket) {
        tickets.remove(ticket);
    }

    public List<String> activeTools(String ticket, String address) {
        requireAccess(ticket, address);
        List<String> names = new ArrayList<>(tools.keySet());
        if (bashEnabled()) names.add("bash");
        return names;
    }

    @Override
    public List<AgentToolState> listTools() {
        List<AgentToolState> catalog = new ArrayList<>();
        tools.values().forEach(callback -> catalog.add(new AgentToolState(
                callback.getToolDefinition().name(), callback.getToolDefinition().description(),
                AgentToolState.Category.DATABASE, AgentToolState.Status.ENABLED)));
        AgentFeatureState bash = features.stream().filter(feature -> feature.feature() == AgentFeature.BASH)
                .map(AgentFeatureService::check).findFirst().orElse(null);
        AgentToolState.Status status = bash == null || !bash.available() || shells.isEmpty()
                ? AgentToolState.Status.UNAVAILABLE
                : bash.enabled() ? AgentToolState.Status.ENABLED : AgentToolState.Status.DISABLED;
        catalog.add(new AgentToolState("bash", "Execute shell commands in the configured working directory.",
                AgentToolState.Category.BUILTIN, status));
        for (String name : List.of("read", "edit", "write", "grep", "find", "ls", "powershell")) {
            catalog.add(new AgentToolState(name, name, AgentToolState.Category.BUILTIN, AgentToolState.Status.UNAVAILABLE));
        }
        return List.copyOf(catalog);
    }

    public String execute(String ticket, String address, String toolCallId, String toolName,
            Map<String, Object> arguments) throws Exception {
        Access access = requireAccess(ticket, address);
        AgentRun run = runs.list(access.sessionId, access.userId).stream()
                .filter(candidate -> candidate.status() == AgentRunStatus.RUNNING
                        || candidate.status() == AgentRunStatus.ACCEPTED
                        || candidate.status() == AgentRunStatus.WAITING_APPROVAL)
                .findFirst().orElseThrow(() -> new IllegalStateException("Agent run is not active"));
        ToolCallback callback = tools.get(toolName);
        if (callback == null && !"bash".equals(toolName)) throw new IllegalArgumentException("Unknown Agent tool");
        String body = json.writeValueAsString(arguments);
        if (body.length() > 64 * 1024) throw new IllegalArgumentException("Tool arguments exceed the size limit");
        AgentShellCommand shellCommand = "bash".equals(toolName) ? prepareShell(access.sessionId, arguments) : null;
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((toolName + "\n" + body
                        + (shellCommand == null ? "" : "\n" + shellCommand.workingDirectory()))
                        .getBytes(StandardCharsets.UTF_8)));
        String executionId = run.id() + ":" + toolCallId;
        Execution execution = new Execution(digest, new CompletableFuture<>());
        Execution existing = access.executions.putIfAbsent(executionId, execution);
        if (existing != null) {
            if (!existing.digest.equals(digest)) throw new IllegalArgumentException("Tool call arguments have changed");
            AgentTrace.record("tool.replayed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName));
            return existing.result.join();
        }
        long started = System.nanoTime();
        AgentTrace.record("tool.requested", access.sessionId, run.id(),
                Map.of("toolCallId", toolCallId, "tool", toolName, "argumentsSha256", digest));
        try {
            if (access.executions.size() > 1000) {
                access.executions.remove(executionId, execution);
                throw new IllegalStateException("Session tool call limit reached");
            }
            String result;
            if (shellCommand != null) {
                AgentApproval approval = new AgentApproval(UUID.randomUUID().toString(), access.sessionId, run.id(),
                        toolCallId, AgentApprovalStatus.PENDING, AgentApprovalScope.ONCE, digest,
                        LocalDateTime.now().plusMinutes(2));
                boolean approved = approvals.awaitDecision(approval, access.userId, () ->
                        access.sink.emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), access.sessionId, run.id(),
                                AgentEventType.APPROVAL_REQUESTED,
                                Map.of("approvalId", approval.id(), "toolName", toolName,
                                        "command", shellCommand.command(), "workingDirectory", shellCommand.workingDirectory()),
                                LocalDateTime.now())), () -> isActive(access, run.id()) && bashEnabled());
                if (isActive(access, run.id())) {
                    access.sink.emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), access.sessionId, run.id(),
                            AgentEventType.APPROVAL_DECIDED, Map.of("approvalId", approval.id(), "approved", approved),
                            LocalDateTime.now()));
                }
                if (!approved || !bashEnabled()) throw new IllegalStateException("Shell command was not approved");
                AgentTrace.record("tool.executing", access.sessionId, run.id(),
                        Map.of("toolCallId", toolCallId, "tool", toolName));
                result = shells.get(0).execute(shellCommand,
                        () -> !isActive(access, run.id()) || !bashEnabled());
            } else {
                if (!isActive(access, run.id())) throw new IllegalStateException("Agent run has stopped");
                AgentTrace.record("tool.executing", access.sessionId, run.id(),
                        Map.of("toolCallId", toolCallId, "tool", toolName));
                Context previous = ContextUtils.queryThreadContext();
                try {
                    ContextUtils.setContext(access.context);
                    result = callback.call(body, new ToolContext(Map.of("requestContext", access.context)));
                } finally {
                    if (previous == null) ContextUtils.removeContext(); else ContextUtils.setContext(previous);
                }
            }
            if (result.length() > 64 * 1024) result = result.substring(0, 64 * 1024) + "\n[Output truncated]";
            execution.result.complete(result);
            AgentTrace.record("tool.completed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "outputCharacters", result.length(),
                            "durationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            return result;
        } catch (Exception error) {
            execution.result.completeExceptionally(error);
            AgentTrace.record("tool.failed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "errorType", error.getClass().getSimpleName()));
            throw error;
        }
    }

    private boolean bashEnabled() {
        return !shells.isEmpty() && features.stream().filter(feature -> feature.feature() == AgentFeature.BASH)
                .anyMatch(feature -> feature.check().enabled());
    }

    private AgentShellCommand prepareShell(String sessionId, Map<String, Object> arguments) {
        if (!bashEnabled()) throw new IllegalStateException("Bash is disabled or unavailable");
        if (!(arguments.get("command") instanceof String command) || command.isBlank()) {
            throw new IllegalArgumentException("Shell command must not be blank");
        }
        return shells.get(0).prepare(sessionId, command);
    }

    private boolean isActive(Access access, String runId) {
        AgentRun run = runs.get(access.sessionId, runId, access.userId);
        return access.expiresAt.isAfter(Instant.now()) && tickets.containsValue(access) && run != null
                && (run.status() == AgentRunStatus.ACCEPTED || run.status() == AgentRunStatus.RUNNING
                        || run.status() == AgentRunStatus.WAITING_APPROVAL);
    }

    private Access requireAccess(String ticket, String address) {
        if (!"127.0.0.1".equals(address) && !"::1".equals(address)
                && !"0:0:0:0:0:0:0:1".equals(address)) {
            throw new SecurityException("Agent tools only accept loopback requests");
        }
        Access access = tickets.get(ticket);
        if (access == null || !access.expiresAt.isAfter(Instant.now())) {
            throw new SecurityException("Agent tool ticket is invalid or expired");
        }
        return access;
    }

    private static final class Access {
        final String sessionId;
        final Long userId;
        final Context context;
        final AgentRuntimeEventSink sink;
        final Instant expiresAt = Instant.now().plusSeconds(7200);
        final Map<String, Execution> executions = new ConcurrentHashMap<>();
        Access(String sessionId, Long userId, Context context, AgentRuntimeEventSink sink) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.context = context;
            this.sink = sink;
        }
    }

    private record Execution(String digest, CompletableFuture<String> result) { }
}
