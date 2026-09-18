package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeBinding;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeInput;
import java.time.Clock;
import java.time.Duration;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunCoordinatorTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-one";
    private final MemoryStorage storage = new MemoryStorage();
    private final FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
    private final AgentRuntimeHandleRegistry handles = new AgentRuntimeHandleRegistry();
    private AgentRunCoordinator coordinator;
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        storage.create(session());
        AtomicInteger ids = new AtomicInteger();
        AgentModelResolver resolver = new AgentModelResolver(null) {
            @Override public AgentModelSnapshot resolve(String modelConfigId) {
                if ("missing-model".equals(modelConfigId)) throw new IllegalArgumentException("Model unavailable");
                return new AgentModelSnapshot(modelConfigId, 1, "openai", modelConfigId, 128000, 4096);
            }
        };
        coordinator = new AgentRunCoordinator(
                new AgentRuntimeRegistry(List.of(adapter)), handles, storage, storage, storage, resolver, new AiAgentQuestionServiceImpl(), new AiAgentPromptServiceImpl(), new AiAgentContextServiceImpl(null),
                new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), temporaryDirectory),
                () -> "generated-" + ids.incrementAndGet(),
                Clock.fixed(Instant.parse("2026-09-08T16:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void startsIdempotentlyAndCancelsOneRun() {
        AgentRunStartCommand start = new AgentRunStartCommand(
                USER_ID, SESSION_ID, "model", new AgentRuntimeInput("hello", List.of()), "request-one");

        AgentRun running = coordinator.start(start).toCompletableFuture().join();
        AgentRun duplicate = coordinator.start(start).toCompletableFuture().join();

        assertEquals(AgentRunStatus.RUNNING, running.status());
        assertEquals(running, duplicate);
        assertEquals(1, adapter.openSessionCount());
        assertEquals(1, handles.size());
        assertEquals(List.of(AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_STARTED),
                storage.events.stream().map(AgentEvent::type).toList());
        assertEquals(List.of(1L, 2L), storage.events.stream().map(AgentEvent::sequence).toList());
        assertEquals("hello", storage.events.get(0).payload().get("text"));
        assertEquals(storage.events.get(0).payload().get("renderedPrompt"), adapter.lastRequest().input().text());
        assertEquals(true, adapter.lastRequest().input().text().contains("<chat2db_context>"));
        assertEquals(running.requestMessageId(), storage.events.get(0).payload().get("requestMessageId"));

        AgentRun cancelled = coordinator.cancel(
                new AgentRunCancelCommand(USER_ID, SESSION_ID, running.id()))
                .toCompletableFuture()
                .join();

        assertEquals(AgentRunStatus.CANCELLED, cancelled.status());
        assertEquals(AgentSessionStatus.READY, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(3, storage.get(SESSION_ID, USER_ID).lastEventSequence());
        assertEquals(1, handles.size());
    }

    @Test
    void keepsOriginalInputAndPassesSkillSeparatelyFromRenderedContext() {
        String text = "/skill:chart 画一下收入";
        coordinator.start(new AgentRunStartCommand(USER_ID, SESSION_ID, "model",
                new AgentRuntimeInput(text, List.of()), "skill-request")).toCompletableFuture().join();
        assertEquals(text, storage.events.get(0).payload().get("text"));
        assertEquals("chart", storage.events.get(0).payload().get("requestedSkill"));
        assertEquals("chart", adapter.lastRequest().input().skillName());
        String rendered = adapter.lastRequest().input().text();
        assertEquals(true, rendered.contains("<chat2db_context>"));
        assertEquals(true, rendered.contains("画一下收入"));
        assertEquals(false, rendered.contains("/skill:chart"));
    }

    @Test
    void rejectsUnknownSkillBeforeWritingRunOrEvents() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(new AgentRunStartCommand(
                USER_ID, SESSION_ID, "model", new AgentRuntimeInput("/skill:missing hello", List.of()), "unknown")));
        assertEquals(0, storage.events.size());
        assertEquals(0, storage.list(SESSION_ID, USER_ID).size());
        assertEquals(0, adapter.openSessionCount());
    }

    @Test
    void recordsOpenFailureAndLeavesSessionFailed() {
        adapter.failOpenWith(new IllegalStateException("runtime unavailable"));

        AgentRun failed = coordinator.start(startCommand("request-open-failure"))
                .toCompletableFuture()
                .join();

        assertEquals(AgentRunStatus.FAILED, failed.status());
        assertEquals("RUNTIME_FAILED", failed.failure().code());
        assertEquals(AgentSessionStatus.FAILED, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(List.of(AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_FAILED), eventTypes());
        assertEquals(0, handles.size());
    }

    @Test
    void recordsAsynchronousStartFailureAndKeepsHandleForInspection() {
        adapter.failStartWith(new IllegalStateException("start rejected"));

        AgentRun failed = coordinator.start(startCommand("request-start-failure"))
                .toCompletableFuture()
                .join();

        assertEquals(AgentRunStatus.FAILED, failed.status());
        assertEquals(AgentSessionStatus.FAILED, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(List.of(
                AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_STARTED, AgentEventType.RUN_FAILED),
                eventTypes());
        assertEquals(1, handles.size());
    }

    @Test
    void preservesCompletionEmittedBeforeRuntimeAcknowledgement() {
        adapter.emitTerminalEventOnStart(AgentEventType.RUN_COMPLETED);

        AgentRun completed = coordinator.start(startCommand("request-completed"))
                .toCompletableFuture()
                .join();

        assertEquals(AgentRunStatus.COMPLETED, completed.status());
        assertEquals("external-" + completed.id(), completed.externalRunId());
        assertEquals(AgentSessionStatus.READY, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(List.of(
                AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_STARTED, AgentEventType.RUN_COMPLETED),
                eventTypes());
    }

    @Test
    void doesNotOverwriteTerminalEventWhenAcknowledgementFails() {
        adapter.emitTerminalEventOnStart(AgentEventType.RUN_COMPLETED);
        adapter.failStartWith(new IllegalStateException("late acknowledgement failure"));

        AgentRun completed = coordinator.start(startCommand("request-late-failure"))
                .toCompletableFuture()
                .join();

        assertEquals(AgentRunStatus.COMPLETED, completed.status());
        assertEquals(AgentSessionStatus.READY, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(List.of(
                AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_STARTED, AgentEventType.RUN_COMPLETED),
                eventTypes());
    }

    @Test
    void keepsATitleRenamedWhileARunIsStreaming() {
        AgentRun running = coordinator.start(startCommand("request-rename")).toCompletableFuture().join();

        storage.rename(SESSION_ID, USER_ID, "Renamed while running");
        adapter.emitLate(running.id(), AgentEventType.ASSISTANT_TEXT_DELTA);

        assertEquals("Renamed while running", storage.get(SESSION_ID, USER_ID).title());
    }

    @Test
    void mergesStreamedTextIntoFewEventsWithoutUsageCopies() {
        AgentRun running = coordinator.start(startCommand("request-deltas")).toCompletableFuture().join();

        StringBuilder expected = new StringBuilder();
        for (int index = 0; index < 60; index++) {
            String chunk = "chunk-" + index + ";";
            expected.append(chunk);
            adapter.emitTextDelta(running.id(), chunk);
        }
        adapter.emitLate(running.id(), AgentEventType.RUN_COMPLETED);

        List<AgentEvent> deltas = storage.events.stream()
                .filter(event -> event.type() == AgentEventType.ASSISTANT_TEXT_DELTA).toList();
        assertTrue(deltas.size() <= 3, "streamed text must be merged into few events, was " + deltas.size());
        String merged = deltas.stream()
                .map(event -> String.valueOf(((Map<?, ?>) event.payload().get("assistantMessageEvent")).get("delta")))
                .collect(java.util.stream.Collectors.joining());
        assertEquals(expected.toString(), merged);
        assertFalse(deltas.get(0).payload().containsKey("usage"), "usage must not be copied into every delta");
    }

    @Test
    void ignoresLateRuntimeEventsAfterTheRunIsTerminal() {
        adapter.emitTerminalEventOnStart(AgentEventType.RUN_COMPLETED);
        AgentRun completed = coordinator.start(startCommand("request-late-event"))
                .toCompletableFuture().join();
        int eventCount = storage.events.size();

        adapter.emitLate(completed.id(), AgentEventType.ASSISTANT_TEXT_DELTA);

        assertEquals(AgentRunStatus.COMPLETED,
                storage.get(SESSION_ID, completed.id(), USER_ID).status());
        assertEquals(eventCount, storage.events.size());
        assertEquals(AgentSessionStatus.READY, storage.get(SESSION_ID, USER_ID).status());
    }

    @Test
    void keepsAHealthyRuntimeWhenTheSnapshotCannotBeConfirmedInTime() {
        adapter.hangSnapshots();
        AgentRun started = coordinator.start(startCommand("request-stuck-snapshot"))
                .toCompletableFuture().join();
        AgentRunCoordinator bounded = new AgentRunCoordinator(
                new AgentRuntimeRegistry(List.of(adapter)), handles, storage, storage, storage,
                new AgentModelResolver(null), new AiAgentQuestionServiceImpl(), new AiAgentPromptServiceImpl(),
                new AiAgentContextServiceImpl(null),
                new AiAgentSkillServiceImpl(new ClassPathResource("skills/catalog.json"), temporaryDirectory),
                () -> "bounded", Clock.fixed(Instant.parse("2026-09-08T16:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(5));

        long begin = System.nanoTime();
        AgentSession recovered = bounded.recoverSession(SESSION_ID, USER_ID);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        // An unconfirmed snapshot must not be treated as a dead runtime: no run is retired and no process killed.
        assertEquals(AgentSessionStatus.RUNNING, recovered.status());
        assertEquals(AgentRunStatus.RUNNING, storage.get(SESSION_ID, started.id(), USER_ID).status());
        assertNotNull(handles.get(SESSION_ID), "the runtime handle stays available for a later confirmation");
        assertTrue(elapsedMillis < 1000, "stuck runtime snapshot must not block lifecycle operations");
    }

    @Test
    void rejectsUnknownAndForeignSessionsWithoutWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.start(new AgentRunStartCommand(
                        2L, SESSION_ID, "model", new AgentRuntimeInput("hello", List.of()), "foreign")));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.start(new AgentRunStartCommand(
                        USER_ID, "missing", "model", new AgentRuntimeInput("hello", List.of()), "missing")));

        assertEquals(List.of(), storage.events);
        assertEquals(List.of(), storage.list(SESSION_ID, USER_ID));
    }

    @Test
    void rejectsSecondNonIdempotentRunWhileSessionIsRunning() {
        coordinator.start(startCommand("request-one")).toCompletableFuture().join();

        assertThrows(IllegalStateException.class,
                () -> coordinator.start(startCommand("request-two")));

        assertEquals(1, storage.list(SESSION_ID, USER_ID).size());
    }

    @Test
    void switchesModelForTheNextRunWhileKeepingHistoryAndTheRuntimeHandle() {
        adapter.emitTerminalEventOnStart(AgentEventType.RUN_COMPLETED);
        AgentRun first = coordinator.start(startCommand("first")).toCompletableFuture().join();
        AgentRun second = coordinator.start(new AgentRunStartCommand(USER_ID, SESSION_ID, "other-model",
                new AgentRuntimeInput("continue", List.of()), "second")).toCompletableFuture().join();

        assertEquals("model", first.model().modelConfigId());
        assertEquals("other-model", second.model().modelConfigId());
        assertEquals("other-model", storage.get(SESSION_ID, USER_ID).definition().modelConfigId());
        assertEquals(2, storage.get(SESSION_ID, USER_ID).definition().revision());
        assertEquals(1, adapter.openSessionCount());
        assertEquals(2, storage.list(SESSION_ID, USER_ID).size());
        assertEquals(6, storage.events.size());
        assertEquals("model", storage.events.get(0).payload().get("modelConfigId"));
        assertEquals("other-model", storage.events.get(3).payload().get("modelConfigId"));
    }

    @Test
    void unavailableModelDoesNotChangeTheSessionOrCreateARun() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(new AgentRunStartCommand(
                USER_ID, SESSION_ID, "missing-model", new AgentRuntimeInput("hello", List.of()), "missing")));
        assertEquals("model", storage.get(SESSION_ID, USER_ID).definition().modelConfigId());
        assertEquals(List.of(), storage.events);
        assertEquals(List.of(), storage.list(SESSION_ID, USER_ID));
    }

    @Test
    void canChooseAnotherModelAfterAFailedRun() {
        adapter.failStartWith(new IllegalStateException("model unavailable"));
        coordinator.start(startCommand("failure")).toCompletableFuture().join();
        adapter.failStartWith(null);
        AgentRun second = coordinator.start(new AgentRunStartCommand(USER_ID, SESSION_ID, "other-model",
                new AgentRuntimeInput("try another model", List.of()), "retry")).toCompletableFuture().join();
        assertEquals(AgentRunStatus.RUNNING, second.status());
        assertEquals("other-model", second.model().modelConfigId());
        assertEquals(1, adapter.openSessionCount());
    }

    @Test
    void recoveryWaitsForAnInProgressRuntimeOpenInsteadOfGuessingFromElapsedTime() throws Exception {
        var opening = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        adapter.beforeOpen(() -> {
            opening.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("open was not released");
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var started = executor.submit(() -> coordinator.start(startCommand("opening")).toCompletableFuture().join());
            org.junit.jupiter.api.Assertions.assertTrue(opening.await(1, java.util.concurrent.TimeUnit.SECONDS));
            var recovered = executor.submit(() -> coordinator.recoverSession(SESSION_ID, USER_ID));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> recovered.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(AgentRunStatus.RUNNING, started.get(1, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(AgentSessionStatus.RUNNING, recovered.get(1, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(List.of(AgentEventType.RUN_ACCEPTED, AgentEventType.RUN_STARTED), eventTypes());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoversTheCurrentRunBeyondTheFirstThousandEventsAndAllowsExplicitContinuation() {
        AgentRun previous = coordinator.start(startCommand("previous")).toCompletableFuture().join();
        coordinator.cancel(new AgentRunCancelCommand(USER_ID, SESSION_ID, previous.id())).toCompletableFuture().join();
        for (int i = 0; i < 1200; i++) {
            storage.events.add(new AgentEvent("history-" + i, SESSION_ID, previous.id(), i + 4,
                    AgentEventType.ASSISTANT_TEXT_DELTA, Map.of(), LocalDateTime.now()));
        }
        AgentSession session = storage.get(SESSION_ID, USER_ID);
        storage.create(new AgentSession(session.schemaVersion(), session.id(), session.userId(), session.definition(),
                session.runtimeBinding(), session.status(), session.title(), 1203, session.gmtCreate(), session.gmtModified()));
        AgentRun interrupted = coordinator.start(startCommand("interrupted")).toCompletableFuture().join();
        handles.close(SESSION_ID);

        AgentSession recovered = coordinator.recoverSession(SESSION_ID, USER_ID);

        assertEquals(AgentSessionStatus.UNKNOWN, recovered.status());
        assertEquals(AgentRunStatus.UNKNOWN, storage.get(SESSION_ID, interrupted.id(), USER_ID).status());
        assertEquals(AgentRunStatus.CANCELLED, storage.get(SESSION_ID, previous.id(), USER_ID).status());
        assertEquals(AgentEventType.RUN_OUTCOME_UNKNOWN, storage.events.get(storage.events.size() - 1).type());
        int eventCount = storage.events.size();
        coordinator.recoverSession(SESSION_ID, USER_ID);
        assertEquals(eventCount, storage.events.size());
        assertEquals(AgentRunStatus.UNKNOWN,
                coordinator.start(startCommand("interrupted")).toCompletableFuture().join().status());
        assertEquals(eventCount, storage.events.size());
        assertEquals(AgentRunStatus.RUNNING,
                coordinator.start(startCommand("continue")).toCompletableFuture().join().status());
        assertEquals(2, adapter.openSessionCount());
    }

    @Test
    void directCancellationRecoversAnOrphanWithoutReplayingIt() {
        AgentRun interrupted = coordinator.start(startCommand("interrupted")).toCompletableFuture().join();
        handles.close(SESSION_ID);

        AgentRun cancelled = coordinator.cancel(new AgentRunCancelCommand(USER_ID, SESSION_ID, interrupted.id()))
                .toCompletableFuture().join();

        assertEquals(AgentRunStatus.UNKNOWN, cancelled.status());
        assertEquals(AgentSessionStatus.UNKNOWN, storage.get(SESSION_ID, USER_ID).status());
        assertEquals(1, adapter.openSessionCount());
    }

    @Test
    void suspendedRunCanBeCancelledWhileAttachedAndRecoveredAfterRuntimeLoss() {
        AgentRun active = coordinator.start(startCommand("active")).toCompletableFuture().join();
        suspend(active);
        assertThrows(IllegalStateException.class, () -> coordinator.start(startCommand("too-early")));
        assertEquals(AgentRunStatus.CANCELLED,
                coordinator.cancel(new AgentRunCancelCommand(USER_ID, SESSION_ID, active.id()))
                        .toCompletableFuture().join().status());
        AgentRun interrupted = coordinator.start(startCommand("interrupted")).toCompletableFuture().join();
        suspend(interrupted);
        handles.close(SESSION_ID);

        assertEquals(AgentSessionStatus.UNKNOWN, coordinator.recoverSession(SESSION_ID, USER_ID).status());
        assertEquals(AgentRunStatus.UNKNOWN, storage.get(SESSION_ID, interrupted.id(), USER_ID).status());
    }

    @Test
    void recoversAnAcceptedRunEvenWhenTheSessionWriteNeverHappened() {
        storage.create(new AgentRun("orphan", SESSION_ID, AgentRunStatus.ACCEPTED, model(),
                "orphan-message", "orphan-request", null, 1, 1, null, null), USER_ID);

        AgentSession recovered = coordinator.recoverSession(SESSION_ID, USER_ID);

        assertEquals(AgentSessionStatus.UNKNOWN, recovered.status());
        assertEquals(1, recovered.lastEventSequence());
        assertEquals(AgentRunStatus.UNKNOWN, storage.get(SESSION_ID, "orphan", USER_ID).status());
        assertEquals(List.of(AgentEventType.RUN_OUTCOME_UNKNOWN), eventTypes());
        assertEquals(0, adapter.openSessionCount());
        coordinator.start(startCommand("explicit-next-run")).toCompletableFuture().join();
        assertEquals(List.of(1L, 2L, 3L), storage.events.stream().map(AgentEvent::sequence).toList());
    }

    @Test
    void alignsPartialTerminalWritesAndKeepsTheLatestRunAfterRecoveringOlderOrphans() {
        // Insert the newer run first: recovery must choose by its first sequence, not list order,
        // ID ordering or the higher last sequence that an older run receives during recovery.
        storage.create(new AgentRun("a-newer", SESSION_ID, AgentRunStatus.COMPLETED, model(),
                "newer-message", "newer-request", "external-newer", 2, 3, null, null), USER_ID);
        storage.create(new AgentRun("z-older", SESSION_ID, AgentRunStatus.RUNNING, model(),
                "older-message", "older-request", "external-older", 1, 1, null, null), USER_ID);
        storage.events.add(new AgentEvent("older-accepted", SESSION_ID, "z-older", 1,
                AgentEventType.RUN_ACCEPTED, Map.of(), LocalDateTime.now()));
        storage.events.add(new AgentEvent("newer-accepted", SESSION_ID, "a-newer", 2,
                AgentEventType.RUN_ACCEPTED, Map.of(), LocalDateTime.now()));
        storage.events.add(new AgentEvent("newer-completed", SESSION_ID, "a-newer", 3,
                AgentEventType.RUN_COMPLETED, Map.of(), LocalDateTime.now()));
        AgentSession before = storage.get(SESSION_ID, USER_ID);
        storage.create(new AgentSession(before.schemaVersion(), before.id(), before.userId(), before.definition(),
                before.runtimeBinding(), AgentSessionStatus.RUNNING, before.title(), 2,
                before.gmtCreate(), before.gmtModified()));

        AgentSession recovered = coordinator.recoverSession(SESSION_ID, USER_ID);

        assertEquals(AgentSessionStatus.READY, recovered.status());
        assertEquals(4, recovered.lastEventSequence());
        assertEquals(AgentRunStatus.UNKNOWN, storage.get(SESSION_ID, "z-older", USER_ID).status());
        assertEquals(AgentRunStatus.COMPLETED, storage.get(SESSION_ID, "a-newer", USER_ID).status());
        assertEquals(List.of(1L, 2L, 3L, 4L), storage.events.stream().map(AgentEvent::sequence).toList());
        assertEquals("z-older", storage.events.get(3).runId());
        coordinator.recoverSession(SESSION_ID, USER_ID);
        assertEquals(4, storage.events.size());
    }

    @Test
    void preservesDurableEventSequencesWhenTheRunSnapshotWriteNeverHappened() {
        AgentRun interrupted = coordinator.start(startCommand("interrupted")).toCompletableFuture().join();
        handles.close(SESSION_ID);
        storage.events.add(new AgentEvent("uncommitted-completion", SESSION_ID, interrupted.id(), 3,
                AgentEventType.RUN_COMPLETED, Map.of(), LocalDateTime.now()));

        AgentSession recovered = coordinator.recoverSession(SESSION_ID, USER_ID);

        assertEquals(AgentSessionStatus.UNKNOWN, recovered.status());
        assertEquals(AgentRunStatus.UNKNOWN, storage.get(SESSION_ID, interrupted.id(), USER_ID).status());
        assertEquals(4, recovered.lastEventSequence());
        assertEquals(List.of(1L, 2L, 3L, 4L), storage.events.stream().map(AgentEvent::sequence).toList());
        assertEquals(AgentEventType.RUN_OUTCOME_UNKNOWN, storage.events.get(3).type());
        assertEquals(1, adapter.openSessionCount());
    }

    @Test
    void ignoresLateRuntimeEventsAfterSessionWasRemoved() {
        AgentRun run = coordinator.start(startCommand("late-event")).toCompletableFuture().join();
        int eventCount = storage.events.size();
        storage.removeSession(SESSION_ID);

        adapter.emitLate(SESSION_ID, run.id(), AgentEventType.ASSISTANT_TEXT_DELTA);

        assertEquals(eventCount, storage.events.size());
    }

    private void suspend(AgentRun run) {
        storage.compareAndSet(new AgentRun(run.id(), run.sessionId(), AgentRunStatus.SUSPENDED, run.model(),
                run.requestMessageId(), run.idempotencyKey(), run.externalRunId(), run.firstEventSequence(),
                run.lastEventSequence(), run.usage(), run.failure()), run.status(), USER_ID);
        AgentSession session = storage.get(SESSION_ID, USER_ID);
        storage.compareAndSet(new AgentSession(session.schemaVersion(), session.id(), session.userId(),
                session.definition(), session.runtimeBinding(), AgentSessionStatus.SUSPENDED, session.title(),
                session.lastEventSequence(), session.gmtCreate(), session.gmtModified()), session.status());
    }

    private AgentRunStartCommand startCommand(String idempotencyKey) {
        return new AgentRunStartCommand(
                USER_ID, SESSION_ID, "model", new AgentRuntimeInput("hello", List.of()), idempotencyKey);
    }

    private List<AgentEventType> eventTypes() {
        return storage.events.stream().map(AgentEvent::type).toList();
    }

    private AgentSession session() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 8, 16, 0);
        AgentDefinition definition = new AgentDefinition(
                "default", "Default", null, "You are helpful.", AgentRuntimeType.PI, "model", 1);
        return new AgentSession(
                2, SESSION_ID, USER_ID, definition,
                new AgentRuntimeBinding(AgentRuntimeType.PI, "1.0.0", "fake-v1", SESSION_ID, null, 1),
                AgentSessionStatus.READY, "Session", 0, now, now);
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model", 1, "openai", "gpt-test", 128000, 4096);
    }

    private static final class MemoryStorage
            implements AgentSessionStorage, AgentRunStorage, AgentEventStorage {

        private final Map<String, AgentSession> sessions = new LinkedHashMap<>();
        private final Map<String, AgentRun> runs = new LinkedHashMap<>();
        private final List<AgentEvent> events = new ArrayList<>();

        @Override public AgentSession create(AgentSession session) { sessions.put(session.id(), session); return session; }
        @Override public AgentSession get(String sessionId, Long userId) {
            AgentSession session = sessions.get(sessionId);
            return session != null && session.userId().equals(userId) ? session : null;
        }
        @Override public List<AgentSession> listByUserId(Long userId) {
            return sessions.values().stream().filter(s -> s.userId().equals(userId)).toList();
        }
        @Override public boolean compareAndSet(AgentSession session, AgentSessionStatus expectedStatus) {
            AgentSession current = sessions.get(session.id());
            if (current == null || current.status() != expectedStatus) return false;
            sessions.put(session.id(), session);
            return true;
        }
        @Override public AgentSession rename(String sessionId, Long userId, String title) {
            AgentSession current = sessions.get(sessionId);
            if (current == null || !current.userId().equals(userId)) {
                throw new IllegalArgumentException("Agent session does not exist");
            }
            AgentSession renamed = new AgentSession(current.schemaVersion(), current.id(), current.userId(),
                    current.definition(), current.runtimeBinding(), current.status(), title,
                    current.lastEventSequence(), current.gmtCreate(), LocalDateTime.now());
            sessions.put(sessionId, renamed);
            return renamed;
        }
        @Override public void delete(String sessionId, Long userId) { throw new UnsupportedOperationException(); }

        void removeSession(String sessionId) { sessions.remove(sessionId); }
        @Override public AgentRun create(AgentRun run, Long userId) { runs.put(run.id(), run); return run; }
        @Override public AgentRun get(String sessionId, String runId, Long userId) {
            AgentRun run = runs.get(runId);
            AgentSession session = sessions.get(sessionId);
            return run != null && run.sessionId().equals(sessionId)
                    && session != null && session.userId().equals(userId) ? run : null;
        }
        @Override public List<AgentRun> list(String sessionId, Long userId) {
            AgentSession session = sessions.get(sessionId);
            if (session == null || !session.userId().equals(userId)) {
                return List.of();
            }
            return runs.values().stream().filter(run -> run.sessionId().equals(sessionId)).toList();
        }
        @Override public boolean compareAndSet(AgentRun run, AgentRunStatus expectedStatus, Long userId) {
            AgentRun current = runs.get(run.id());
            if (current == null || current.status() != expectedStatus) return false;
            runs.put(run.id(), run);
            return true;
        }
        @Override public AgentEvent append(AgentEvent event, Long userId) {
            long expected = events.stream().filter(stored -> stored.sessionId().equals(event.sessionId()))
                    .mapToLong(AgentEvent::sequence).max().orElse(0) + 1;
            assertEquals(expected, event.sequence(), "Stored events must retain a continuous unique sequence");
            events.add(event);
            return event;
        }
        @Override public List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.sequence() > afterSequence).limit(limit).toList();
        }
        @Override public List<AgentEvent> listBefore(String sessionId, Long userId, long beforeSequence, int limit) {
            List<AgentEvent> page = events.stream().filter(event -> event.sequence() < beforeSequence).toList();
            return page.subList(Math.max(0, page.size() - limit), page.size());
        }
    }
}
