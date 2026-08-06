package ai.chat2db.plugin.redis;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers update() paths that must return before touching a connection.
 * These tests run without a Chat2DBContext, so reaching command execution
 * would fail, which is exactly the regression they guard against.
 *
 * <p>Invalid type input (null / none / unknown) must be rejected before any
 * Redis command is generated — see the review on #2437. Rejection is surfaced
 * as a {@link BusinessException}; because validation runs before script
 * generation, no command can be emitted for the rejected cases.
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

    // --- Reject null / none / unknown types before emitting any command ---

    @Test
    void rejectsOldKeyTypeNull() {
        RedisKey oldKey = RedisKey.builder().name("k").type(null).build();
        RedisKey newKey = RedisKey.builder().name("k").type("string").build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }

    @Test
    void rejectsNewKeyTypeNull() {
        RedisKey oldKey = RedisKey.builder().name("k").type("string").build();
        RedisKey newKey = RedisKey.builder().name("k").type(null).build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }

    @Test
    void rejectsBothKeyTypesNull() {
        RedisKey oldKey = RedisKey.builder().name("k").type(null).build();
        RedisKey newKey = RedisKey.builder().name("k").type(null).build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }

    @Test
    void rejectsNoneType() {
        // "none" is what Redis TYPE returns for a missing key; fromCode maps it to NONE.
        RedisKey oldKey = RedisKey.builder().name("k").type("none").build();
        RedisKey newKey = RedisKey.builder().name("k").type("string").build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }

    @Test
    void rejectsUnknownTypeCode() {
        RedisKey oldKey = RedisKey.builder().name("k").type("string").build();
        RedisKey newKey = RedisKey.builder().name("k").type("bogus").build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }

    @Test
    void rejectsRenameOfMissingKeyWhenOldTypeIsNone() {
        // Renaming a vanished key (old type "none") must not emit RENAME, which would
        // fail with "no such key" server-side. Validate rejects before that.
        RedisKey oldKey = RedisKey.builder().name("k1").type("none").build();
        RedisKey newKey = RedisKey.builder().name("k2").type("none").build();
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance().update(oldKey, newKey));
    }
}
