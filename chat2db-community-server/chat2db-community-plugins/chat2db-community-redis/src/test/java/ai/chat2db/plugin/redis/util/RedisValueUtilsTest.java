package ai.chat2db.plugin.redis.util;

import org.junit.jupiter.api.Test;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisValueUtilsTest {

    @Test
    void wrapsPlainValueInSingleQuotes() {
        assertEquals("'abc'", getRedisValue("abc"));
    }

    @Test
    void escapesBackslashBeforeQuotes() {
        assertEquals("'a\\\\'", getRedisValue("a\\"));
        assertEquals("'a\\\\\\'b'", getRedisValue("a\\'b"));
    }

    @Test
    void escapesSingleQuote() {
        assertEquals("'a\\'b'", getRedisValue("a'b"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(getRedisValue(null));
    }
}
