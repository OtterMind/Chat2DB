package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiSelectedKnowledge;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatStreamAdapterScenarioTest {

    @Test
    void resolvesSqlScenarioPromptsFromQuestionType() {
        assertPromptContains("SQL_EXPLAIN", "SQL Explanation Mode");
        assertPromptContains("SQL_OPTIMIZER", "SQL Optimization Mode");
        assertPromptContains("SQL_DEBUG", "SQL Diagnosis Mode");
        assertPromptContains("SQL_DEBUG_CHAIN", "SQL Diagnosis Mode");
        assertPromptContains("SQL_2_SQL", "SQL Dialect Conversion Mode");
    }

    @Test
    void ordinaryAndUnknownQuestionTypesDoNotAddSqlScenarioPrompt() {
        assertPromptIsEmpty("ORDINARY_CHAT");
        assertPromptIsEmpty("UNKNOWN_SCENARIO");
        assertPromptIsEmpty(null);
    }

    @Test
    void persistHistoryDefaultsToTrueAndCanBeDisabledForOneShotScenarios() {
        ChatRequest defaultRequest = new ChatRequest();
        assertTrue(AiChatStreamAdapter.shouldPersistHistory(defaultRequest));

        ChatRequest oneShotRequest = new ChatRequest();
        oneShotRequest.setPersistHistory(false);
        assertFalse(AiChatStreamAdapter.shouldPersistHistory(oneShotRequest));
    }

    @Test
    void sensitiveLogTextIsReducedToLengthOnly() {
        String secretPrompt = "enterprise knowledge and customer values";

        assertEquals(secretPrompt.length(), AiChatStreamAdapter.textLength(secretPrompt));
        assertEquals(0, AiChatStreamAdapter.textLength(null));
    }

    @Test
    void persistsServerResolvedKnowledgeSnapshotWithUserMessage() throws Exception {
        RecordingHistoryService historyService = new RecordingHistoryService();
        AiChatStreamAdapter adapter = adapter(historyService);
        ChatRequest request = existingSessionRequest();
        AiSelectedKnowledge knowledge = new AiSelectedKnowledge();
        knowledge.setId(186L);
        knowledge.setType("KNOWLEDGE_TERM");
        knowledge.setKey("三全水饺");
        knowledge.setValue("速冻水饺商品集合");

        invokePrepareSession(adapter, request, List.of(knowledge));

        assertEquals(1, historyService.addedMessages.size());
        AiSelectedKnowledge persisted = historyService.addedMessages.get(0).getSelectedKnowledge().get(0);
        assertEquals(186L, persisted.getId());
        assertEquals("KNOWLEDGE_TERM", persisted.getType());
        assertEquals("三全水饺", persisted.getKey());
        assertEquals("速冻水饺商品集合", persisted.getValue());
    }

    @Test
    void doesNotInventKnowledgeWhenResolvedSnapshotIsEmpty() throws Exception {
        RecordingHistoryService historyService = new RecordingHistoryService();

        invokePrepareSession(adapter(historyService), existingSessionRequest(), List.of());

        assertEquals(1, historyService.addedMessages.size());
        assertTrue(historyService.addedMessages.get(0).getSelectedKnowledge().isEmpty());
    }

    private void assertPromptContains(String questionType, String expected) {
        ChatRequest request = new ChatRequest();
        request.setQuestionType(questionType);
        assertTrue(AiChatStreamAdapter.buildQuestionTypePrompt(request).contains(expected));
    }

    private void assertPromptIsEmpty(String questionType) {
        ChatRequest request = new ChatRequest();
        request.setQuestionType(questionType);
        assertTrue(AiChatStreamAdapter.buildQuestionTypePrompt(request).isEmpty());
    }

    private AiChatStreamAdapter adapter(IAiChatHistoryService historyService) {
        return new AiChatStreamAdapter(null, null, new AiToolAdapter(null, null), historyService,
                null, null, null, null, null);
    }

    private ChatRequest existingSessionRequest() {
        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setInput("查询销量");
        return request;
    }

    private void invokePrepareSession(AiChatStreamAdapter adapter, ChatRequest request,
                                      List<AiSelectedKnowledge> selectedKnowledge) throws Exception {
        Method method = AiChatStreamAdapter.class.getDeclaredMethod(
                "prepareSession", ChatRequest.class, Long.class, List.class);
        method.setAccessible(true);
        method.invoke(adapter, request, 35L, selectedKnowledge);
    }

    private static class RecordingHistoryService implements IAiChatHistoryService {
        private final List<AiChatMessageAddRequest> addedMessages = new java.util.ArrayList<>();

        @Override
        public AiChatSession createSession(Long userId, String firstMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiChatMessage addMessage(AiChatMessageAddRequest request) {
            addedMessages.add(request);
            return new AiChatMessage();
        }

        @Override
        public List<AiChatSession> listSessions(Long userId) {
            return List.of();
        }

        @Override
        public List<AiChatMessage> getMessages(String sessionId, Long userId) {
            return List.of();
        }

        @Override
        public List<AiChatMessage> getHistoryForAI(String sessionId, Long userId) {
            return List.of();
        }

        @Override
        public void deleteSession(String sessionId, Long userId) {
        }
    }
}
