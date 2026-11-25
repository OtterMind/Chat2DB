package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Locale;

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

    @Test
    void outputLanguagePromptFollowsUiLocale() {
        assertOutputLanguage(Locale.CHINA, "Simplified Chinese");
        assertOutputLanguage(Locale.JAPAN, "Japanese");
        assertOutputLanguage(Locale.KOREA, "Korean");
        assertOutputLanguage(Locale.forLanguageTag("es-ES"), "Spanish");
        assertOutputLanguage(Locale.US, "English");
        assertOutputLanguage(Locale.ROOT, "English");
        assertOutputLanguage(null, "English");
    }

    private void assertOutputLanguage(Locale locale, String language) {
        assertTrue(AiChatStreamAdapter.buildOutputLanguagePrompt(locale)
                .contains("Respond in " + language + " for both"));
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
