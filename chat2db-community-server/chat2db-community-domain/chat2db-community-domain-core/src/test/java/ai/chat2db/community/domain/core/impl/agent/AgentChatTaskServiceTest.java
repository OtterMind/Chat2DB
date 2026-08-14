package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskContextTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentTaskOriginTypeEnum;
import ai.chat2db.community.domain.api.model.agent.AgentChatTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.agent.AgentChatTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.agent.IAgentRunCoordinator;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentChatTaskServiceTest {

    private AgentControlServiceTest.MemoryAgentControlStorage storage;
    private FakeHistoryService historyService;
    private AgentChatTaskServiceImpl service;
    private AgentDefinition agent;
    private int dispatchCount;

    @BeforeEach
    void setUp() {
        storage = new AgentControlServiceTest.MemoryAgentControlStorage();
        historyService = new FakeHistoryService();
        AgentDefinitionServiceImpl agentService = new AgentDefinitionServiceImpl(storage);
        AgentTaskServiceImpl taskService = new AgentTaskServiceImpl(storage);
        AgentTaskContextServiceImpl contextService = new AgentTaskContextServiceImpl(storage, taskService);
        IAgentRunCoordinator coordinator = new NoopCoordinator();
        service = new AgentChatTaskServiceImpl(
                historyService, agentService, taskService, contextService, coordinator);

        AgentDefinitionCreateRequest definition = new AgentDefinitionCreateRequest();
        definition.setName("AnalysisAgent");
        definition.setCreatedBy(7L);
        agent = agentService.create(definition);
    }

    @Test
    void persistsDelegationReferenceAndConversationSnapshotBeforeDispatch() {
        AiChatSession session = historyService.createSession(7L, "Existing conversation");
        historyService.addPlainMessage(session.getId(), 7L, "user", "Compare channel performance.");
        historyService.addPlainMessage(session.getId(), 7L, "assistant", "Which period should I use?");

        AgentChatTaskCreation created = service.create(request(session.getId(), "message-1"));

        assertEquals(AgentTaskOriginTypeEnum.CHAT, created.getTaskCreation().getTask().getOriginType());
        assertEquals(session.getId(), created.getTaskCreation().getTask().getOriginSessionId());
        assertEquals("message-1", created.getTaskCreation().getTask().getOriginMessageId());
        assertEquals("TASK_DELEGATION", created.getMessage().getMessageType());
        assertEquals(created.getTaskCreation().getTask().getId(), created.getMessage().getTaskId());
        assertEquals(agent.getId(), created.getMessage().getAgentId());
        assertEquals("AnalysisAgent", created.getMessage().getAgentName());
        assertEquals(1, dispatchCount);

        var contexts = storage.listTaskContexts(created.getTaskCreation().getTask().getId());
        assertEquals(1, contexts.size());
        assertEquals(AgentTaskContextTypeEnum.CHAT_SNAPSHOT, contexts.get(0).getType());
        assertEquals(true, contexts.get(0).getContent().contains("Compare channel performance."));
        assertEquals(true, contexts.get(0).getContent().contains("Which period should I use?"));
    }

    @Test
    void retriesByMessageIdWithoutCreatingOrDispatchingAnotherTask() {
        AiChatSession session = historyService.createSession(7L, "Existing conversation");
        AgentChatTaskCreateRequest request = request(session.getId(), "message-2");

        AgentChatTaskCreation first = service.create(request);
        AgentChatTaskCreation retried = service.create(request);

        assertEquals(first.getTaskCreation().getTask().getId(), retried.getTaskCreation().getTask().getId());
        assertEquals(first.getMessage().getId(), retried.getMessage().getId());
        assertEquals(1, storage.listTasks().size());
        assertEquals(1, historyService.getMessages(session.getId(), 7L).stream()
                .filter(message -> "message-2".equals(message.getId())).count());
        assertEquals(1, dispatchCount);
    }

    @Test
    void createsAChatSessionWhenDelegatingFromANewConversation() {
        AgentChatTaskCreation created = service.create(request(null, "message-3"));

        assertNotNull(created.getSession().getId());
        assertEquals(created.getSession().getId(), created.getTaskCreation().getTask().getOriginSessionId());
        assertEquals(1, historyService.listSessions(7L).size());
    }

    @Test
    void boundedSnapshotKeepsTheMostRecentConversationMessage() {
        AiChatSession session = historyService.createSession(7L, "Long conversation");
        historyService.addPlainMessage(session.getId(), 7L, "user", "x".repeat(40_000));
        historyService.addPlainMessage(session.getId(), 7L, "assistant", "MOST_RECENT_CONTEXT");

        AgentChatTaskCreation created = service.create(request(session.getId(), "message-4"));

        var snapshot = storage.listTaskContexts(created.getTaskCreation().getTask().getId()).get(0);
        assertEquals(true, snapshot.getContent().contains("MOST_RECENT_CONTEXT"));
        assertEquals(true, snapshot.getContent().length() <= 32_000);
    }

    private AgentChatTaskCreateRequest request(String sessionId, String messageId) {
        AgentChatTaskCreateRequest request = new AgentChatTaskCreateRequest();
        request.setSessionId(sessionId);
        request.setMessageId(messageId);
        request.setContent("@AnalysisAgent analyze this conversation");
        request.setTaskDescription("analyze this conversation");
        request.setAssigneeAgentId(agent.getId());
        request.setCreatedBy(7L);
        return request;
    }

    private class NoopCoordinator implements IAgentRunCoordinator {
        @Override
        public AgentRun dispatch(String runId) {
            dispatchCount++;
            AgentRun run = storage.getRun(runId);
            run.setStatus(AgentRunStatusEnum.COMPLETED);
            return run;
        }

        @Override
        public AgentRun resumeAfterApproval(String runId, String approvalContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRun cancel(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ai.chat2db.community.domain.api.model.agent.AgentRunEvent> listEvents(String runId) {
            return List.of();
        }
    }

    private static class FakeHistoryService implements IAiChatHistoryService {
        private final List<AiChatSession> sessions = new ArrayList<>();
        private final List<AiChatMessage> messages = new ArrayList<>();

        @Override
        public AiChatSession createSession(Long userId, String firstMessage) {
            AiChatSession session = new AiChatSession();
            session.setId(UUID.randomUUID().toString());
            session.setUserId(userId);
            session.setTitle(firstMessage);
            session.setGmtCreate(LocalDateTime.now());
            session.setGmtModified(LocalDateTime.now());
            sessions.add(session);
            return session;
        }

        @Override
        public AiChatMessage addMessage(AiChatMessageAddRequest request) {
            AiChatMessage existing = messages.stream()
                    .filter(message -> Objects.equals(message.getId(), request.getId()))
                    .findFirst().orElse(null);
            if (existing != null) return existing;
            AiChatMessage message = new AiChatMessage();
            message.setId(request.getId() == null ? UUID.randomUUID().toString() : request.getId());
            message.setSessionId(request.getSessionId());
            message.setRole(request.getRole());
            message.setContent(request.getContent());
            message.setAttachments(request.getAttachments());
            message.setMessageType(request.getMessageType());
            message.setTaskId(request.getTaskId());
            message.setAgentId(request.getAgentId());
            message.setAgentName(request.getAgentName());
            messages.add(message);
            return message;
        }

        void addPlainMessage(String sessionId, Long userId, String role, String content) {
            AiChatMessageAddRequest request = new AiChatMessageAddRequest();
            request.setSessionId(sessionId);
            request.setUserId(userId);
            request.setRole(role);
            request.setContent(content);
            addMessage(request);
        }

        @Override
        public List<AiChatSession> listSessions(Long userId) {
            return sessions.stream().filter(session -> Objects.equals(session.getUserId(), userId)).toList();
        }

        @Override
        public List<AiChatMessage> getMessages(String sessionId, Long userId) {
            return messages.stream().filter(message -> sessionId.equals(message.getSessionId())).toList();
        }

        @Override
        public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) {
            return getMessages(sessionId, userId);
        }

        @Override
        public void deleteSession(String sessionId, Long userId) {
            sessions.removeIf(session -> sessionId.equals(session.getId())
                    && Objects.equals(userId, session.getUserId()));
            messages.removeIf(message -> sessionId.equals(message.getSessionId()));
        }
    }
}
