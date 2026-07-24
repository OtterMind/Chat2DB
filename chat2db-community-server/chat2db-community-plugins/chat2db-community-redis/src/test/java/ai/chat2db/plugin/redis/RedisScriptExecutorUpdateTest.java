package ai.chat2db.plugin.redis;

import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers update() paths that must return before touching a connection.
 * These tests run without a Chat2DBContext, so reaching command execution
 * would fail, which is exactly the regression they guard against.
 */
class RedisScriptExecutorUpdateTest {

    @Test
    void updateReturnsEmptyResponseWhenBothKeysAreNull() {
        assertNotNull(RedisScriptExecutor.getInstance().update(null, null));
    }

    @Test
    void typeChangeAbortsInsteadOfDeletingWhenNewTypeHasNothingToWrite() {
        RedisKey oldKey = RedisKey.builder().name("k").type("list").build();
        RedisKey newKey = RedisKey.builder().name("k").type("string").value(null).build();

        assertNotNull(RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }
}
