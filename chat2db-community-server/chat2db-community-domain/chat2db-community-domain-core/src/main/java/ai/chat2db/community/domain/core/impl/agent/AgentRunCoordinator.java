package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentFailure;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentUsage;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentContextService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.core.converter.agent.AgentSkillConverter;
import ai.chat2db.community.domain.api.service.agent.IAiAgentPromptService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentQuestionService;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeAdapter;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionOpenRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionDeleteRequest;
import ai.chat2db.community.tools.util.AgentTrace;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AgentRunCoordinator {

    private final AgentRuntimeRegistry runtimeRegistry;
    private final AgentRuntimeHandleRegistry handleRegistry;
    private final AgentSessionStorage sessionStorage;
    private final AgentRunStorage runStorage;
    private final AgentEventStorage eventStorage;
    private final AgentModelResolver modelResolver;
    private final IAiAgentQuestionService questions;
    private final IAiAgentPromptService prompts;
    private final IAiAgentContextService contexts;
    private final IAiAgentSkillService skills;
    private final Supplier<String> idGenerator;
    private final Clock clock;
    private final Duration snapshotTimeout;

    private static final Duration DEFAULT_SNAPSHOT_TIMEOUT = Duration.ofSeconds(2);

    @Autowired
    public AgentRunCoordinator(
            AgentRuntimeRegistry runtimeRegistry,
            AgentRuntimeHandleRegistry handleRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunStorage runStorage,
            AgentEventStorage eventStorage,
            AgentModelResolver modelResolver,
            IAiAgentQuestionService questions, IAiAgentPromptService prompts, IAiAgentContextService contexts,
            IAiAgentSkillService skills) {
        this(runtimeRegistry, handleRegistry, sessionStorage, runStorage, eventStorage, modelResolver, questions, prompts, contexts, skills,
                () -> UUID.randomUUID().toString(), Clock.systemDefaultZone(), DEFAULT_SNAPSHOT_TIMEOUT);
    }

    AgentRunCoordinator(
            AgentRuntimeRegistry runtimeRegistry,
            AgentRuntimeHandleRegistry handleRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunStorage runStorage,
            AgentEventStorage eventStorage,
            AgentModelResolver modelResolver,
            IAiAgentQuestionService questions,
            IAiAgentPromptService prompts, IAiAgentContextService contexts, IAiAgentSkillService skills,
            Supplier<String> idGenerator,
            Clock clock) {
        this(runtimeRegistry, handleRegistry, sessionStorage, runStorage, eventStorage, modelResolver, questions,
                prompts, contexts, skills, idGenerator, clock, DEFAULT_SNAPSHOT_TIMEOUT);
    }

    AgentRunCoordinator(
            AgentRuntimeRegistry runtimeRegistry,
            AgentRuntimeHandleRegistry handleRegistry,
            AgentSessionStorage sessionStorage,
            AgentRunStorage runStorage,
            AgentEventStorage eventStorage,
            AgentModelResolver modelResolver,
            IAiAgentQuestionService questions,
            IAiAgentPromptService prompts, IAiAgentContextService contexts, IAiAgentSkillService skills,
            Supplier<String> idGenerator,
            Clock clock,
            Duration snapshotTimeout) {
        this.runtimeRegistry = Objects.requireNonNull(runtimeRegistry, "runtimeRegistry");
        this.handleRegistry = Objects.requireNonNull(handleRegistry, "handleRegistry");
        this.sessionStorage = Objects.requireNonNull(sessionStorage, "sessionStorage");
        this.runStorage = Objects.requireNonNull(runStorage, "runStorage");
        this.eventStorage = Objects.requireNonNull(eventStorage, "eventStorage");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.questions = Objects.requireNonNull(questions, "questions");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.snapshotTimeout = Objects.requireNonNull(snapshotTimeout, "snapshotTimeout");
        if (snapshotTimeout.isZero() || snapshotTimeout.isNegative()) {
            throw new IllegalArgumentException("snapshotTimeout must be positive");
        }
    }

    public synchronized CompletionStage<AgentRun> start(AgentRunStartCommand command) {
        AgentSession session = recoverSession(command.sessionId(), command.userId());
        if (session == null) throw new IllegalArgumentException("Agent session does not exist");
        AgentRun duplicate = runStorage.list(session.id(), command.userId()).stream()
                .filter(run -> run.idempotencyKey().equals(command.idempotencyKey()))
                .findFirst()
                .orElse(null);
        if (duplicate != null) {
            AgentTrace.record("run.replayed", session.id(), duplicate.id(), Map.of("status", duplicate.status()));
            return CompletableFuture.completedFuture(duplicate);
        }
        if (session.status() != AgentSessionStatus.READY && session.status() != AgentSessionStatus.FAILED
                && session.status() != AgentSessionStatus.UNKNOWN) {
            throw new IllegalStateException("Agent session is not ready: " + session.id());
        }
        var skillInput = skills.resolve(new AiAgentSkillResolveRequest(command.input().text()));
        var selectedSkills = skills.select(session.id()).stream().map(AgentSkillConverter::skill2runtime).toList();
        if (skillInput.skillName() != null && selectedSkills.stream().noneMatch(skill -> skill.name().equals(skillInput.skillName()))) {
            throw new IllegalArgumentException("Skill changed before the run could start: " + skillInput.skillName());
        }
        AgentModelSnapshot model = modelResolver.resolve(command.modelConfigId());
        AgentTrace.record("run.model.resolved", session.id(), null,
                Map.of("modelConfigId", model.modelConfigId(), "provider", model.provider(), "model", model.modelId()));
        var context = contexts.resolve(command.context());
        String renderedPrompt = prompts.userPrompt(skillInput.message(), context);
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
                                "modelConfigId", model.modelConfigId(),
                                "requestMessageId", run.requestMessageId(),
                                "context", context, "renderedPrompt", renderedPrompt, "promptTemplate", "agent-v1",
                                "requestedSkill", Objects.toString(skillInput.skillName(), ""), "skills", selectedSkills)),
                command.userId());
        updateSession(session, session.status(), AgentSessionStatus.RUNNING, sequence, command.modelConfigId());
        AgentTrace.record("run.accepted", session.id(), run.id(),
                Map.of("sequence", sequence, "idempotencyKey", command.idempotencyKey()));

        AgentRuntimeRunRequest runtimeRequest = new AgentRuntimeRunRequest(
                session.id(), runId, model, new AgentRuntimeInput(renderedPrompt, command.input().artifactIds(), skillInput.skillName()), command.idempotencyKey(), selectedSkills);
        try {
            IAgentRuntimeSessionHandle handle = handle(session, command, model, selectedSkills);
            return handle.startRun(runtimeRequest).handle((reference, error) -> {
                synchronized (this) {
                    if (error != null) {
                        return failStart(session.id(), runId, command.userId(), unwrap(error));
                    }
                    return bindExternalRun(session.id(), runId, command.userId(), reference);
                }
            });
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(
                    failStart(session.id(), runId, command.userId(), error));
        }
    }

    public synchronized CompletionStage<AgentRun> cancel(AgentRunCancelCommand command) {
        recoverSession(command.sessionId(), command.userId());
        AgentRun run = requireRun(command.sessionId(), command.runId(), command.userId());
        AgentTrace.record("run.cancel.requested", run.sessionId(), run.id(), Map.of("status", run.status()));
        if (run.status() != AgentRunStatus.RUNNING && run.status() != AgentRunStatus.ACCEPTED
                && run.status() != AgentRunStatus.WAITING_APPROVAL && run.status() != AgentRunStatus.SUSPENDED) {
            return CompletableFuture.completedFuture(run);
        }
        IAgentRuntimeSessionHandle handle = handleRegistry.get(command.sessionId());
        if (handle == null) {
            throw new IllegalStateException("Agent runtime session is not active: " + command.sessionId());
        }
        return handle.snapshot().thenCompose(snapshot -> {
                    String externalRunId = run.externalRunId() != null ? run.externalRunId() : snapshot.activeExternalRunId();
                    if (externalRunId == null) {
                        AgentRun durable = requireRun(command.sessionId(), command.runId(), command.userId());
                        if (durable.status() == AgentRunStatus.RUNNING) {
                            // The runtime finished this run already and is publishing its terminal event.
                            return CompletableFuture.<AgentRun>completedFuture(durable);
                        }
                        throw new IllegalStateException("Agent run has not started: " + run.id());
                    }
                    var cancellation = handle.cancel(new AgentRuntimeCancelRequest(
                            command.sessionId(), command.runId(), externalRunId));
                    // Close the pending question only after the abort was accepted, so a failed abort leaves it open.
                    return cancellation.thenApply(ignored -> {
                        questions.cancel(command.sessionId(), command.runId(), command.userId());
                        return requireRun(command.sessionId(), command.runId(), command.userId());
                    });
                })
                .thenApply(ignored -> requireRun(command.sessionId(), command.runId(), command.userId()));
    }

    /**
     * Deletes a session under the same monitor that opens runtimes, so a concurrent start can no longer leave a live
     * runtime handle behind for a session whose row is already gone.
     */
    public synchronized void deleteSession(String sessionId, Long userId) {
        AgentSession session = recoverSession(sessionId, userId);
        if (session == null) {
            throw new IllegalArgumentException("Agent session does not exist");
        }
        if (session.status() == AgentSessionStatus.RUNNING
                || session.status() == AgentSessionStatus.WAITING_APPROVAL
                || session.status() == AgentSessionStatus.SUSPENDED) {
            throw new IllegalStateException("Active agent session cannot be deleted");
        }
        handleRegistry.close(sessionId);
        runtimeRegistry.require(session.runtimeBinding().runtimeType()).deleteSession(
                new AgentRuntimeSessionDeleteRequest(session.id(), session.runtimeBinding()));
        sessionStorage.delete(sessionId, userId);
        skills.release(sessionId);
    }

    public synchronized AgentSession recoverSession(String sessionId, Long userId) {
        AgentSession session = sessionStorage.get(sessionId, userId);
        if (session == null) return null;
        IAgentRuntimeSessionHandle handle = handleRegistry.get(sessionId);
        if (handle != null) {
            AgentRuntimeHealth health = snapshotHealth(handle);
            // An unconfirmed snapshot (timeout or interrupted caller) must not tear down a healthy runtime.
            if (health == null) return session;
            if (health != AgentRuntimeHealth.STOPPED && health != AgentRuntimeHealth.FAILED) return session;
            handleRegistry.remove(sessionId, handle);
            session = requireSession(sessionId, userId);
        }
        if (session.status() == AgentSessionStatus.CLOSED) return session;
        List<AgentRun> runs = runStorage.list(sessionId, userId);
        AgentRun latest = runs.stream().max(Comparator.comparingLong(AgentRun::firstEventSequence)
                .thenComparing(AgentRun::id)).orElse(null);
        if (latest == null) return session;
        boolean orphaned = runs.stream().anyMatch(run -> !run.status().isTerminal());
        if (!orphaned && session.status() == sessionStatus(latest.status())
                && session.lastEventSequence() >= latest.lastEventSequence()) return session;
        // Opening a runtime and recording its accepted run use this same monitor, so a
        // missing handle here is an orphan, regardless of its age or stored session status.
        // Event, run and session snapshots are separate writes. Keep every durable event
        // sequence even when the process stopped before updating the other two snapshots.
        long sequence = session.lastEventSequence();
        while (true) {
            List<AgentEvent> page = eventStorage.list(sessionId, userId, sequence, 1000);
            if (page.isEmpty()) break;
            sequence = page.stream().mapToLong(AgentEvent::sequence).max().orElseThrow();
            if (page.size() < 1000) break;
        }
        if (sequence != session.lastEventSequence()) {
            updateSession(session, session.status(), session.status(), sequence);
        }
        for (AgentRun run : runs) {
            if (!run.status().isTerminal()) {
                recordRuntimeEvent(userId, new AgentRuntimeEvent(
                        nextId(), sessionId, run.id(), AgentEventType.RUN_OUTCOME_UNKNOWN,
                        Map.of("reason", "The Agent runtime stopped before this run completed."),
                        LocalDateTime.now(clock)));
                questions.cancel(sessionId, run.id(), userId);
            }
        }
        AgentSession recovered = requireSession(sessionId, userId);
        AgentSessionStatus status = sessionStatus(requireRun(sessionId, latest.id(), userId).status());
        if (recovered.status() != status) {
            updateSession(recovered, recovered.status(), status, recovered.lastEventSequence());
        }
        return requireSession(sessionId, userId);
    }

    private AgentRuntimeHealth snapshotHealth(IAgentRuntimeSessionHandle handle) {
        try {
            return handle.snapshot().toCompletableFuture()
                    .get(snapshotTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .health();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            AgentTrace.record("runtime.snapshot.interrupted",
                    Objects.toString(handle.session().externalSessionId(), "unknown"), null, Map.of());
            return null;
        } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException error) {
            AgentTrace.record("runtime.snapshot.failed",
                    Objects.toString(handle.session().externalSessionId(), "unknown"), null,
                    Map.of("reason", Objects.toString(error.getMessage(), error.getClass().getSimpleName())));
            return null;
        }
    }

    private AgentSessionStatus sessionStatus(AgentRunStatus status) {
        return switch (status) {
            case ACCEPTED, RUNNING -> AgentSessionStatus.RUNNING;
            case WAITING_APPROVAL -> AgentSessionStatus.WAITING_APPROVAL;
            case SUSPENDED -> AgentSessionStatus.SUSPENDED;
            case COMPLETED, CANCELLED -> AgentSessionStatus.READY;
            case FAILED -> AgentSessionStatus.FAILED;
            case UNKNOWN -> AgentSessionStatus.UNKNOWN;
        };
    }

    private IAgentRuntimeSessionHandle handle(
            AgentSession session, AgentRunStartCommand command, AgentModelSnapshot model,
            List<ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill> selectedSkills) {
        IAgentRuntimeSessionHandle existing = handleRegistry.get(session.id());
        if (existing != null) {
            return existing;
        }
        handleRegistry.closeIdle(id -> {
            AgentSession other = sessionStorage.get(id, command.userId());
            return other != null && (other.status() == AgentSessionStatus.READY
                    || other.status() == AgentSessionStatus.FAILED || other.status() == AgentSessionStatus.UNKNOWN);
        });
        IAgentRuntimeAdapter adapter = runtimeRegistry.require(session.runtimeBinding().runtimeType());
        IAgentRuntimeSessionHandle opened = adapter.openSession(
                new AgentRuntimeSessionOpenRequest(
                        session.id(), session.runtimeBinding().externalSessionId(),
                        session.definition().systemPrompt(), model,
                        selectedSkills),
                event -> recordRuntimeEvent(command.userId(), event));
        handleRegistry.register(session.id(), opened);
        AgentTrace.record("runtime.opened", session.id(), null, Map.of("runtime", session.runtimeBinding().runtimeType()));
        return opened;
    }

    void releaseSessionSkills(String sessionId) { skills.release(sessionId); }

    private synchronized void recordRuntimeEvent(Long userId, AgentRuntimeEvent runtimeEvent) {
        AgentSession session = sessionStorage.get(runtimeEvent.sessionId(), userId);
        if (session == null) {
            AgentTrace.record("event.ignored", runtimeEvent.sessionId(), runtimeEvent.runId(),
                    Map.of("reason", "session_missing", "type", runtimeEvent.type()));
            return;
        }
        AgentRun run = runStorage.get(session.id(), runtimeEvent.runId(), userId);
        if (run == null) {
            AgentTrace.record("event.ignored", session.id(), runtimeEvent.runId(),
                    Map.of("reason", "run_missing", "type", runtimeEvent.type()));
            return;
        }
        if (run.status().isTerminal()) return;
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
        updateSession(session, expected, target, sequence, session.definition().modelConfigId());
    }

    private void updateSession(AgentSession session, AgentSessionStatus expected,
            AgentSessionStatus target, long sequence, String modelConfigId) {
        AgentDefinition definition = session.definition();
        if (!definition.modelConfigId().equals(modelConfigId)) {
            definition = new AgentDefinition(definition.id(), definition.name(), definition.description(),
                    definition.systemPrompt(), definition.runtimeType(), modelConfigId, definition.revision() + 1);
        }
        AgentSession updated = new AgentSession(
                session.schemaVersion(), session.id(), session.userId(), definition,
                session.runtimeBinding(), target, currentTitle(session), sequence,
                session.gmtCreate(), LocalDateTime.now(clock));
        if (!sessionStorage.compareAndSet(updated, expected)) {
            throw new IllegalStateException("Agent session changed while applying a lifecycle event");
        }
    }

    /** Keeps a title renamed after the caller read this session instead of writing the stale one back. */
    private String currentTitle(AgentSession session) {
        AgentSession current = sessionStorage.get(session.id(), session.userId());
        return current == null ? session.title() : current.title();
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
