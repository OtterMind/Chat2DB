package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.plugin.redis.model.RedisKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void executeUpdatePublishesExecutionMetrics() {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> "executeUpdate".equals(method.getName()) ? 3 : null);
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName()) ? statement : null);

        ExecuteResponse result = RedisScriptExecutor.getInstance().executeUpdate("SET k v", connection, 0);

        assertEquals(3, result.getUpdateCount());
        assertNotNull(result.getExecutionMetrics());
        assertEquals(0L, result.getExecutionMetrics().getFetchDurationMs());
        assertEquals(0, result.getExecutionMetrics().getFetchedRowCount());
        assertEquals(result.getExecutionMetrics().getTotalDurationMs(),
                result.getExecutionMetrics().getExecuteDurationMs());
        assertTrue(result.getExecutionMetrics().getExecuteDurationMs() >= 0L);
    }
}
