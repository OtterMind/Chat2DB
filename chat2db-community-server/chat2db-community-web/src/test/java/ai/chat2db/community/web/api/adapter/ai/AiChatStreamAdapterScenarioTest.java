package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;

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
        String secretPrompt = "private context and customer values";

        assertEquals(secretPrompt.length(), AiChatStreamAdapter.textLength(secretPrompt));
        assertEquals(0, AiChatStreamAdapter.textLength(null));
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

}
