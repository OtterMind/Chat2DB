package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import ai.chat2db.community.domain.api.service.ai.IAiAttachmentService;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class AiSystemPromptReuseTest {

    @Test
    void agentUsesTheExistingChatPromptAndCurrentLanguage() throws Exception {
        IAiAttachmentService attachments = (IAiAttachmentService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IAiAttachmentService.class},
                (proxy, method, args) -> false);
        AiChatStreamAdapter adapter = new AiChatStreamAdapter(
                null, null, new AiToolAdapter(null, null), null, attachments, null, null, null);
        var existing = AiChatStreamAdapter.class.getDeclaredMethod("resolveSystemPrompt", ChatRequest.class, Map.class);
        existing.setAccessible(true);
        Locale previous = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            String prompt = adapter.defaultSystemPrompt(true);
            assertEquals(existing.invoke(adapter, new ChatRequest(), Map.of("globalDatabaseScope", true)), prompt);
            assertTrue(prompt.contains("## Chart Output Format"));
            assertTrue(prompt.contains("[table::tableName]"));
            assertTrue(prompt.contains("Respond in Simplified Chinese"));

            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertTrue(adapter.defaultSystemPrompt(true).contains("Respond in English"));
            assertFalse(adapter.defaultSystemPrompt(true).contains("Respond in Simplified Chinese"));
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }
}
