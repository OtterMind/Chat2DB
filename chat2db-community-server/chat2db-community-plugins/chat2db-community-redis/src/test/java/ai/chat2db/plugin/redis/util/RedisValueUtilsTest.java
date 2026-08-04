package ai.chat2db.plugin.redis.util;

import org.junit.jupiter.api.Test;

import static ai.chat2db.plugin.redis.util.RedisValueUtils.getRedisValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void rejectsCharactersThatRedisJdbcSplitsBeforeQuoteParsing() {
        assertThrows(IllegalArgumentException.class, () -> getRedisValue("first\nDEL other"));
        assertThrows(IllegalArgumentException.class, () -> getRedisValue("first\rDEL other"));
        assertThrows(IllegalArgumentException.class, () -> getRedisValue("first\0second"));
    }
}
