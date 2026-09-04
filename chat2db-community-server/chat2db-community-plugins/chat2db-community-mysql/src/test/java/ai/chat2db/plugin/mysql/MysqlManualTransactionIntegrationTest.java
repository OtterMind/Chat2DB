package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.community.tools.util.I18nUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MysqlManualTransactionIntegrationTest {

    private static final String REQUIRE_MYSQL_FIXTURE_PROPERTY = "chat2db.mysql.fixture.required";
    private static final AtomicLong CONSOLE_IDS = new AtomicLong(20_000L);
    private static IPlugin previousMysqlPlugin;
    private static Field messageSourceField;
    private static MessageSource previousMessageSource;

    @BeforeAll
    static void registerPlugin() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        previousMysqlPlugin = Chat2DBContext.PLUGIN_MAP.put("MYSQL", new MysqlPlugin());
        messageSourceField = I18nUtils.class.getDeclaredField("messageSourceStatic");
        messageSourceField.setAccessible(true);
        previousMessageSource = (MessageSource) messageSourceField.get(null);
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("sqlResult.success", Locale.US, "Success");
        messageSourceField.set(null, messageSource);
    }

    @AfterAll
    static void cleanup() {
        ConnectionPool.releaseAll(true);
        Chat2DBContext.removeContext();
        if (previousMysqlPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove("MYSQL");
        } else {
            Chat2DBContext.PLUGIN_MAP.put("MYSQL", previousMysqlPlugin);
        }
        if (messageSourceField != null) {
            try {
                messageSourceField.set(null, previousMessageSource);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @ParameterizedTest(name = "MySQL transaction commit/rollback on port {0}")
    @ValueSource(ints = {3357, 3380})
    void commitAndRollbackControlVisibility(int port) throws Exception {
        requireMysqlAvailable(port);
        prepareSchema(port);
        try (Connection observer = open(port)) {
            long commitConsole = nextConsoleId();
            ConnectInfo committing = registerBound(port, commitConsole);
            execute(committing.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('committed')");
            assertEquals(0, count(observer, "tx_innodb"));
            assertEquals(ConnectionPool.TransactionOutcome.COMMITTED, ConnectionPool.commit(commitConsole));
            assertEquals(1, count(observer, "tx_innodb"));

            long rollbackConsole = nextConsoleId();
            ConnectInfo rollingBack = registerBound(port, rollbackConsole);
            execute(rollingBack.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('rolled-back')");
            assertEquals(1, count(observer, "tx_innodb"));
            assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK, ConnectionPool.rollback(rollbackConsole));
            assertEquals(1, count(observer, "tx_innodb"));
        } finally {
            ConnectionPool.releaseAll(true);
        }
    }

    @ParameterizedTest(name = "MySQL MyISAM rollback semantics on port {0}")
    @ValueSource(ints = {3357, 3380})
    void myIsamChangesRemainVisibleAfterRollback(int port) throws Exception {
        requireMysqlAvailable(port);
        prepareSchema(port);
        long consoleId = nextConsoleId();
        try (Connection observer = open(port)) {
            ConnectInfo bound = registerBound(port, consoleId);
            execute(bound.getConnection(), "INSERT INTO tx_myisam(val) VALUES ('not-transactional')");
            assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK, ConnectionPool.rollback(consoleId));
            assertEquals(1, count(observer, "tx_myisam"));
        } finally {
            ConnectionPool.release(consoleId, true);
        }
    }

    @ParameterizedTest(name = "MySQL blocks implicit-commit DDL on port {0}")
    @ValueSource(ints = {3357, 3380})
    void policyBlocksDdlBeforeImplicitCommitAndKeepsConsolesIsolated(int port) throws Exception {
        requireMysqlAvailable(port);
        prepareSchema(port);
        long ddlConsoleId = nextConsoleId();
        long isolatedConsoleId = nextConsoleId();
        try (Connection observer = open(port)) {
            ConnectInfo ddlConsole = registerBound(port, ddlConsoleId);
            ConnectInfo isolatedConsole = registerBound(port, isolatedConsoleId);
            execute(ddlConsole.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('before-blocked-ddl')");
            execute(isolatedConsole.getConnection(), "INSERT INTO tx_innodb(val) VALUES ('isolated-console')");
            assertEquals(0, count(observer, "tx_innodb"));

            Chat2DBContext.putContext(ddlConsole);
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> Chat2DBContext.beforeExecute(executionPlan(
                            "CREATE TABLE tx_ddl_marker(id INT PRIMARY KEY) ENGINE=InnoDB")));

            assertEquals("transaction.implicitCommit.blocked", exception.getCode());
            assertFalse(tableExists(observer, "tx_ddl_marker"));
            assertEquals(0, count(observer, "tx_innodb"));

            assertEquals(ConnectionPool.TransactionOutcome.COMMITTED, ConnectionPool.commit(ddlConsoleId));
            assertEquals(1, countWhereVal(observer, "tx_innodb", "before-blocked-ddl"));
            assertEquals(0, countWhereVal(observer, "tx_innodb", "isolated-console"));

            assertEquals(ConnectionPool.TransactionOutcome.ROLLED_BACK, ConnectionPool.rollback(isolatedConsoleId));
            assertEquals(1, countWhereVal(observer, "tx_innodb", "before-blocked-ddl"));
            assertEquals(0, countWhereVal(observer, "tx_innodb", "isolated-console"));
        } finally {
            Chat2DBContext.removeContext();
            ConnectionPool.release(ddlConsoleId, true);
            ConnectionPool.release(isolatedConsoleId, true);
        }
    }

    private static ConnectInfo registerBound(int port, long consoleId) throws SQLException {
        Connection connection = open(port);
        connection.setAutoCommit(false);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDbType("MYSQL");
        connectInfo.setDatabaseName("c2d_tx_test");
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        assertTrue(ConnectionPool.registerIfAbsent(
                consoleId,
                connectInfo,
                TransactionIsolationLevel.DEFAULT,
                List.of(TransactionIsolationLevel.DEFAULT)
        ));
        return connectInfo;
    }

    private static SqlExecutionPlan executionPlan(String sql) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        SqlExecutionContext context = new SqlExecutionContext(
                connectInfo.getDataSourceId(),
                connectInfo.getDbType(),
                connectInfo.getDatabaseName(),
                connectInfo.getSchemaName(),
                null,
                sql,
                SqlExecutionOperation.EXECUTE,
                null
        );
        return new SqlExecutionPlan(context, sql, null, "mysql-manual-transaction-integration-test");
    }

    private static void prepareSchema(int port) throws SQLException {
        try (Connection connection = open(port); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tx_ddl_marker");
            statement.execute("CREATE TABLE IF NOT EXISTS tx_innodb "
                    + "(id BIGINT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE IF NOT EXISTS tx_myisam "
                    + "(id BIGINT AUTO_INCREMENT PRIMARY KEY, val VARCHAR(255)) ENGINE=MyISAM");
            statement.execute("TRUNCATE TABLE tx_innodb");
            statement.execute("TRUNCATE TABLE tx_myisam");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int countWhereVal(Connection connection, String table, String val) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE val = ?")) {
            statement.setString(1, val);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(
                "c2d_tx_test", null, table, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private static Connection open(int port) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:" + port
                        + "/c2d_tx_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "root",
                "chat2db"
        );
    }

    private static void requireMysqlAvailable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        } catch (Exception e) {
            String message = "MySQL test fixture is not running on loopback port " + port;
            if (Boolean.getBoolean(REQUIRE_MYSQL_FIXTURE_PROPERTY)) {
                fail(message, e);
            }
            assumeTrue(false, message);
        }
    }

    private static long nextConsoleId() {
        return CONSOLE_IDS.incrementAndGet();
    }
}
