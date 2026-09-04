package ai.chat2db.plugin.redis;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.redis.model.RedisKey;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers update() command generation and paths that must return before touching a connection.
 */
class RedisScriptExecutorUpdateTest {

    @AfterEach
    void tearDown() {
        Chat2DBContext.close();
    }

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
    void nullOldKeyKeepsCreatePathValid() {
        RedisKey emptyListKey = RedisKey.builder().name("k").type("list").build();

        assertNotNull(RedisScriptExecutor.getInstance().update(null, emptyListKey));
    }

    @Test
    void rejectsNullOldTypeBeforeGeneratingCommands() {
        RedisKey valid = RedisKey.builder().name("k").type("string").build();

        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(RedisKey.builder().name("k").type(null).build(), valid));
    }

    @Test
    void rejectsNullNewTypeBeforeGeneratingCommands() {
        RedisKey valid = RedisKey.builder().name("k").type("string").build();

        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(valid, RedisKey.builder().name("k").type(null).build()));
    }

    @Test
    void rejectsBothNullTypesBeforeGeneratingCommands() {
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(RedisKey.builder().name("k").type(null).build(), RedisKey.builder().name("k").type(null).build()));
    }

    @Test
    void rejectsUnknownKeyTypesBeforeGeneratingCommands() {
        RedisKey valid = RedisKey.builder().name("k").type("string").build();

        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(RedisKey.builder().name("k").type("unknown").build(), valid));
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(valid, RedisKey.builder().name("k").type("unknown").build()));
    }

    @Test
    void rejectsNoneKeyTypesBeforeGeneratingCommands() {
        RedisKey valid = RedisKey.builder().name("k").type("string").build();

        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(RedisKey.builder().name("k").type("none").build(), valid));
        assertThrows(BusinessException.class, () -> RedisScriptExecutor.getInstance()
                .update(valid, RedisKey.builder().name("k").type("none").build()));
    }

    @Test
    void updatePersistsKeyWhenTtlChangesFromExpiringToNoExpiration() {
        List<String> commands = captureUpdateCommands();

        RedisScriptExecutor.getInstance().update(stringKey("k", 60L), stringKey("k", -1L));

        assertEquals(List.of("PERSIST 'k'"), commands);
    }

    @Test
    void updatePersistsDesiredNoExpirationStateWithoutTrustingOldTtl() {
        List<String> commands = captureUpdateCommands();

        RedisScriptExecutor.getInstance().update(stringKey("k", -1L), stringKey("k", -1L));

        assertEquals(List.of("PERSIST 'k'"), commands);
    }

    @Test
    void updatePersistsRecreatedKeyWhenDesiredTtlHasNoExpiration() {
        List<String> commands = captureUpdateCommands();
        RedisKey oldKey = RedisKey.builder().name("k").type("list").ttl(60L).build();

        RedisScriptExecutor.getInstance().update(oldKey, stringKey("k", -1L));

        assertEquals("PERSIST 'k'", commands.get(commands.size() - 1));
    }

    @Test
    void updatePersistsRenamedKeyUsingQuotedNewName() {
        List<String> commands = captureUpdateCommands();

        RedisScriptExecutor.getInstance().update(stringKey("old key", 60L), stringKey("new key's", -1L));

        assertEquals(List.of("RENAME 'old key' 'new key\\'s'\n", "PERSIST 'new key\\'s'"), commands);
    }

    @Test
    void updateKeepsExpireCommandForPositiveTtl() {
        List<String> commands = captureUpdateCommands();

        RedisScriptExecutor.getInstance().update(stringKey("k", 60L), stringKey("k", 120L));

        assertEquals(List.of("EXPIRE 'k' 120"), commands);
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

    private RedisKey stringKey(String name, Long ttl) {
        return RedisKey.builder()
                .name(name)
                .type("string")
                .value("value")
                .ttl(ttl)
                .build();
    }

    private List<String> captureUpdateCommands() {
        List<String> commands = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        commands.add((String) args[0]);
                        return updateStatement();
                    }
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    return null;
                });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("REDIS");
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        return commands;
    }

    private PreparedStatement updateStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> "executeUpdate".equals(method.getName()) ? 1 : null);
    }
}
