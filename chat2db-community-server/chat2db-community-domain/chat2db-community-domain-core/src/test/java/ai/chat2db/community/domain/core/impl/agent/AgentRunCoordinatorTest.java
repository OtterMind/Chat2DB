package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.*;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunCoordinatorTest {

    private static final Long USER_ID = 1L;
    private static final String SESSION_ID = "session-one";
    private final MemoryStorage storage = new MemoryStorage();
    private final FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
    private final AgentRuntimeHandleRegistry handles = new AgentRuntimeHandleRegistry();
    private AgentRunCoordinator coordinator;

    @BeforeEach
    void setUp() {
        storage.create(session());
        AtomicInteger ids = new AtomicInteger();
        AgentModelResolver resolver = new AgentModelResolver(null) {
            @Override public AgentModelSnapshot resolve(String modelConfigId) {
                return model();
            }
        };
        coordinator = new AgentRunCoordinator(
                new AgentRuntimeRegistry(List.of(adapter)), handles, storage, storage, storage, resolver, new AiAgentQuestionServiceImpl(),
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
    void rejectsChangingTheFrozenSessionModel() {
        assertThrows(IllegalArgumentException.class, () -> coordinator.start(new AgentRunStartCommand(
                USER_ID, SESSION_ID, "other-model", new AgentRuntimeInput("hello", List.of()), "other")));

        assertEquals(List.of(), storage.events);
        assertEquals(List.of(), storage.list(SESSION_ID, USER_ID));
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
            throw new UnsupportedOperationException();
        }
        @Override public void delete(String sessionId, Long userId) { throw new UnsupportedOperationException(); }
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
        @Override public AgentEvent append(AgentEvent event, Long userId) { events.add(event); return event; }
        @Override public List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.sequence() > afterSequence).limit(limit).toList();
        }
    }
}
