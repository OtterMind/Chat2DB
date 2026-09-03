package ai.chat2db.plugin.mysql.explain;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlExplainManagerTest {

    private final MysqlExplainManager manager = new MysqlExplainManager();

    @Test
    void acceptsSingleSelectStatementsIncludingCommentsAndCtes() {
        assertTrue(MysqlExplainManager.isSingleSelectStatement("SELECT * FROM orders"));
        assertTrue(MysqlExplainManager.isSingleSelectStatement("/* dashboard query */ SELECT * FROM orders"));
        assertTrue(MysqlExplainManager.isSingleSelectStatement("WITH recent AS (SELECT * FROM orders) SELECT * FROM recent"));
    }

    @Test
    void rejectsWritesAndMultipleStatements() {
        assertFalse(MysqlExplainManager.isSingleSelectStatement("UPDATE orders SET status = 'done'"));
        assertFalse(MysqlExplainManager.isSingleSelectStatement("SELECT * FROM orders; DELETE FROM orders"));
        assertFalse(MysqlExplainManager.isSingleSelectStatement("SELECT * FROM orders; SELECT * FROM users"));
    }

    @Test
    void checksMySqlExplainVersionBoundaries() {
        assertFalse(MysqlExplainManager.supportsExplainJson("5.6.51"));
        assertTrue(MysqlExplainManager.supportsExplainJson("5.7.44"));
        assertFalse(MysqlExplainManager.supportsExplainAnalyze("8.0.17"));
        assertTrue(MysqlExplainManager.supportsExplainAnalyze("8.0.18"));
        assertTrue(MysqlExplainManager.supportsExplainAnalyze("8.0.36"));
    }

    @Test
    void reportsCurrentMySqlExplainCapabilities() {
        DbExplainCapability capability = manager.capability(DatabaseTypeEnum.MYSQL.name(), "8.0.17");

        assertEquals(DatabaseTypeEnum.MYSQL.name(), capability.getDatabaseType());
        assertEquals("8.0.17", capability.getServerVersion());
        assertTrue(capability.isExplainJsonSupported());
        assertFalse(capability.isExplainAnalyzeSupported());
    }

    @Test
    void explainJsonReturnsTypedResultAndOnlyExecutesExplainSelect() {
        JdbcPlanFixture jdbc = new JdbcPlanFixture("{\"query_block\":{\"select_id\":1}}");
        ConnectInfo connectInfo = mysqlContext("8.0.36", 1L, 1L, "test-user");

        DbExplainResult result = manager.explainJson(jdbc.connection(), connectInfo, "8.0.36",
                "/* dashboard */ SELECT * FROM obj002_orders WHERE user_id = 1", "req-json-1");

        assertEquals("req-json-1", result.getRequestId());
        assertEquals("json", result.getMode());
        assertEquals("{\"query_block\":{\"select_id\":1}}", result.getRawPlan());
        assertTrue(result.getCapability().isExplainAnalyzeSupported());
        assertTrue(jdbc.executedSql().startsWith("EXPLAIN FORMAT=JSON"));
        assertTrue(jdbc.executedSql().toUpperCase().contains("SELECT"));
        assertFalse(jdbc.executedSql().toUpperCase().contains("DELETE"));
        assertNotNull(result.getNormalizedSql());
    }

    @Test
    void explainRejectsDmlBeforeJdbcExecution() {
        JdbcPlanFixture jdbc = new JdbcPlanFixture("{}");
        ConnectInfo connectInfo = mysqlContext("8.0.36", 1L, 1L, "test-user");

        assertThrows(BusinessException.class,
                () -> manager.explainJson(jdbc.connection(), connectInfo, "8.0.36",
                        "UPDATE obj002_orders SET amount = 0", "req-dml"));
        assertEquals(null, jdbc.executedSql());
    }

    @Test
    void cancelInterruptsActiveExplainStatement() throws Exception {
        JdbcPlanFixture jdbc = new JdbcPlanFixture("cancelled");
        jdbc.blockExecute();
        ConnectInfo ownerContext = mysqlContext("8.0.36", 7L, 11L, "alice");
        var executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> manager.executeExplain(
                jdbc.connection(), "EXPLAIN ANALYZE SELECT SLEEP(10)", "req-cancel", ownerContext));

        assertTrue(jdbc.awaitExecute(), "test statement should enter execute before cancellation");
        assertFalse(manager.cancel(mysqlContext("8.0.36", 8L, 11L, "alice"), "req-cancel"),
                "another datasource must not cancel the request");
        assertFalse(manager.cancel(mysqlContext("8.0.36", 7L, 12L, "alice"), "req-cancel"),
                "another console must not cancel the request");
        assertFalse(manager.cancel(mysqlContext("8.0.36", 7L, 11L, "bob"), "req-cancel"),
                "another login user must not cancel the request");
        assertTrue(manager.cancel(ownerContext, "req-cancel"));
        jdbc.releaseExecute();

        assertThrows(Exception.class, () -> future.get(2, TimeUnit.SECONDS));
        assertTrue(jdbc.cancelled());
        executor.shutdownNow();
    }

    private static ConnectInfo mysqlContext(String version, Long dataSourceId, Long consoleId, String loginUser) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DatabaseTypeEnum.MYSQL.name());
        connectInfo.setDbVersion(version);
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setConsoleId(consoleId);
        connectInfo.setLoginUser(loginUser);
        return connectInfo;
    }

    private static final class JdbcPlanFixture {
        private final String explainValue;
        private final AtomicReference<String> sql = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch executeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseExecute = new CountDownLatch(1);
        private boolean blockExecute;

        private JdbcPlanFixture(String explainValue) {
            this.explainValue = explainValue;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    sql.set((String) args[0]);
                    yield statement();
                }
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "execute" -> execute();
                case "getResultSet" -> resultSet();
                case "cancel" -> {
                    cancelled.set(true);
                    releaseExecute.countDown();
                    yield null;
                }
                case "isClosed" -> false;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Object resultSet() {
            AtomicBoolean read = new AtomicBoolean(false);
            return proxy(java.sql.ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> read.compareAndSet(false, true);
                case "getString" -> explainValue;
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private boolean execute() throws Exception {
            executeEntered.countDown();
            if (blockExecute) {
                assertTrue(releaseExecute.await(2, TimeUnit.SECONDS));
            }
            if (cancelled.get()) {
                throw new SQLException("SQL execution canceled");
            }
            return true;
        }

        private void blockExecute() {
            blockExecute = true;
        }

        private boolean awaitExecute() throws InterruptedException {
            return executeEntered.await(2, TimeUnit.SECONDS);
        }

        private void releaseExecute() {
            releaseExecute.countDown();
        }

        private boolean cancelled() {
            return cancelled.get();
        }

        private String executedSql() {
            return sql.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
