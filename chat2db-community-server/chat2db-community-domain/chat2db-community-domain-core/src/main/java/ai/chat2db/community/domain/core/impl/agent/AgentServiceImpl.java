package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeDescriptor;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentReport;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@Service
public class AgentServiceImpl implements AgentService {

    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentSessionStorage sessionStorage;
    private final AgentRunCoordinator runCoordinator;
    private final AgentEventStorage eventStorage;
    private final Supplier<String> idGenerator;
    private final Clock clock;

    public AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage) {
        this(runtimeRegistry, sessionStorage, runCoordinator, eventStorage,
                () -> UUID.randomUUID().toString(), Clock.systemDefaultZone());
    }

    AgentServiceImpl(
            AgentRuntimeRegistry runtimeRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunCoordinator runCoordinator,
            AgentEventStorage eventStorage,
            Supplier<String> idGenerator,
            Clock clock) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.runCoordinator = Objects.requireNonNull(runCoordinator, "runCoordinator");
        this.eventStorage = Objects.requireNonNull(eventStorage, "eventStorage");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AgentSession createSession(AgentSessionCreateCommand command) {
        Objects.requireNonNull(command, "command");
        AgentDefinition definition = command.definition();
        AgentRuntimeAdapter adapter = runtimeRegistry.require(definition.runtimeType());
        AgentRuntimeEnvironmentReport environment = adapter.inspectEnvironment(command.environment());
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
                command.title().trim(),
                0,
                now,
                now);
        return sessionStorage.create(session);
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

    private String requireGeneratedId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Agent session id generator returned a blank value");
        }
        return id;
    }
}
