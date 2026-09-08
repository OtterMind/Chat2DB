package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEnvironmentStatus;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.tools.exception.agent.AgentRuntimeUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T14:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsV2SessionWithoutStartingTheRuntime() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(AgentRuntimeType.PI);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentServiceImpl service = new AgentServiceImpl(
                new AgentRuntimeRegistry(List.of(adapter)), storage, () -> "session-one", CLOCK);

        AgentSession session = service.createSession(command());

        assertEquals(AgentSession.SCHEMA_VERSION, session.schemaVersion());
        assertEquals(AgentRuntimeType.PI, session.runtimeBinding().runtimeType());
        assertEquals("1.0.0", session.runtimeBinding().runtimeVersion());
        assertEquals("Session", session.title());
        assertEquals(LocalDateTime.of(2026, 9, 8, 14, 0), session.gmtCreate());
        assertEquals(0, adapter.openSessionCount());
        assertEquals(session, service.getSession(session.id(), 1L));
        assertEquals(List.of(session), service.listSessions(1L));
    }

    @Test
    void blockedRuntimeDoesNotCreateV2Session() {
        FakeAgentRuntimeAdapter adapter = new FakeAgentRuntimeAdapter(
                AgentRuntimeType.PI, AgentRuntimeEnvironmentStatus.BLOCKED);
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentServiceImpl service = new AgentServiceImpl(
                new AgentRuntimeRegistry(List.of(adapter)), storage, () -> "session-one", CLOCK);

        assertThrows(AgentRuntimeUnavailableException.class, () -> service.createSession(command()));

        assertEquals(0, storage.createCount());
        assertEquals(0, adapter.openSessionCount());
    }

    @Test
    void missingRuntimeDoesNotCreateV2Session() {
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentServiceImpl service = new AgentServiceImpl(
                new AgentRuntimeRegistry(List.of()), storage, () -> "session-one", CLOCK);

        assertThrows(AgentRuntimeUnavailableException.class, () -> service.createSession(command()));

        assertEquals(0, storage.createCount());
    }

    @Test
    void storageDoesNotRevealAnotherUsersSession() {
        MemoryAgentSessionStorage storage = new MemoryAgentSessionStorage();
        AgentServiceImpl service = new AgentServiceImpl(
                new AgentRuntimeRegistry(List.of(new FakeAgentRuntimeAdapter(AgentRuntimeType.PI))),
                storage,
                () -> "session-one",
                CLOCK);
        service.createSession(command());

        assertNull(service.getSession("session-one", 2L));
        assertEquals(List.of(), service.listSessions(2L));
    }

    private AgentSessionCreateCommand command() {
        return new AgentSessionCreateCommand(
                1L,
                " Session ",
                new AgentDefinition(
                        "default", "Default", null, "You are helpful.",
                        AgentRuntimeType.PI, "model-config", 1),
                new AgentRuntimeEnvironmentRequest("5.3.0", "macos", "arm64"));
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

        int createCount() {
            return createCount;
        }
    }
}
