package ai.chat2db.server.web.api.controller.ai.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;

import ai.chat2db.server.domain.core.cache.MemoryCacheManage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Component
public class AiConversationCache {

    private static final int MAX_MESSAGES = 6;
    private static final String CACHE_KEY_PREFIX = "ai_conversation_";
    private static final Pattern SQL_CODE_BLOCK = Pattern.compile("```sql\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_CODE_BLOCK = Pattern.compile("```\\s*([\\s\\S]*?)```");
    private static final Pattern SQL_KEYWORDS = Pattern.compile("\\b(select|insert|update|delete|create|alter|drop)\\b",
            Pattern.CASE_INSENSITIVE);

    public void appendTurn(String conversationId, String userContent, String assistantContent) {
        if (StringUtils.isBlank(conversationId) || StringUtils.isBlank(userContent)
                || StringUtils.isBlank(assistantContent)) {
            return;
        }

        String cacheKey = buildCacheKey(conversationId);
        synchronized (cacheKey.intern()) {
            ConversationState state = getState(conversationId);
            if (state == null) {
                state = new ConversationState();
            }
            appendMessage(state.messages, new ChatHistoryMessage("user", userContent));
            appendMessage(state.messages, new ChatHistoryMessage("assistant", assistantContent));
            String sql = extractSql(assistantContent);
            if (StringUtils.isNotBlank(sql)) {
                state.previousSql = sql;
            }
            MemoryCacheManage.put(cacheKey, state);
        }
    }

    public String getHistoryJson(String conversationId) {
        ConversationState state = getState(conversationId);
        if (state == null) {
            return null;
        }
        return JSON.toJSONString(new ArrayList<>(state.messages));
    }

    public String getPreviousSql(String conversationId) {
        ConversationState state = getState(conversationId);
        if (state == null) {
            return null;
        }
        return state.previousSql;
    }

    private void appendMessage(Deque<ChatHistoryMessage> messages, ChatHistoryMessage message) {
        messages.addLast(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }
    }

    private ConversationState getState(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return null;
        }
        return MemoryCacheManage.get(buildCacheKey(conversationId));
    }

    private String buildCacheKey(String conversationId) {
        return CACHE_KEY_PREFIX + conversationId;
    }

    private String extractSql(String content) {
        Matcher sqlMatcher = SQL_CODE_BLOCK.matcher(content);
        if (sqlMatcher.find()) {
            return sqlMatcher.group(1).trim();
        }

        Matcher codeMatcher = GENERIC_CODE_BLOCK.matcher(content);
        if (codeMatcher.find()) {
            String code = codeMatcher.group(1).trim();
            if (SQL_KEYWORDS.matcher(code).find()) {
                return code;
            }
        }
        return null;
    }

    private static class ConversationState implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Deque<ChatHistoryMessage> messages = new ArrayDeque<>();
        private String previousSql;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatHistoryMessage implements Serializable {
        private static final long serialVersionUID = 1L;

        private String role;
        private String content;
    }
}
