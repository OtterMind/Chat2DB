package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentToolCategory;
import ai.chat2db.community.domain.api.enums.agent.AgentToolStatus;
import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolState;
import ai.chat2db.community.domain.api.service.agent.*;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.tools.util.agent.AgentNativeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgentToolGatewayService implements AgentToolAccessService {
    private final AgentDatabaseToolRegistry tools;
    private final AgentQuestionTool questionTool;
    private final Map<String, Access> tickets = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final AgentSessionStorage sessions;
    private final AgentRunStorage runs;
    private final IIdentityService identity;
    private final AgentApprovalService approvals;
    private final List<IAiAgentWorkspaceService> workspaces;
    private final int port;

    public AgentToolGatewayService(AgentDatabaseToolRegistry tools, AgentQuestionTool questionTool, AgentSessionStorage sessions, AgentRunStorage runs,
            IIdentityService identity, AgentApprovalService approvals, List<IAiAgentWorkspaceService> workspaces, @Value("${server.port:10825}") int port) {
        this.tools = tools;
        this.questionTool = questionTool;
        this.sessions = sessions;
        this.runs = runs;
        this.identity = identity;
        this.approvals = approvals;
        this.workspaces = workspaces;
        this.port = port;
    }

    @Override
    public AgentToolAccess issue(String sessionId, IAgentRuntimeEventSink eventSink) {
        Long userId = identity.currentUserId();
        if (sessions.get(sessionId, userId) == null) throw new IllegalArgumentException("Agent session does not exist");
        Context context = Objects.requireNonNull(ContextUtils.queryThreadContext(), "Agent request context is unavailable");
        String ticket = UUID.randomUUID() + "-" + UUID.randomUUID();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(Instant.now()));
        tickets.put(ticket, new Access(sessionId, userId, context, eventSink));
        AgentTrace.record("tools.access.issued", sessionId, null, Map.of("userId", userId));
        var definitions = new ArrayList<>(tools.definitions()); definitions.add(questionTool.definition());
        return new AgentToolAccess("http://127.0.0.1:" + port + "/api/v3/ai/agent-tools", ticket, List.copyOf(definitions));
    }

    @Override
    public void revoke(String ticket) {
        tickets.remove(ticket);
    }

    @Override
    public List<String> activeTools(String ticket, String address) {
        requireAccess(ticket, address);
        List<String> names = new ArrayList<>(tools.names());
        names.add(AgentQuestionTool.NAME);
        AgentNativeTools.currentPlatform().stream().filter(this::nativeToolEnabled).forEach(names::add);
        return names;
    }

    @Override
    public List<AgentToolState> listTools() {
        List<AgentToolState> catalog = new ArrayList<>();
        tools.definitions().forEach(tool -> catalog.add(new AgentToolState(tool.name(), tool.description(),
                AgentToolCategory.DATABASE, AgentToolStatus.ENABLED)));
        catalog.add(new AgentToolState(AgentQuestionTool.NAME, questionTool.definition().description(),
                AgentToolCategory.INTERACTION, AgentToolStatus.ENABLED));
        for (String name : AgentNativeTools.currentPlatform()) {
            AgentToolStatus status = workspaces.isEmpty() ? AgentToolStatus.UNAVAILABLE
                    : nativeToolEnabled(name) ? AgentToolStatus.ENABLED : AgentToolStatus.DISABLED;
            catalog.add(new AgentToolState(name, name, AgentToolCategory.BUILTIN, status));
        }
        return List.copyOf(catalog);
    }

    @Override
    public IAgentToolResult<?> execute(String ticket, String address, String toolCallId, String toolName,
            Map<String, Object> arguments) throws Exception {
        Access access = requireAccess(ticket, address);
        AgentRun run = runs.list(access.sessionId, access.userId).stream()
                .filter(candidate -> candidate.status() == AgentRunStatus.RUNNING
                        || candidate.status() == AgentRunStatus.ACCEPTED
                        || candidate.status() == AgentRunStatus.WAITING_APPROVAL)
                .findFirst().orElseThrow(() -> new IllegalStateException("Agent run is not active"));
        if (!tools.names().contains(toolName) && !AgentQuestionTool.NAME.equals(toolName)) return tools.execute(toolName, arguments);
        String body = json.writeValueAsString(arguments);
        if (body.length() > 64 * 1024) throw new IllegalArgumentException("Tool arguments exceed the size limit");
        String digest = digest(toolName + "\n" + body);
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
            if (!isActive(access, run.id())) throw new IllegalStateException("Agent run has stopped");
            AgentTrace.record("tool.executing", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName));
            IAgentToolResult<?> result;
            Context previous = ContextUtils.queryThreadContext();
            try {
                ContextUtils.setContext(access.context);
                result = AgentQuestionTool.NAME.equals(toolName)
                        ? questionTool.execute(access.sessionId, run.id(), toolCallId, access.userId, arguments, access.sink, () -> isActive(access, run.id()))
                        : tools.execute(toolName, arguments);
            } finally {
                if (previous == null) ContextUtils.removeContext(); else ContextUtils.setContext(previous);
            }
            execution.result.complete(result);
            AgentTrace.record(result.ok() ? "tool.completed" : "tool.failed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "ok", result.ok(),
                            "durationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            return result;
        } catch (Exception error) {
            execution.result.completeExceptionally(error);
            AgentTrace.record("tool.failed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "errorType", error.getClass().getSimpleName()));
            throw error;
        }
    }

    @Override
    public AgentWorkspaceSettings prepareNative(String ticket, String address, String toolCallId,
            String toolName, Map<String, Object> arguments) throws Exception {
        Access access = requireAccess(ticket, address);
        if (!nativeToolEnabled(toolName)) {
            throw new IllegalArgumentException("Native tool is disabled or unavailable");
        }
        AgentRun run = runs.list(access.sessionId, access.userId).stream()
                .filter(candidate -> isActive(access, candidate.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent run is not active"));
        String body = json.writeValueAsString(arguments);
        if (body.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Tool arguments exceed the size limit");
        String argumentsDigest = digest(toolName + "\n" + body);
        String executionId = run.id() + ":" + toolCallId;
        NativePreparation preparation = new NativePreparation(argumentsDigest, new CompletableFuture<>());
        NativePreparation existing = access.nativePreparations.putIfAbsent(executionId, preparation);
        if (existing != null) {
            if (!existing.digest.equals(argumentsDigest)) throw new IllegalArgumentException("Tool call arguments have changed");
            return existing.result.join();
        }
        try {
            if (access.nativePreparations.size() > 1000) throw new IllegalStateException("Session tool call limit reached");
            String cwd = workspaces.get(0).resolveWorkingDirectory(access.sessionId);
            AgentTrace.record("tool.native.preparing", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "workingDirectory", cwd,
                            "argumentsSha256", argumentsDigest));
            if ("bash".equals(toolName) || "powershell".equals(toolName)) {
                if (!(arguments.get("command") instanceof String command) || command.isBlank()) {
                    throw new IllegalArgumentException("Shell command must not be blank");
                }
                AgentApproval approval = new AgentApproval(UUID.randomUUID().toString(), access.sessionId, run.id(),
                        toolCallId, AgentApprovalStatus.PENDING, AgentApprovalScope.ONCE,
                        digest(argumentsDigest + "\n" + cwd), LocalDateTime.now().plusMinutes(2));
                boolean approved = approvals.awaitDecision(approval, access.userId, () ->
                        access.sink.emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), access.sessionId, run.id(),
                                AgentEventType.APPROVAL_REQUESTED,
                                Map.of("approvalId", approval.id(), "toolName", toolName,
                                        "command", command, "workingDirectory", cwd), LocalDateTime.now())),
                        () -> isActive(access, run.id()) && nativeToolEnabled(toolName));
                if (isActive(access, run.id())) {
                    access.sink.emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), access.sessionId, run.id(),
                            AgentEventType.APPROVAL_DECIDED, Map.of("approvalId", approval.id(), "approved", approved),
                            LocalDateTime.now()));
                }
                if (!approved) throw new IllegalStateException("Shell command was not approved");
            }
            if (!isActive(access, run.id())) throw new IllegalStateException("Agent run has stopped");
            if (!nativeToolEnabled(toolName)) throw new IllegalStateException("Native tool has been disabled");
            AgentWorkspaceSettings result = new AgentWorkspaceSettings(cwd);
            preparation.result.complete(result);
            AgentTrace.record("tool.native.authorized", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "workingDirectory", cwd));
            return result;
        } catch (Exception error) {
            preparation.result.completeExceptionally(error);
            AgentTrace.record("tool.native.rejected", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "errorType", error.getClass().getSimpleName()));
            throw error;
        }
    }

    private String digest(String value) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean nativeToolEnabled(String toolName) {
        return !workspaces.isEmpty() && workspaces.get(0).isToolEnabled(toolName);
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
        final IAgentRuntimeEventSink sink;
        final Instant expiresAt = Instant.now().plusSeconds(7200);
        final Map<String, Execution> executions = new ConcurrentHashMap<>();
        final Map<String, NativePreparation> nativePreparations = new ConcurrentHashMap<>();
        Access(String sessionId, Long userId, Context context, IAgentRuntimeEventSink sink) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.context = context;
            this.sink = sink;
        }
    }

    private record NativePreparation(String digest, CompletableFuture<AgentWorkspaceSettings> result) { }

    private record Execution(String digest, CompletableFuture<IAgentToolResult<?>> result) { }
}
