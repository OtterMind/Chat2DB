package ai.chat2db.server.web.api.controller.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiConversationCacheTest {

    @Test
    void appendTurnCachesRecentHistoryAndPreviousSql() {
        AiConversationCache cache = new AiConversationCache();

        cache.appendTurn("conversation-1", "查询订单", "```sql\nselect * from orders\n```");

        assertTrue(cache.getHistoryJson("conversation-1").contains("查询订单"));
        assertEquals("select * from orders", cache.getPreviousSql("conversation-1"));
    }

    @Test
    void appendTurnKeepsOnlyRecentMessages() {
        AiConversationCache cache = new AiConversationCache();

        for (int i = 0; i < 4; i++) {
            cache.appendTurn("conversation-1", "user-" + i, "```sql\nselect " + i + "\n```");
        }

        String history = cache.getHistoryJson("conversation-1");
        assertTrue(!history.contains("user-0"));
        assertTrue(history.contains("user-1"));
        assertEquals("select 3", cache.getPreviousSql("conversation-1"));
    }
}
