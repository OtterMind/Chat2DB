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
import ai.chat2db.community.domain.api.service.agent.IAiAgentPromptService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.tools.util.AgentTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentServiceImpl implements AgentService {
    private static final Duration RUNTIME_STARTUP_GRACE = Duration.ofSeconds(30);

    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentSessionStorage sessionStorage;
    private final AgentRunCoordinator runCoordinator;
    private final AgentEventStorage eventStorage;
    private final AgentRuntimeHandleRegistry handleRegistry;
    private final Supplier<String> idGenerator;
    private final Clock clock;
    private final IAiAgentPromptService prompts;

    @Autowired
    public AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage,
            AgentRuntimeHandleRegistry handleRegistry, IAiAgentPromptService prompts) {
        this(runtimeRegistry, sessionStorage, runCoordinator, eventStorage, handleRegistry, prompts,
                () -> UUID.randomUUID().toString(), Clock.systemDefaultZone());
    }

    AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage,
            AgentRuntimeHandleRegistry handleRegistry,
            IAiAgentPromptService prompts,
            Supplier<String> idGenerator,
            Clock clock) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.runCoordinator = Objects.requireNonNull(runCoordinator, "runCoordinator");
        this.eventStorage = Objects.requireNonNull(eventStorage, "eventStorage");
        this.handleRegistry = Objects.requireNonNull(handleRegistry, "handleRegistry");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    @Override
    public AgentSession createSession(AgentSessionCreateCommand command) {
        Objects.requireNonNull(command, "command");
        AgentDefinition definition = new AgentDefinition(
                "DEFAULT", "Chat2DB Agent", null, prompts.systemPrompt(),
                command.runtimeType(), command.modelConfigId(), 1);
        IAgentRuntimeAdapter adapter = runtimeRegistry.require(definition.runtimeType());
        AgentRuntimeEnvironmentReport environment = adapter.inspectEnvironment(command.environment());
        AgentTrace.record("session.environment", null, null,
                Map.of("runtime", command.runtimeType(), "status", environment.status()));
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
                Map.of("runtime", definition.runtimeType(), "modelConfigId", definition.modelConfigId(),
                        "status", session.status(), "promptCharacters", definition.systemPrompt().length()));
        return created;
    }

    @Override
    public AgentSession getSession(String sessionId, Long userId) {
        return recoverStaleRuntime(sessionStorage.get(sessionId, userId), userId);
    }

    @Override
    public List<AgentSession> listSessions(Long userId) {
        return sessionStorage.listByUserId(userId).stream()
                .map(session -> recoverStaleRuntime(session, userId)).toList();
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

    private AgentSession recoverStaleRuntime(AgentSession session, Long userId) {
        if (session == null || !isRuntimeActive(session.status()) || handleRegistry.get(session.id()) != null) {
            return session;
        }
        List<AgentEvent> events = eventStorage.list(session.id(), userId, 0, 1000);
        Map<String, Boolean> activeRuns = new HashMap<>();
        String latestRun = null;
        for (AgentEvent event : events) {
            if (event.runId() == null) continue;
            if (event.type() == AgentEventType.RUN_ACCEPTED) {
                activeRuns.put(event.runId(), true);
                latestRun = event.runId();
            } else if (isTerminal(event.type())) {
                activeRuns.put(event.runId(), false);
            }
        }
        if (latestRun == null || !Boolean.TRUE.equals(activeRuns.get(latestRun))) {
            return session;
        }
        String activeRun = latestRun;
        AgentEvent accepted = events.stream()
                .filter(event -> activeRun.equals(event.runId()) && event.type() == AgentEventType.RUN_ACCEPTED)
                .findFirst().orElse(null);
        if (accepted != null && accepted.occurredAt().plus(RUNTIME_STARTUP_GRACE).isAfter(LocalDateTime.now(clock))) {
            return session;
        }
        long sequence = Math.max(session.lastEventSequence(), events.stream()
                .mapToLong(AgentEvent::sequence).max().orElse(0)) + 1;
        eventStorage.append(new AgentEvent(UUID.randomUUID().toString(), session.id(), latestRun, sequence,
                AgentEventType.RUN_OUTCOME_UNKNOWN,
                Map.of("reason", "The Agent runtime was restarted before this run completed."),
                LocalDateTime.now(clock)), userId);
        AgentSession recovered = new AgentSession(session.schemaVersion(), session.id(), session.userId(),
                session.definition(), session.runtimeBinding(), AgentSessionStatus.UNKNOWN, session.title(), sequence,
                session.gmtCreate(), LocalDateTime.now(clock));
        if (!sessionStorage.compareAndSet(recovered, session.status())) return sessionStorage.get(session.id(), userId);
        AgentTrace.record("runtime.stale.recovered", session.id(), latestRun,
                Map.of("previousStatus", session.status(), "sequence", sequence));
        return recovered;
    }

    private boolean isRuntimeActive(AgentSessionStatus status) {
        return status == AgentSessionStatus.RUNNING
                || status == AgentSessionStatus.WAITING_APPROVAL;
    }

    private boolean isTerminal(AgentEventType type) {
        return type == AgentEventType.RUN_COMPLETED || type == AgentEventType.RUN_FAILED
                || type == AgentEventType.RUN_CANCELLED || type == AgentEventType.RUN_SUSPENDED
                || type == AgentEventType.RUN_OUTCOME_UNKNOWN;
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
                new AgentRuntimeSessionDeleteRequest(
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
