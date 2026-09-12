package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.domain.api.enums.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.service.agent.AgentEventStorage;
import ai.chat2db.community.domain.api.service.agent.AgentRunStorage;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T14:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsV2SessionWithoutStartingTheRuntime() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(adapter));
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one", CLOCK);

        AgentSession session = service.createSession(command());

        assertEquals(AgentSession.SCHEMA_VERSION, session.schemaVersion());
        assertEquals(AgentRuntimeType.PI, session.runtimeBinding().runtimeType());
        assertEquals("1.0.0", session.runtimeBinding().runtimeVersion());
        assertEquals("Session", session.title());
        assertEquals("DEFAULT", session.definition().id());
        assertEquals("Chat2DB Agent", session.definition().name());
        assertTrue(session.definition().systemPrompt().contains("You are Chat2DB Agent."));
        assertEquals("model-config", session.definition().modelConfigId());
        assertEquals(LocalDateTime.of(2026, 9, 8, 14, 0), session.gmtCreate());
        assertEquals(0, adapter.openSessionCount());
        assertEquals(session, service.getSession(session.id(), 1L));
        assertEquals(List.of(session), service.listSessions(1L));
    }

    @Test
    void doesNotRecoverARecentlyAcceptedRunBeforeItsRuntimeHandleIsRegistered() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(adapter));
        MemoryAgentEventStorage events = new MemoryAgentEventStorage(List.of(
                new AgentEvent("event-one", "session-one", "run-one", 1, AgentEventType.RUN_ACCEPTED,
                        Map.of(), LocalDateTime.of(2026, 9, 8, 14, 0))));
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), events,
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one", CLOCK);
        AgentSession created = service.createSession(command());
        storage.put(new AgentSession(created.schemaVersion(), created.id(), created.userId(), created.definition(),
                created.runtimeBinding(), AgentSessionStatus.RUNNING, created.title(), 1,
                created.gmtCreate(), created.gmtModified()));

        assertEquals(AgentSessionStatus.RUNNING, service.getSession("session-one", 1L).status());
        assertEquals(0, events.appendCount);
    }

    @Test
    void blockedRuntimeDoesNotCreateV2Session() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(
                AgentRuntimeType.PI, AgentRuntimeEnvironmentStatus.BLOCKED);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(adapter));
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one", CLOCK);

        assertThrows(AgentRuntimeUnavailableException.class, () -> service.createSession(command()));

        assertEquals(0, storage.createCount());
        assertEquals(0, adapter.openSessionCount());
    }

    @Test
    void missingRuntimeDoesNotCreateV2Session() {
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of());
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one", CLOCK);

        assertThrows(AgentRuntimeUnavailableException.class, () -> service.createSession(command()));

        assertEquals(0, storage.createCount());
    }

    @Test
    void storageDoesNotRevealAnotherUsersSession() {
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(
                List.of(new FakeAgentRuntimeAdapter(AgentRuntimeType.PI)));
        AgentServiceImpl service = new AgentServiceImpl(
                registry,
                storage,
                unusedCoordinator(registry, storage),
                new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one",
                CLOCK);
        service.createSession(command());

        assertNull(service.getSession("session-one", 2L));
        assertEquals(List.of(), service.listSessions(2L));
    }

    @Test
    void eventQueriesEnforceOwnershipAndBounds() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(adapter));
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(),
                () -> "session-one", CLOCK);
        service.createSession(command());

        assertEquals(List.of(), service.listEvents("session-one", 1L, 0, 200));
        assertThrows(IllegalArgumentException.class,
                () -> service.listEvents("session-one", 2L, 0, 200));
        assertThrows(IllegalArgumentException.class,
                () -> service.listEvents("session-one", 1L, -1, 200));
        assertThrows(IllegalArgumentException.class,
                () -> service.listEvents("session-one", 1L, 0, 1001));
    }

    @Test
    void renamesAndDeletesAnIdleV2Session() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry(List.of(adapter));
        AgentServiceImpl service = new AgentServiceImpl(
                registry, storage, unusedCoordinator(registry, storage), new UnusedAgentEventStorage(),
                new AgentRuntimeHandleRegistry(), new AiAgentPromptServiceImpl(), () -> "session-one", CLOCK);
        service.createSession(command());

        assertEquals("Renamed", service.renameSession("session-one", 1L, " Renamed ").title());
        service.deleteSession("session-one", 1L);

        assertNull(service.getSession("session-one", 1L));
        assertEquals("session-one", adapter.deletedSessionId());
    }

    private AgentSessionCreateCommand command() {
        return new AgentSessionCreateCommand(
                1L,
                " Session ",
                AgentRuntimeType.PI, "model-config",
                new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64"));
    }

    private AgentRunCoordinator unusedCoordinator(
            AgentRuntimeRegistry registry,
            AgentSessionStorage sessionStorage) {
        return new AgentRunCoordinator(
                registry,
                new AgentRuntimeHandleRegistry(),
                sessionStorage,
                new UnusedAgentRunStorage(),
                new UnusedAgentEventStorage(),
                new AgentModelResolver(null),
                new AiAgentQuestionServiceImpl(), new AiAgentPromptServiceImpl(), new AiAgentContextServiceImpl(null, CLOCK),
                new AiAgentSkillServiceImpl(null, null),
                () -> "unused",
                CLOCK);
    }

    private static final class UnusedAgentRunStorage implements AgentRunStorage {
        @Override public AgentRun create(AgentRun run, Long userId) { throw new UnsupportedOperationException(); }
        @Override public AgentRun get(String sessionId, String runId, Long userId) { return null; }
        @Override public List<AgentRun> list(String sessionId, Long userId) { return List.of(); }
        @Override public boolean compareAndSet(AgentRun run, AgentRunStatus expectedStatus, Long userId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedAgentEventStorage implements AgentEventStorage {
        @Override public AgentEvent append(AgentEvent event, Long userId) { throw new UnsupportedOperationException(); }
        @Override public List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit) {
            return List.of();
        }
    }

    private static final class MemoryAgentEventStorage implements AgentEventStorage {
        private final List<AgentEvent> events;
        private int appendCount;

        private MemoryAgentEventStorage(List<AgentEvent> events) {
            this.events = events;
        }

        @Override public AgentEvent append(AgentEvent event, Long userId) {
            appendCount++;
            return event;
        }

        @Override public List<AgentEvent> list(String sessionId, Long userId, long afterSequence, int limit) {
            return events;
        }
    }

    private static final class MemoryAgentSessionStorage implements AgentSessionStorage {

        private final Map<String, AgentSession> sessions = new LinkedHashMap<>();
        private int createCount;

        @Override
        public AgentSession create(AgentSession session) {
            createCount++;
            if (sessions.putIfAbsent(session.id(), session) != null) {
                throw new IllegalStateException("duplicate session");
            }
            return session;
        }

        void put(AgentSession session) {
            sessions.put(session.id(), session);
        }

        @Override
        public AgentSession get(String sessionId, Long userId) {
            AgentSession session = sessions.get(sessionId);
            return session != null && session.userId().equals(userId) ? session : null;
        }

        @Override
        public List<AgentSession> listByUserId(Long userId) {
            return sessions.values().stream()
                    .filter(session -> session.userId().equals(userId))
                    .toList();
        }

        @Override
        public boolean compareAndSet(AgentSession session, AgentSessionStatus expectedStatus) {
            AgentSession current = sessions.get(session.id());
            if (current == null || current.status() != expectedStatus) {
                return false;
            }
            sessions.put(session.id(), session);
            return true;
        }

        @Override
        public AgentSession rename(String sessionId, Long userId, String title) {
            AgentSession session = get(sessionId, userId);
            AgentSession renamed = new AgentSession(
                    session.schemaVersion(), session.id(), session.userId(), session.definition(),
                    session.runtimeBinding(), session.status(), title, session.lastEventSequence(),
                    session.gmtCreate(), session.gmtModified());
            sessions.put(sessionId, renamed);
            return renamed;
        }

        @Override
        public void delete(String sessionId, Long userId) {
            if (get(sessionId, userId) == null) {
                throw new IllegalArgumentException();
            }
            sessions.remove(sessionId);
        }

        int createCount() {
            return createCount;
        }
    }
}
