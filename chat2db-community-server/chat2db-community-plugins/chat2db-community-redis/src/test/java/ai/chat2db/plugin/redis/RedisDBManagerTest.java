package ai.chat2db.plugin.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisDBManagerTest {

    private final RedisDBManager dbManager = new RedisDBManager();

    @Test
    void dropTableQuotesKeyNameContainingSpaces() {
        assertEquals("del 'foo bar'", dbManager.dropTable(null, null, null, "foo bar"));
    }

    @Test
    void dropTableEscapesQuotesInKeyName() {
        assertEquals("del 'it\\'s'", dbManager.dropTable(null, null, null, "it's"));
    }
}
