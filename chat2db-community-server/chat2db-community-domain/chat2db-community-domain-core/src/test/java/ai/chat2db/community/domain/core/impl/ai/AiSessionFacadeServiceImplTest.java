package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentSession;
import ai.chat2db.community.domain.api.model.agent.AgentSessionStatus;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunCancelCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunStartCommand;
import ai.chat2db.community.domain.api.model.request.agent.AgentSessionCreateCommand;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.agent.AgentService;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiSessionFacadeServiceImplTest {

    @Test
    void mergesAndSortsV1AndV2WithExplicitVersions() {
        AiChatSession v1 = v1("v1", LocalDateTime.of(2026, 9, 8, 10, 0));
        AgentSession v2 = v2("v2", LocalDateTime.of(2026, 9, 9, 10, 0));
        AiSessionFacadeServiceImpl service = new AiSessionFacadeServiceImpl(
                new V1History(List.of(v1)), new V2Agents(List.of(v2)));

        var summaries = service.listSessions(1L);

        assertEquals(List.of("v2", "v1"), summaries.stream().map(summary -> summary.id()).toList());
        assertEquals(2, summaries.get(0).sessionVersion());
        assertEquals(1, summaries.get(1).sessionVersion());
        assertEquals("v1", service.getSession("v1", 1L, 1).id());
        assertEquals("v2", service.getSession("v2", 1L, 2).id());
    }

    @Test
    void rejectsCrossVersionIdentifierCollisions() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 9, 10, 0);
        AiSessionFacadeServiceImpl service = new AiSessionFacadeServiceImpl(
                new V1History(List.of(v1("same", now))), new V2Agents(List.of(v2("same", now))));

        assertThrows(IllegalStateException.class, () -> service.listSessions(1L));
    }

    private AiChatSession v1(String id, LocalDateTime modified) {
        AiChatSession session = new AiChatSession();
        session.setId(id);
        session.setUserId(1L);
        session.setTitle("V1");
        session.setGmtCreate(modified.minusHours(1));
        session.setGmtModified(modified);
        return session;
    }

    private AgentSession v2(String id, LocalDateTime modified) {
        AgentDefinition definition = new AgentDefinition(
                "default", "Default", null, "Help", AgentRuntimeType.PI, "model", 1);
        return new AgentSession(
                2, id, 1L, definition,
                new AgentRuntimeBinding(AgentRuntimeType.PI, "1", "1", id, null, 1),
                AgentSessionStatus.READY, "V2", 0, modified.minusHours(1), modified);
    }

    private record V1History(List<AiChatSession> sessions) implements IAiChatHistoryService {
        @Override public AiChatSession createSession(Long userId, String firstMessage) { throw new UnsupportedOperationException(); }
        @Override public AiChatMessage addMessage(AiChatMessageAddRequest request) { throw new UnsupportedOperationException(); }
        @Override public List<AiChatSession> listSessions(Long userId) { return sessions; }
        @Override public void renameSession(String sessionId, Long userId, String title) { throw new UnsupportedOperationException(); }
        @Override public List<AiChatMessage> getMessages(String sessionId, Long userId) { return List.of(); }
        @Override public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) { return List.of(); }
        @Override public void deleteSession(String sessionId, Long userId) { throw new UnsupportedOperationException(); }
    }

    private record V2Agents(List<AgentSession> sessions) implements AgentService {
        @Override public AgentSession createSession(AgentSessionCreateCommand command) { throw new UnsupportedOperationException(); }
        @Override public AgentSession getSession(String sessionId, Long userId) {
            for (AgentSession session : sessions) {
                if (session.id().equals(sessionId)) {
                    return session;
                }
            }
            return null;
        }
        @Override public List<AgentSession> listSessions(Long userId) { return sessions; }
        @Override public CompletionStage<AgentRun> startRun(AgentRunStartCommand command) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<AgentRun> cancelRun(AgentRunCancelCommand command) { throw new UnsupportedOperationException(); }
        @Override public List<AgentEvent> listEvents(String sessionId, Long userId, long afterSequence, int limit) {
            return List.of();
        }
        @Override public AgentSession renameSession(String sessionId, Long userId, String title) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteSession(String sessionId, Long userId) {
            throw new UnsupportedOperationException();
        }
    }
}
