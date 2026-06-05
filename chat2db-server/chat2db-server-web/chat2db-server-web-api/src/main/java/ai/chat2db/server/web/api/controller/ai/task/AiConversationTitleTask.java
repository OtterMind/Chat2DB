package ai.chat2db.server.web.api.controller.ai.task;

import ai.chat2db.server.domain.api.service.AiConversationService;
import ai.chat2db.server.web.api.config.AiChatConfig;
import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * AI 会话标题异步生成任务
 * <p>
 * 流式 SSE 入口检测到该会话"首条消息"时,异步调用
 * {@link PromptType#TITLE_GENERATION} 让模型为会话生成短标题。
 * 失败时降级为截取首条消息前 20 字。
 */
@Slf4j
@Component
public class AiConversationTitleTask {

    private static final int MAX_TITLE_LENGTH = 50;
    private static final int FALLBACK_TITLE_LENGTH = 20;

    @Autowired
    private AiChatConfig aiChatConfig;

    @Autowired
    private AiConversationService aiConversationService;

    @Async
    public void generateTitleAsync(String conversationId, String firstUserMessage) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(firstUserMessage)) {
            return;
        }
        try {
            String title = aiChatConfig.createChatClient(PromptType.TITLE_GENERATION)
                .prompt()
                .user(firstUserMessage)
                .call()
                .content();
            title = normalize(title);
            if (StringUtils.isBlank(title)) {
                title = fallbackTitle(firstUserMessage);
            }
            aiConversationService.updateTitle(conversationId, title);
            log.info("[AiConversationTitleTask] Generated title for {}: {}", conversationId, title);
        } catch (Exception e) {
            log.warn("[AiConversationTitleTask] Failed to generate AI title for {}: {}",
                conversationId, e.getMessage());
            aiConversationService.updateTitle(conversationId, fallbackTitle(firstUserMessage));
        }
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        trimmed = trimmed.replaceAll("\\s+", " ").trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TITLE_LENGTH);
        }
        return trimmed;
    }

    private String fallbackTitle(String message) {
        if (message == null) {
            return "新对话";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > FALLBACK_TITLE_LENGTH
            ? compact.substring(0, FALLBACK_TITLE_LENGTH) + "..."
            : compact;
    }
}
