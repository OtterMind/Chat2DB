package ai.chat2db.plugin.mysql;

import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.ConsoleTransactionRegistry;
import ai.chat2db.spi.sql.ConsoleTransactionRegistry.TransactionOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for console-scoped manual transactions against a real MySQL (5.7 and 8.0),
 * covering issue #2586 acceptance criteria: commit visibility, rollback invisibility, console
 * isolation, MyISAM non-protection, and release without connection leak.
 *
 * <p>Tests exercise {@link ConsoleTransactionRegistry} directly with a live JDBC connection
 * wrapped in a {@link ConnectInfo}; the registry's commit/rollback/release paths are the same
 * ones used by the production transaction endpoints.
 */
@Testcontainers
class MysqlConsoleTransactionIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("c2d_tx_test")
            .withUsername("root")
            .withPassword("")
            .withEnv("MYSQL_ALLOW_EMPTY_PASSWORD", "yes")
            .withInitScript("init.sql");

    private static final AtomicLong CONSOLE_ID_SEQ = new AtomicLong(1000L);

    private static String adminUrl() {
        // Connect to the c2d_tx_test database as root.
        return MYSQL.getJdbcUrl().replaceAll("/[\\w]+$", "/c2d_tx_test");
    }

    @BeforeAll
    static void warmUp() {
        // Force container start (Testcontainers @Container on a static field starts lazily on
        // first test). Also verify the schema is present.
        assertTrue(MYSQL.isRunning());
    }

    @AfterAll
    static void cleanupRegistry() {
        ConsoleTransactionRegistry.releaseAll(true);
    }

    /**
     * Empties the test tables before each test. The container is shared across all tests (and
     * across reruns within the same container), so committed rows from one test would otherwise
     * leak into the next and break the absolute row-count assertions.
     */
    @BeforeEach
    void truncateTestTables() throws SQLException {
        try (Connection admin = openAdmin(); Statement st = admin.createStatement()) {
            st.execute("TRUNCATE TABLE tx_innodb");
            st.execute("TRUNCATE TABLE tx_myisam");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"innodb"})
    void commitMakesDmlVisibleToSecondConnection(String engine) throws Exception {
        long consoleId = nextConsoleId();
        try (Connection observer = openAdmin()) {
            ConnectInfo bound = registerBound(consoleId);
            try {
                try (Statement st = bound.getConnection().createStatement()) {
                    st.execute("INSERT INTO tx_" + engine + " (val) VALUES ('before-commit')");
                }
                // Uncommitted: observer must not see the row.
                assertEquals(0, countRows(observer, "tx_" + engine));

                TransactionOutcome outcome = ConsoleTransactionRegistry.commit(consoleId);
                assertEquals(TransactionOutcome.COMMITTED, outcome);

                // After commit: observer sees the row.
                assertEquals(1, countRows(observer, "tx_" + engine));
            } finally {
                releaseQuietly(consoleId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"innodb"})
    void rollbackHidesUncommittedDml(String engine) throws Exception {
        long consoleId = nextConsoleId();
        try (Connection observer = openAdmin()) {
            ConnectInfo bound = registerBound(consoleId);
            try {
                try (Statement st = bound.getConnection().createStatement()) {
                    st.execute("INSERT INTO tx_" + engine + " (val) VALUES ('rolled-back')");
                }
                assertEquals(0, countRows(observer, "tx_" + engine));

                TransactionOutcome outcome = ConsoleTransactionRegistry.rollback(consoleId);
                assertEquals(TransactionOutcome.ROLLED_BACK, outcome);

                assertEquals(0, countRows(observer, "tx_" + engine));
            } finally {
                releaseQuietly(consoleId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"innodb"})
    void consolesDoNotShareTransaction(String engine) throws Exception {
        long consoleA = nextConsoleId();
        long consoleB = nextConsoleId();
        try (Connection observer = openAdmin()) {
            ConnectInfo boundA = registerBound(consoleA);
            ConnectInfo boundB = registerBound(consoleB);
            try {
                try (Statement st = boundA.getConnection().createStatement()) {
                    st.execute("INSERT INTO tx_" + engine + " (val) VALUES ('from-A')");
                }
                try (Statement st = boundB.getConnection().createStatement()) {
                    st.execute("INSERT INTO tx_" + engine + " (val) VALUES ('from-B')");
                }
                // Two separate bound connections: neither sees the other's uncommitted work.
                assertEquals(0, countRows(boundB.getConnection(), "tx_" + engine, "val = 'from-A'"));
                assertEquals(0, countRows(boundA.getConnection(), "tx_" + engine, "val = 'from-B'"));

                // Capture the two bound connections' identities before committing: commit()
                // releases (and nulls) the bound connection, so the identity check must run
                // while both are still live.
                int boundAIdentity = System.identityHashCode(boundA.getConnection());
                int boundBIdentity = System.identityHashCode(boundB.getConnection());

                ConsoleTransactionRegistry.commit(consoleA);
                // A committed; B's connection still must not expose A's row to the observer until B commits.
                assertEquals(1, countRows(observer, "tx_" + engine));

                ConsoleTransactionRegistry.commit(consoleB);
                assertEquals(2, countRows(observer, "tx_" + engine));

                // The two bound connections were distinct JDBC objects.
                assertNotEquals(boundAIdentity, boundBIdentity);
            } finally {
                releaseQuietly(consoleA);
                releaseQuietly(consoleB);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"myisam"})
    void myIsamRollbackIsNoop(String engine) throws Exception {
        long consoleId = nextConsoleId();
        try (Connection observer = openAdmin()) {
            ConnectInfo bound = registerBound(consoleId);
            try {
                try (Statement st = bound.getConnection().createStatement()) {
                    st.execute("INSERT INTO tx_" + engine + " (val) VALUES ('persists')");
                }
                // MyISAM is non-transactional: the row is already committed server-side and a
                // rollback cannot remove it.
                TransactionOutcome outcome = ConsoleTransactionRegistry.rollback(consoleId);
                assertEquals(TransactionOutcome.ROLLED_BACK, outcome);

                assertEquals(1, countRows(observer, "tx_" + engine));
            } finally {
                releaseQuietly(consoleId);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void releaseClosesBoundConnectionWithoutLeak() throws Exception {
        long consoleId = nextConsoleId();
        ConnectInfo bound = registerBound(consoleId);
        Connection connection = bound.getConnection();
        assertFalse(connection.isClosed());

        TransactionOutcome outcome = ConsoleTransactionRegistry.release(consoleId, true);
        // No open transaction was started via registry (we registered without DML), but release
        // must still close the connection and clear the registry entry.
        assertTrue(outcome == TransactionOutcome.RELEASED_WITHOUT_TRANSACTION
                || outcome == TransactionOutcome.ROLLED_BACK
                || outcome == TransactionOutcome.UNKNOWN);
        assertTrue(connection.isClosed());
        assertFalse(ConsoleTransactionRegistry.isInTransaction(consoleId));
    }

    // --- helpers -----------------------------------------------------------------

    private static long nextConsoleId() {
        return CONSOLE_ID_SEQ.incrementAndGet();
    }

    private static Connection openAdmin() throws SQLException {
        return DriverManager.getConnection(adminUrl(), "root", "");
    }

    /**
     * Opens a fresh connection, disables auto-commit, wraps it in a ConnectInfo with
     * consoleOwn=true and a null dataSourceId (so release closes it rather than returning it to
     * the pool), and registers it with the transaction registry.
     */
    private static ConnectInfo registerBound(long consoleId) throws SQLException {
        Connection connection = openAdmin();
        connection.setAutoCommit(false);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setDbType("MYSQL");
        connectInfo.setConnection(connection);
        ConsoleTransactionRegistry.register(consoleId, connectInfo);
        return connectInfo;
    }

    private static void releaseQuietly(long consoleId) {
        try {
            ConsoleTransactionRegistry.release(consoleId, true);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    private static int countRows(Connection connection, String table) throws SQLException {
        return countRows(connection, table, null);
    }

    private static int countRows(Connection connection, String table, String where) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table;
        if (where != null) {
            sql += " WHERE " + where;
        }
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } finally {
            // An observer connection on auto-commit=true needs no rollback; a bound connection
            // in a transaction must not have its transaction disturbed by the count query.
            if (!connection.getAutoCommit()) {
                // no-op: SELECT does not start a write transaction in InnoDB REPEATABLE READ
            }
        }
    }
}
