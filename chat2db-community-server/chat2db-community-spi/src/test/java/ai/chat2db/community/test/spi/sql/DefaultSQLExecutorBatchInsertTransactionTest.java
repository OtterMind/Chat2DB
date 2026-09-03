package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSQLExecutorBatchInsertTransactionTest {

    @Test
    void commitsOwnedTransactionAndRestoresAutoCommitOnSuccess() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_success;DB_CLOSE_DELAY=-1";
        try (Connection delegate = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(delegate);
            List<String> lifecycleCalls = new ArrayList<>();
            Connection connection = lifecycleConnection(delegate, false, lifecycleCalls, new AtomicBoolean());

            DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                    "INSERT INTO records VALUES (1)",
                    "INSERT INTO records VALUES (2)"));

            assertTrue(connection.getAutoCommit());
            assertEquals(2, countRows(observer));
            assertEquals(List.of("setAutoCommit:false", "commit", "setAutoCommit:true"), lifecycleCalls);
        }
    }

    @Test
    void rollsBackOwnedTransactionOnSqlException() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_sql_exception;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(connection);

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                            "INSERT INTO records VALUES (1)",
                            "INSERT INTO records VALUES (1)")));

            assertInstanceOf(SQLException.class, thrown.getCause());
            assertTrue(connection.getAutoCommit());
            assertEquals(0, countRows(observer));
        }
    }

    @Test
    void rollsBackOwnedTransactionOnRuntimeException() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_runtime_exception;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(connection);
            IllegalStateException listenerFailure = new IllegalStateException("listener failed");
            ISqlExecutionStatementListener listener = new ISqlExecutionStatementListener() {
                @Override
                public void onStatementCreated(Statement statement) {
                }

                @Override
                public void onStatementClosed(Statement statement) {
                    throw listenerFailure;
                }
            };

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection,
                            List.of("INSERT INTO records VALUES (1)"), listener, null));

            assertEquals(listenerFailure, thrown);
            assertTrue(connection.getAutoCommit());
            assertEquals(0, countRows(observer));
        }
    }

    @Test
    void rollsBackOwnedTransactionOnCancellation() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_cancellation;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(connection);
            AtomicInteger checks = new AtomicInteger();

            CancellationException thrown = assertThrows(CancellationException.class,
                    () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                                    "INSERT INTO records VALUES (1)",
                                    "INSERT INTO records VALUES (2)"),
                            null, () -> {
                                if (checks.incrementAndGet() >= 3) {
                                    throw new CancellationException("cancelled between statements");
                                }
                            }));

            assertEquals("cancelled between statements", thrown.getMessage());
            assertTrue(connection.getAutoCommit());
            assertEquals(0, countRows(observer));
        }
    }

    @Test
    void reportsRestoreFailureAndDiscardsConnection() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_restore_failure;DB_CLOSE_DELAY=-1";
        try (Connection delegate = DriverManager.getConnection(url)) {
            createTable(delegate);
            List<String> lifecycleCalls = new ArrayList<>();
            AtomicBoolean abortExecutorCompleted = new AtomicBoolean();
            Connection connection = lifecycleConnection(delegate, true, lifecycleCalls, abortExecutorCompleted);
            ConnectInfo connectInfo = putContext(connection);
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection,
                                List.of("INSERT INTO records VALUES (1)")));

                assertInstanceOf(SQLException.class, thrown.getCause());
                assertEquals("restore autoCommit failed", thrown.getCause().getMessage());
                assertEquals(List.of("setAutoCommit:false", "commit", "setAutoCommit:true", "abort"),
                        lifecycleCalls);
                assertTrue(abortExecutorCompleted.get());
                assertTrue(connection.isClosed());
                assertNull(connectInfo.getConnection());
            } finally {
                Chat2DBContext.removeContext();
            }
        }
    }

    @Test
    void commitFailureRollsBackAndDiscardsConnection() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_commit_failure;DB_CLOSE_DELAY=-1";
        try (Connection delegate = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(delegate);
            List<String> lifecycleCalls = new ArrayList<>();
            AtomicBoolean abortExecutorCompleted = new AtomicBoolean();
            Connection lifecycle = lifecycleConnection(delegate, false, lifecycleCalls, abortExecutorCompleted);
            Connection connection = commitFailureConnection(lifecycle);
            ConnectInfo connectInfo = putContext(connection);
            try {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                        () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection,
                                List.of("INSERT INTO records VALUES (1)")));

                assertInstanceOf(SQLException.class, thrown.getCause());
                assertEquals("commit failed", thrown.getCause().getMessage());
                assertEquals(List.of("setAutoCommit:false", "rollback", "abort"), lifecycleCalls);
                assertEquals(0, countRows(observer));
                assertTrue(abortExecutorCompleted.get());
                assertNull(connectInfo.getConnection());
            } finally {
                Chat2DBContext.removeContext();
            }
        }
    }

    @Test
    void leavesPreExistingTransactionOpenOnSuccess() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_caller_transaction_success;DB_CLOSE_DELAY=-1";
        try (Connection delegate = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(delegate);
            delegate.setAutoCommit(false);
            List<String> lifecycleCalls = new ArrayList<>();
            Connection connection = lifecycleConnection(delegate, false, lifecycleCalls, new AtomicBoolean());

            DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                    "INSERT INTO records VALUES (1)",
                    "INSERT INTO records VALUES (2)"));

            assertFalse(connection.getAutoCommit());
            assertEquals(0, countRows(observer));
            assertEquals(List.of(), lifecycleCalls);
            connection.commit();
            assertEquals(2, countRows(observer));
        }
    }

    @Test
    void leavesPreExistingTransactionUnresolvedOnFailure() throws Exception {
        String url = "jdbc:h2:mem:batch_insert_caller_transaction_failure;DB_CLOSE_DELAY=-1";
        try (Connection delegate = DriverManager.getConnection(url);
             Connection observer = DriverManager.getConnection(url)) {
            createTable(delegate);
            delegate.setAutoCommit(false);
            executeUpdate(delegate, "INSERT INTO records VALUES (1)");
            List<String> lifecycleCalls = new ArrayList<>();
            Connection connection = lifecycleConnection(delegate, false, lifecycleCalls, new AtomicBoolean());

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                            "INSERT INTO records VALUES (2)",
                            "INSERT INTO records VALUES (1)")));

            assertInstanceOf(SQLException.class, thrown.getCause());
            assertFalse(connection.getAutoCommit());
            assertEquals(0, countRows(observer));
            assertEquals(List.of(), lifecycleCalls);
            connection.commit();
            assertEquals(2, countRows(observer));
        }
    }

    private static void createTable(Connection connection) throws SQLException {
        executeUpdate(connection, "CREATE TABLE records(id INT PRIMARY KEY)");
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int countRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM records")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static ConnectInfo putContext(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
        return connectInfo;
    }

    private static Connection lifecycleConnection(
            Connection delegate, boolean failAutoCommitRestore, List<String> lifecycleCalls,
            AtomicBoolean abortExecutorCompleted) {
        AtomicBoolean autoCommitDisabled = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(
                DefaultSQLExecutorBatchInsertTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        boolean autoCommit = (Boolean) args[0];
                        lifecycleCalls.add("setAutoCommit:" + autoCommit);
                        if (autoCommit && failAutoCommitRestore && autoCommitDisabled.get()) {
                            throw new SQLException("restore autoCommit failed");
                        }
                        Object result = invoke(delegate, method, args);
                        if (!autoCommit) {
                            autoCommitDisabled.set(true);
                        }
                        return result;
                    }
                    if ("commit".equals(method.getName())) {
                        lifecycleCalls.add("commit");
                    }
                    if ("rollback".equals(method.getName())) {
                        lifecycleCalls.add("rollback");
                    }
                    if ("abort".equals(method.getName())) {
                        lifecycleCalls.add("abort");
                        AtomicBoolean taskCompleted = new AtomicBoolean();
                        ((Executor) args[0]).execute(() -> taskCompleted.set(true));
                        abortExecutorCompleted.set(taskCompleted.get());
                        delegate.close();
                        return null;
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Connection commitFailureConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                DefaultSQLExecutorBatchInsertTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName())) {
                        throw new SQLException("commit failed");
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Connection delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
