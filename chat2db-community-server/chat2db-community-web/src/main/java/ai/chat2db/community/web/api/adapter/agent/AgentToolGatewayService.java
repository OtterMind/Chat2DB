package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalScope;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentToolCategory;
import ai.chat2db.community.domain.api.enums.agent.AgentToolStatus;
import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolState;
import ai.chat2db.community.domain.api.model.agent.tool.AgentNativePreparation;
import ai.chat2db.community.domain.api.service.agent.*;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.community.tools.util.ContextUtils;
import ai.chat2db.community.tools.util.agent.AgentNativeTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class AgentToolGatewayService implements AgentToolAccessService {
    private final AgentDatabaseToolRegistry tools;
    private final AgentQuestionTool questionTool;
    private final AgentChartTool chartTool;
    private final Map<String, Access> tickets = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final AgentSessionStorage sessions;
    private final AgentRunStorage runs;
    private final IIdentityService identity;
    private final AgentApprovalService approvals;
    private final List<IAiAgentWorkspaceService> workspaces;
    private final AgentGatewayAddress address;
    private final IAiAgentOutputService outputs;
    private final IAiAgentFileAccessService files;

    public AgentToolGatewayService(AgentDatabaseToolRegistry tools, AgentQuestionTool questionTool, AgentChartTool chartTool, AgentSessionStorage sessions, AgentRunStorage runs,
            IIdentityService identity, AgentApprovalService approvals, List<IAiAgentWorkspaceService> workspaces, AgentGatewayAddress address,
            IAiAgentOutputService outputs, IAiAgentFileAccessService files) {
        this.tools = tools;
        this.questionTool = questionTool;
        this.chartTool = chartTool;
        this.sessions = sessions;
        this.runs = runs;
        this.identity = identity;
        this.approvals = approvals;
        this.workspaces = workspaces;
        this.address = address;
        this.outputs = outputs;
        this.files = files;
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
        definitions.add(chartTool.definition());
        return new AgentToolAccess(address.baseUrl() + "/api/v3/ai/agent-tools", ticket, List.copyOf(definitions),
                files.userSkillDirectory());
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
        names.add(AgentChartTool.NAME);
        AgentNativeTools.currentPlatform().stream()
                .filter(name -> isFileReader(name) || skillFileTool(name) || nativeToolEnabled(name)).forEach(names::add);
        return names;
    }

    @Override
    public List<AgentToolState> listTools() {
        List<AgentToolState> catalog = new ArrayList<>();
        tools.definitions().forEach(tool -> catalog.add(new AgentToolState(tool.name(), tool.description(),
                AgentToolCategory.DATABASE, AgentToolStatus.ENABLED)));
        catalog.add(new AgentToolState(AgentQuestionTool.NAME, questionTool.definition().description(),
                AgentToolCategory.INTERACTION, AgentToolStatus.ENABLED));
        catalog.add(new AgentToolState(AgentChartTool.NAME, chartTool.definition().description(),
                AgentToolCategory.VISUALIZATION, AgentToolStatus.ENABLED));
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
        arguments = toolArguments(arguments);
        Access access = requireAccess(ticket, address);
        AgentRun run = activeRun(access);
        if (!tools.names().contains(toolName) && !AgentQuestionTool.NAME.equals(toolName)
                && !AgentChartTool.NAME.equals(toolName) && !isFileTool(toolName)) return tools.execute(toolName, arguments);
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
                AgentToolExecutionContext executionContext = new AgentToolExecutionContext(access.sessionId, run.id(),
                        toolCallId, access.userId, access.sink, () -> isActive(access, run.id()));
                result = switch (toolName) {
                    case AgentQuestionTool.NAME -> questionTool.execute(access.sessionId, run.id(), toolCallId,
                            access.userId, arguments, access.sink, executionContext.active());
                    case AgentChartTool.NAME -> chartTool.execute(arguments, executionContext);
                    case "read", "grep", "ls", "find" -> files.execute(executionContext, toolName, arguments);
                    default -> tools.execute(toolName, arguments, executionContext);
                };
                if (!isFileTool(toolName)) result = outputs.present(result, executionContext);
            } finally {
                if (previous == null) ContextUtils.removeContext(); else ContextUtils.setContext(previous);
            }
            execution.result.complete(result);
            AgentTrace.record(result.ok() ? "tool.completed" : "tool.failed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "ok", result.ok(),
                            "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            return result;
        } catch (Exception error) {
            execution.result.completeExceptionally(error);
            AgentTrace.record("tool.failed", access.sessionId, run.id(),
                    Map.of("toolCallId", toolCallId, "tool", toolName, "errorType", error.getClass().getSimpleName()));
            throw error;
        }
    }

    @Override
    public AgentNativePreparation prepareNative(String ticket, String address, String toolCallId,
            String toolName, Map<String, Object> arguments) throws Exception {
        arguments = toolArguments(arguments);
        Access access = requireAccess(ticket, address);
        if (!nativeToolEnabled(toolName) && !skillFileTool(toolName)) {
            throw new IllegalArgumentException("Native tool is disabled or unavailable");
        }
        AgentRun run = activeRun(access);
        String body = json.writeValueAsString(arguments);
        if (body.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Tool arguments exceed the size limit");
        String argumentsDigest = digest(toolName + "\n" + body);
        String executionId = run.id() + ":" + toolCallId;
        NativePreparation preparation = new NativePreparation(run.id(), toolCallId, toolName, UUID.randomUUID().toString(),
                argumentsDigest, new CompletableFuture<>());
        NativePreparation existing = access.nativePreparations.putIfAbsent(executionId, preparation);
        if (existing != null) {
            if (!existing.digest.equals(argumentsDigest)) throw new IllegalArgumentException("Tool call arguments have changed");
            return existing.result.join();
        }
        try {
            if (access.nativePreparations.size() > 1000) throw new IllegalStateException("Session tool call limit reached");
            String cwd = workspaces.get(0).resolveWorkingDirectory(access.sessionId);
            String allowedRoot = files.authorizedDirectory(access.sessionId, toolName, cwd, arguments);
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
            if (!allowedRoot.equals(files.authorizedDirectory(access.sessionId, toolName, cwd, arguments))) {
                throw new IllegalStateException("File authorization changed before execution");
            }
            AgentNativePreparation result = new AgentNativePreparation(cwd, preparation.id, allowedRoot);
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

    @Override
    public Object output(String ticket, String address, String toolCallId, String toolName,
            Map<String, Object> arguments) throws Exception {
        Access access = requireAccess(ticket, address);
        // Completion remains authorized after cancellation, but only for an already prepared invocation.
        String preparationId = requiredString(arguments, "preparationId");
        NativePreparation prepared = access.nativePreparations.values().stream()
                .filter(preparation -> preparation.id.equals(preparationId) && preparation.toolCallId.equals(toolCallId)
                        && preparation.toolName.equals(toolName)).findFirst()
                .orElseThrow(() -> new SecurityException("Native tool output has no authorized invocation"));
        if (!prepared.result.isDone() || prepared.result.isCompletedExceptionally()) {
            throw new SecurityException("Native tool execution was not authorized");
        }
        AgentToolExecutionContext context = new AgentToolExecutionContext(access.sessionId, prepared.runId, toolCallId,
                access.userId, access.sink, () -> isActive(access, prepared.runId));
        String action = requiredString(arguments, "action");
        Context previous = ContextUtils.queryThreadContext();
        try {
            ContextUtils.setContext(access.context);
            return switch (action) {
                case "begin" -> outputs.begin(context, requiredString(arguments, "format"));
                case "append" -> {
                    String content = requiredString(arguments, "content");
                    if (content.length() > 96 * 1024) throw new IllegalArgumentException("Output chunk is too large");
                    outputs.append(context, requiredString(arguments, "uploadId"), content);
                    yield Map.of("accepted", true);
                }
                case "finish" -> {
                    AgentOutputReference reference = outputs.finish(context, requiredString(arguments, "uploadId"),
                            Boolean.TRUE.equals(arguments.get("complete")), boundedString(arguments.get("warning")));
                    access.outputReferences.put(prepared.id, reference);
                    yield reference;
                }
                case "present" -> {
                    byte[] bytes = json.writeValueAsBytes(arguments.get("result"));
                    if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Native result exceeds the size limit");
                    NativeResult result = json.readValue(bytes, NativeResult.class);
                    if (result.output() != null && bytes.length > 32 * 1024) {
                        throw new IllegalArgumentException("Native output must contain only a bounded preview");
                    }
                    if (result.output() != null && result.output().artifactId() != null) {
                        AgentOutputReference published = access.outputReferences.get(prepared.id);
                        if (published == null || !Objects.equals(published.artifactId(), result.output().artifactId())) {
                            throw new SecurityException("Output does not belong to this native invocation");
                        }
                        AgentOutputReference reference = outputs.reference(access.sessionId, access.userId, result.output().artifactId());
                        yield new NativeResult(result.ok(), result.data(), reference, result.warning());
                    }
                    yield outputs.present(result, context);
                }
                default -> throw new IllegalArgumentException("Unknown output action");
            };
        } finally {
            if (previous == null) ContextUtils.removeContext(); else ContextUtils.setContext(previous);
        }
    }

    private static String requiredString(Map<String, Object> arguments, String key) {
        if (!(arguments.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String boundedString(Object value) {
        if (!(value instanceof String text)) return null;
        return text.substring(0, Math.min(1000, text.length()));
    }

    private static boolean isFileReader(String name) { return "read".equals(name) || "grep".equals(name); }

    private boolean skillFileTool(String name) {
        return List.of("read", "grep", "ls", "find", "write", "edit").contains(name)
                && files.userSkillDirectory() != null;
    }
    private static boolean isFileTool(String name) { return isFileReader(name) || "ls".equals(name) || "find".equals(name); }

    private static Map<String, Object> toolArguments(Map<String, Object> arguments) {
        if (!arguments.containsKey("description")) return arguments;
        var sanitized = new LinkedHashMap<>(arguments);
        sanitized.remove("description");
        return sanitized;
    }

    private String digest(String value) throws NoSuchAlgorithmException {
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

    private AgentRun activeRun(Access access) {
        return runs.list(access.sessionId, access.userId).stream()
                .filter(candidate -> isActive(access, candidate.id()))
                // Recovery can temporarily expose more than one non-terminal snapshot.
                // Route tool calls to the newest run so an older orphan cannot receive them.
                .max(Comparator.comparingLong(AgentRun::firstEventSequence).thenComparing(AgentRun::id))
                .orElseThrow(() -> new IllegalStateException("Agent run is not active"));
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
        final Map<String, AgentOutputReference> outputReferences = new ConcurrentHashMap<>();
        Access(String sessionId, Long userId, Context context, IAgentRuntimeEventSink sink) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.context = context;
            this.sink = sink;
        }
    }

    private record NativePreparation(String runId, String toolCallId, String toolName, String id, String digest,
            CompletableFuture<AgentNativePreparation> result) { }

    public record NativeResult(boolean ok, Object data, AgentOutputReference output, String warning) implements IAgentToolResult<Object> { }

    private record Execution(String digest, CompletableFuture<IAgentToolResult<?>> result) { }
}
