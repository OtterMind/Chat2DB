package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.util.AgentTrace;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentServiceImpl implements AgentService {

    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentSessionStorage sessionStorage;
    private final AgentRunCoordinator runCoordinator;
    private final AgentEventStorage eventStorage;
    private final AgentRuntimeHandleRegistry handleRegistry;
    private final Supplier<String> idGenerator;
    private final Clock clock;

    @Autowired
    public AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage,
            AgentRuntimeHandleRegistry handleRegistry) {
        this(runtimeRegistry, sessionStorage, runCoordinator, eventStorage, handleRegistry,
                () -> UUID.randomUUID().toString(), Clock.systemDefaultZone());
    }

    AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage,
            AgentRuntimeHandleRegistry handleRegistry,
            Supplier<String> idGenerator,
            Clock clock) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.runCoordinator = Objects.requireNonNull(runCoordinator, "runCoordinator");
        this.eventStorage = Objects.requireNonNull(eventStorage, "eventStorage");
        this.handleRegistry = Objects.requireNonNull(handleRegistry, "handleRegistry");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AgentSession createSession(AgentSessionCreateCommand command) {
        Objects.requireNonNull(command, "command");
        AgentDefinition definition = new AgentDefinition(
                "DEFAULT", "Chat2DB Agent", null, """
                你是 Chat2DB Agent，帮助用户完成数据库、文件和命令行任务。
                根据用户请求使用已启用的工具，基于实际结果简洁回答。
                用户明确限定范围时遵守该范围；范围未明确时，名称相似只是检索线索，不是范围限制。
                优先通过工具获取证据并逐步定位对象。局部检索无结果只对已检查的范围和条件有效，应继续探索其他合理候选，避免重复无效检索。
                找到足够证据能继续完成任务时直接执行；存在影响结果的歧义、缺少必要信息或需要用户选择下一步时，调用 askUserQuestion。
                提问时尽量给出基于实际发现的可选方向及简短理由，保留自由回答；让用户做选择，不要求用户替你定位答案。一次只问一个问题并等待真实回答。
                区分已验证的事实、推测和未检查的范围，不将局部结果表述为全局结论。
                需要审批时等待用户确认；工具不可用或执行失败时如实说明。
                """,
                command.runtimeType(), command.modelConfigId(), 1);
        IAgentRuntimeAdapter adapter = runtimeRegistry.require(definition.runtimeType());
        AgentRuntimeEnvironmentReport environment = adapter.inspectEnvironment(command.environment());
        AgentTrace.record("session.environment", null, null,
                java.util.Map.of("runtime", command.runtimeType(), "status", environment.status()));
        if (environment.runtimeType() != definition.runtimeType()) {
            throw new IllegalStateException("Agent runtime environment report type does not match its adapter");
        }
        if (!environment.isUsable()) {
            throw new AgentRuntimeUnavailableException(
                    definition.runtimeType().name(),
                    "environment status is " + environment.status());
        }
        AgentRuntimeDescriptor descriptor = adapter.descriptor();
        String sessionId = requireGeneratedId(idGenerator.get());
        LocalDateTime now = LocalDateTime.now(clock);
        AgentRuntimeBinding binding = new AgentRuntimeBinding(
                descriptor.type(),
                descriptor.version(),
                descriptor.protocolVersion(),
                sessionId,
                null,
                1);
        AgentSession session = new AgentSession(
                AgentSession.SCHEMA_VERSION,
                sessionId,
                command.userId(),
                definition,
                binding,
                AgentSessionStatus.READY,
                sessionTitle(command.message()),
                0,
                now,
                now);
        AgentSession created = sessionStorage.create(session);
        AgentTrace.record("session.created", session.id(), null,
                java.util.Map.of("runtime", definition.runtimeType(), "modelConfigId", definition.modelConfigId(),
                        "status", session.status(), "promptCharacters", definition.systemPrompt().length()));
        return created;
    }

    @Override
    public AgentSession getSession(String sessionId, Long userId) {
        return sessionStorage.get(sessionId, userId);
    }

    @Override
    public List<AgentSession> listSessions(Long userId) {
        return sessionStorage.listByUserId(userId);
    }

    @Override
    public CompletionStage<AgentRun> startRun(AgentRunStartCommand command) {
        return runCoordinator.start(command);
    }

    @Override
    public CompletionStage<AgentRun> cancelRun(AgentRunCancelCommand command) {
        return runCoordinator.cancel(command);
    }

    @Override
    public List<AgentEvent> listEvents(String sessionId, Long userId, long afterSequence, int limit) {
        if (sessionStorage.get(sessionId, userId) == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return eventStorage.list(sessionId, userId, afterSequence, limit);
    }

    @Override
    public AgentSession renameSession(String sessionId, Long userId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        return sessionStorage.rename(sessionId, userId, title.trim());
    }

    @Override
    public void deleteSession(String sessionId, Long userId) {
        AgentSession session = sessionStorage.get(sessionId, userId);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        if (session.status() == AgentSessionStatus.RUNNING
                || session.status() == AgentSessionStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("Active agent session cannot be deleted");
        }
        handleRegistry.close(sessionId);
        runtimeRegistry.require(session.runtimeBinding().runtimeType()).deleteSession(
                new ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionDeleteRequest(
                        session.id(), session.runtimeBinding()));
        sessionStorage.delete(sessionId, userId);
    }

    private String requireGeneratedId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Agent session id generator returned a blank value");
        }
        return id;
    }

    private String sessionTitle(String message) {
        String title = message.strip();
        return title.substring(0, title.offsetByCodePoints(0, Math.min(100, title.codePointCount(0, title.length()))));
    }
}
