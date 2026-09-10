package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.AgentFailure;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentUsage;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeAdapter;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import org.springframework.stereotype.Component;
import ai.chat2db.community.tools.util.AgentTrace;
import org.springframework.beans.factory.annotation.Autowired;

import ai.chat2db.community.domain.api.service.agent.IAiAgentQuestionService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@Component
public class AgentRunCoordinator {

    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentRuntimeHandleRegistry handleRegistry;
    private final AgentSessionStorage sessionStorage;
    private final AgentRunStorage runStorage;
    private final AgentEventStorage eventStorage;
    private final AgentModelResolver modelResolver;
    private final IAiAgentQuestionService questions;
    private final Supplier<String> idGenerator;
    private final Clock clock;

    @Autowired
    public AgentRunCoordinator(
            AgentRuntimeRegistry runtimeRegistry,
            AgentRuntimeHandleRegistry handleRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunStorage runStorage,
            AgentEventStorage eventStorage,
            AgentModelResolver modelResolver,
            IAiAgentQuestionService questions) {
        this(runtimeRegistry, handleRegistry, sessionStorage, runStorage, eventStorage, modelResolver, questions,
                () -> UUID.randomUUID().toString(), Clock.systemDefaultZone());
    }

    AgentRunCoordinator(
            AgentRuntimeRegistry runtimeRegistry,
            AgentRuntimeHandleRegistry handleRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunStorage runStorage,
            AgentEventStorage eventStorage,
            AgentModelResolver modelResolver,
            IAiAgentQuestionService questions,
            Supplier<String> idGenerator,
            Clock clock) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.handleRegistry = Objects.requireNonNull(handleRegistry, "handleRegistry");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.runStorage = Objects.requireNonNull(runStorage, "runStorage");
        this.eventStorage = Objects.requireNonNull(eventStorage, "eventStorage");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.questions = Objects.requireNonNull(questions, "questions");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized CompletionStage<AgentRun> start(AgentRunStartCommand command) {
        AgentSession session = requireSession(command.sessionId(), command.userId());
        AgentRun duplicate = runStorage.list(session.id(), command.userId()).stream()
                .filter(run -> run.idempotencyKey().equals(command.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (duplicate != null) {
            AgentTrace.record("run.replayed", session.id(), duplicate.id(), Map.of("status", duplicate.status()));
            return java.util.concurrent.CompletableFuture.completedFuture(duplicate);
        }
        if (session.status() != AgentSessionStatus.READY) {
            throw new IllegalStateException("Agent session is not ready: " + session.id());
        }
        if (!session.definition().modelConfigId().equals(command.modelConfigId())) {
            throw new IllegalArgumentException("Agent session model cannot be changed");
        }
        AgentModelSnapshot model = modelResolver.resolve(command.modelConfigId());
        AgentTrace.record("run.model.resolved", session.id(), null,
                Map.of("modelConfigId", model.modelConfigId(), "provider", model.provider(), "model", model.modelId()));
        long sequence = session.lastEventSequence() + 1;
        String runId = nextId();
        AgentRun run = new AgentRun(
                runId, session.id(), AgentRunStatus.ACCEPTED, model, nextId(),
                command.idempotencyKey(), null, sequence, sequence, null, null);
        runStorage.create(run, command.userId());
        eventStorage.append(productEvent(
                        session.id(), runId, sequence, AgentEventType.RUN_ACCEPTED,
                        Map.of(
                                "text", Objects.toString(command.input().text(), ""),
                                "artifactIds", command.input().artifactIds(),
                                "requestMessageId", run.requestMessageId())),
                command.userId());
        updateSession(session, AgentSessionStatus.READY, AgentSessionStatus.RUNNING, sequence);
        AgentTrace.record("run.accepted", session.id(), run.id(),
                Map.of("sequence", sequence, "idempotencyKey", command.idempotencyKey()));

        AgentRuntimeRunRequest runtimeRequest = new AgentRuntimeRunRequest(
                session.id(), runId, model, command.input(), command.idempotencyKey());
        try {
            AgentRuntimeSessionHandle handle = handle(session, command, model);
            return handle.startRun(runtimeRequest).handle((reference, error) -> {
                synchronized (this) {
                    if (error != null) {
                        return failStart(session.id(), runId, command.userId(), unwrap(error));
                    }
                    return bindExternalRun(session.id(), runId, command.userId(), reference);
                }
            });
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    failStart(session.id(), runId, command.userId(), error));
        }
    }

    public synchronized CompletionStage<AgentRun> cancel(AgentRunCancelCommand command) {
        AgentRun run = requireRun(command.sessionId(), command.runId(), command.userId());
        AgentTrace.record("run.cancel.requested", run.sessionId(), run.id(), Map.of("status", run.status()));
        if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.ACCEPTED
                && run.status() != AgentRunStatus.WAITING_APPROVAL) {
            return java.util.concurrent.CompletableFuture.completedFuture(run);
        }
        AgentRuntimeSessionHandle handle = handleRegistry.get(command.sessionId());
        if (handle == null) {
            throw new IllegalStateException("Agent runtime session is not active: " + command.sessionId());
        }
        return handle.snapshot().thenCompose(snapshot -> {
                    String externalRunId = run.externalRunId() != null ? run.externalRunId() : snapshot.activeExternalRunId();
                    if (externalRunId == null) {
                        throw new IllegalStateException("Agent run has not started: " + run.id());
                    }
                    var cancellation = handle.cancel(new AgentRuntimeCancelRequest(
                            command.sessionId(), command.runId(), externalRunId));
                    questions.cancel(command.sessionId(), command.runId(), command.userId());
                    return cancellation;
                })
                .thenApply(ignored -> requireRun(command.sessionId(), command.runId(), command.userId()));
    }

    private AgentRuntimeSessionHandle handle(
            AgentSession session, AgentRunStartCommand command, AgentModelSnapshot model) {
        AgentRuntimeSessionHandle existing = handleRegistry.get(session.id());
        if (existing != null) {
            return existing;
        }
        handleRegistry.closeIdle(id -> {
            AgentSession other = sessionStorage.get(id, command.userId());
            return other != null && (other.status() == AgentSessionStatus.READY
                    || other.status() == AgentSessionStatus.FAILED || other.status() == AgentSessionStatus.UNKNOWN);
        });
        AgentRuntimeAdapter adapter = runtimeRegistry.require(session.runtimeBinding().runtimeType());
        AgentRuntimeSessionHandle opened = adapter.openSession(
                new AgentRuntimeSessionOpenRequest(
                        session.id(), session.runtimeBinding().externalSessionId(),
                        session.definition().systemPrompt(), model),
                event -> recordRuntimeEvent(command.userId(), event));
        handleRegistry.register(session.id(), opened);
        AgentTrace.record("runtime.opened", session.id(), null, Map.of("runtime", session.runtimeBinding().runtimeType()));
        return opened;
    }

    private synchronized void recordRuntimeEvent(Long userId, AgentRuntimeEvent runtimeEvent) {
        AgentSession session = requireSession(runtimeEvent.sessionId(), userId);
        AgentRun run = requireRun(session.id(), runtimeEvent.runId(), userId);
        long sequence = session.lastEventSequence() + 1;
        eventStorage.append(productEvent(
                session.id(), run.id(), sequence, runtimeEvent.type(), runtimeEvent.payload()), userId);
        AgentRunStatus runStatus = runStatus(runtimeEvent.type(), run.status());
        AgentFailure failure = runtimeEvent.type() == AgentEventType.RUN_FAILED
                ? new AgentFailure("RUNTIME_FAILED",
                        Objects.toString(runtimeEvent.payload().get("error"), "Runtime reported a failed run"), false)
                : run.failure();
        AgentUsage usage = runtimeEvent.type() == AgentEventType.USAGE_UPDATED
                ? accumulateUsage(run, runtimeEvent.payload()) : run.usage();
        AgentRun updatedRun = new AgentRun(
                run.id(), run.sessionId(), runStatus, run.model(), run.requestMessageId(), run.idempotencyKey(),
                run.externalRunId(), run.firstEventSequence(), sequence, usage, failure);
        if (!runStorage.compareAndSet(updatedRun, run.status(), userId)) {
            throw new IllegalStateException("Agent run changed while recording a runtime event");
        }
        updateSession(session, session.status(), sessionStatus(runtimeEvent.type(), session.status()), sequence);
        AgentTrace.record("event.persisted", session.id(), run.id(),
                Map.of("sequence", sequence, "type", runtimeEvent.type(), "runStatus", runStatus,
                        "sessionStatus", sessionStatus(runtimeEvent.type(), session.status())));
    }

    private AgentRun bindExternalRun(
            String sessionId,
            String runId,
            Long userId,
            AgentRuntimeRunRef reference) {
        AgentRun run = requireRun(sessionId, runId, userId);
        AgentRunStatus targetStatus = run.status() == AgentRunStatus.ACCEPTED
                ? AgentRunStatus.RUNNING : run.status();
        AgentRun updated = new AgentRun(
                run.id(), run.sessionId(), targetStatus, run.model(), run.requestMessageId(),
                run.idempotencyKey(), reference.externalRunId(), run.firstEventSequence(),
                run.lastEventSequence(), run.usage(), run.failure());
        if (!runStorage.compareAndSet(updated, run.status(), userId)) {
            throw new IllegalStateException("Agent run was not accepted when the runtime acknowledged it");
        }
        AgentTrace.record("run.acknowledged", sessionId, runId,
                Map.of("externalRunId", reference.externalRunId(), "status", updated.status()));
        return updated;
    }

    private AgentUsage accumulateUsage(AgentRun run, Map<String, Object> payload) {
        Object message = payload.get("message");
        Object rawUsage = message instanceof Map<?, ?> value ? value.get("usage") : payload.get("usage");
        if (!(rawUsage instanceof Map<?, ?> values)) return run.usage();
        AgentUsage previous = run.usage() == null ? new AgentUsage(0, 0, 0, 0, 0, null) : run.usage();
        long input = tokens(values, "input") + tokens(values, "cacheWrite");
        long cached = tokens(values, "cacheRead");
        long output = tokens(values, "output");
        return new AgentUsage(previous.inputTokens() + input, previous.cachedInputTokens() + cached,
                previous.outputTokens() + output, previous.reasoningTokens(),
                previous.totalTokens() + input + cached + output,
                run.model().contextWindow() == null ? null : run.model().contextWindow().longValue());
    }

    private long tokens(Map<?, ?> values, String key) {
        return values.get(key) instanceof Number count ? count.longValue() : 0;
    }

    private AgentRun failStart(String sessionId, String runId, Long userId, Throwable error) {
        AgentRun run = requireRun(sessionId, runId, userId);
        if (run.status() != AgentRunStatus.ACCEPTED && run.status() != AgentRunStatus.RUNNING) {
            return run;
        }
        recordRuntimeEvent(userId, new AgentRuntimeEvent(
                nextId(), sessionId, runId, AgentEventType.RUN_FAILED,
                Map.of("error", Objects.toString(error.getMessage(), error.getClass().getSimpleName())),
                LocalDateTime.now(clock)));
        return requireRun(sessionId, runId, userId);
    }

    private void updateSession(
            AgentSession session,
            AgentSessionStatus expected,
            AgentSessionStatus target,
            long sequence) {
        AgentSession updated = new AgentSession(
                session.schemaVersion(), session.id(), session.userId(), session.definition(),
                session.runtimeBinding(), target, session.title(), sequence,
                session.gmtCreate(), LocalDateTime.now(clock));
        if (!sessionStorage.compareAndSet(updated, expected)) {
            throw new IllegalStateException("Agent session changed while applying a lifecycle event");
        }
    }

    private AgentSession requireSession(String sessionId, Long userId) {
        AgentSession session = sessionStorage.get(sessionId, userId);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        return session;
    }

    private AgentRun requireRun(String sessionId, String runId, Long userId) {
        AgentRun run = runStorage.get(sessionId, runId, userId);
        if (run == null) {
            throw new IllegalArgumentException("Agent run does not exist");
        }
        return run;
    }

    private AgentEvent productEvent(
            String sessionId,
            String runId,
            long sequence,
            AgentEventType type,
            Map<String, Object> payload) {
        return new AgentEvent(nextId(), sessionId, runId, sequence, type, payload, LocalDateTime.now(clock));
    }

    private AgentRunStatus runStatus(AgentEventType type, AgentRunStatus current) {
        return switch (type) {
            case APPROVAL_REQUESTED -> AgentRunStatus.WAITING_APPROVAL;
            case APPROVAL_DECIDED -> current == AgentRunStatus.WAITING_APPROVAL ? AgentRunStatus.RUNNING : current;
            case RUN_COMPLETED -> AgentRunStatus.COMPLETED;
            case RUN_FAILED -> AgentRunStatus.FAILED;
            case RUN_CANCELLED -> AgentRunStatus.CANCELLED;
            case RUN_SUSPENDED -> AgentRunStatus.SUSPENDED;
            case RUN_OUTCOME_UNKNOWN -> AgentRunStatus.UNKNOWN;
            default -> current;
        };
    }

    private AgentSessionStatus sessionStatus(AgentEventType type, AgentSessionStatus current) {
        return switch (type) {
            case APPROVAL_REQUESTED -> AgentSessionStatus.WAITING_APPROVAL;
            case APPROVAL_DECIDED -> current == AgentSessionStatus.WAITING_APPROVAL ? AgentSessionStatus.RUNNING : current;
            case RUN_COMPLETED, RUN_CANCELLED -> AgentSessionStatus.READY;
            case RUN_FAILED -> AgentSessionStatus.FAILED;
            case RUN_SUSPENDED -> AgentSessionStatus.SUSPENDED;
            case RUN_OUTCOME_UNKNOWN -> AgentSessionStatus.UNKNOWN;
            default -> current;
        };
    }

    private Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private String nextId() {
        String id = idGenerator.get();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Agent id generator returned a blank value");
        }
        return id;
    }
}
