package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSQLExecutorJdbcBatchTest {

    @Test
    void batchExecutesEveryStatementWithoutTransactionControl() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_legacy_batch;DB_CLOSE_DELAY=-1")) {
            createTable(connection);

            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(withoutTransactionControl(connection), List.of(
                    "INSERT INTO records VALUES (1)",
                    "INSERT INTO records VALUES (2)"), null, null);

            assertEquals(2, countRows(connection));
        }
    }

    @Test
    void cancellationAfterExecutionKeepsCommittedRows() throws Exception {
        // The cancellation checker fires after the driver has executed the batch.
        List<String> sqls = new java.util.ArrayList<>();
        for (int value = 1; value <= 501; value++) {
            sqls.add("INSERT INTO records VALUES (" + value + ")");
        }
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_cancel_batch;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            AtomicInteger checks = new AtomicInteger();
            CountingStatementListener listener = new CountingStatementListener();

            assertThrows(CancellationException.class,
                    () -> DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection, sqls,
                            listener, () -> {
                                if (checks.incrementAndGet() >= 3) {
                                    throw new CancellationException("cancelled after execution");
                                }
                            }));

            assertEquals(501, countRows(connection));
            assertEquals(1, listener.created.get());
            assertEquals(1, listener.closed.get());
        }
    }

    @Test
    void stopCancelsExecutingJdbcBatch() throws Exception {
        List<String> sqls = new java.util.ArrayList<>();
        for (int value = 1; value <= 501; value++) {
            sqls.add("INSERT INTO records VALUES (" + value + ")");
        }
        AtomicInteger createCalls = new AtomicInteger();
        AtomicInteger cancelCalls = new AtomicInteger();
        CountDownLatch executeStarted = new CountDownLatch(1);
        TestCancellation cancellation = new TestCancellation();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection real = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_cancel_running;DB_CLOSE_DELAY=-1")) {
            createTable(real);
            Connection connection = (Connection) Proxy.newProxyInstance(
                    DefaultSQLExecutorJdbcBatchTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object value = method.invoke(real, args);
                        if ("createStatement".equals(method.getName()) && value instanceof Statement statement) {
                            createCalls.incrementAndGet();
                            value = blockingStatement(statement, cancelCalls, executeStarted);
                        }
                        return value;
                    });

            var execution = executor.submit(
                    () -> DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection, sqls,
                            cancellation, cancellation::checkCancelled));
            assertTrue(executeStarted.await(5, TimeUnit.SECONDS), "batch did not start executing");

            assertTrue(cancellation.stop());

            assertThrows(ExecutionException.class, () -> execution.get(10, TimeUnit.SECONDS));
            assertEquals(1, createCalls.get(), "the batch must use one statement");
            assertEquals(1, cancelCalls.get());
            assertEquals(0, countRows(real), "the cancelled statement was not executed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void statementCloseNotificationFollowsJdbcClose() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_close_notification;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO records VALUES (1)");
            }
            AtomicInteger notifications = new AtomicInteger();
            AtomicBoolean statementClosed = new AtomicBoolean();
            ISqlExecutionStatementListener listener = new ISqlExecutionStatementListener() {
                @Override
                public void onStatementCreated(Statement statement) {
                }

                @Override
                public void onStatementClosed(Statement statement) {
                    notifications.incrementAndGet();
                    try {
                        statementClosed.set(statement.isClosed());
                    } catch (SQLException e) {
                        throw new AssertionError(e);
                    }
                }
            };

            assertThrows(RuntimeException.class, () -> DefaultSQLExecutor.getInstance()
                    .executeJdbcBatchInsert(connection, List.of("INSERT INTO records VALUES (1)"), listener, null));

            assertEquals(1, notifications.get());
            assertTrue(statementClosed.get());
        }
    }

    @Test
    void callerOwnedTransactionKeepsAutoCommitDisabled() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_caller_owned")) {
            createTable(connection);
            connection.setAutoCommit(false);
            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(withoutTransactionControl(connection),
                    List.of("INSERT INTO records VALUES (1)"), null, null);
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            assertEquals(0, countRows(connection));
        }
    }

    @Test
    void failedBatchDoesNotRollbackCallerOwnedWork() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:jdbc_batch_caller_owned_failure")) {
            createTable(connection);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO records VALUES (99)");
            }
            assertThrows(RuntimeException.class,
                    () -> DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection,
                            List.of("INSERT INTO records VALUES (1)",
                                    "INSERT INTO records VALUES (1)"), null, null));
            assertFalse(connection.getAutoCommit());
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT COUNT(*) FROM records WHERE id = 99")) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));
            }
            connection.rollback();
        }
    }

    @Test
    void failedBatchKeepsRowsCommittedByTheDriver() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_batch_partial_failure")) {
            createTable(connection);

            assertThrows(RuntimeException.class,
                    () -> DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(
                            withoutTransactionControl(connection),
                            List.of("INSERT INTO records VALUES (1)", "INSERT INTO records VALUES (1)",
                                    "INSERT INTO records VALUES (2)"), null, null));

            // H2 continues a JDBC batch after this constraint violation while auto-commit is on.
            assertEquals(2, countRows(connection));
            assertTrue(connection.getAutoCommit());
        }
    }

    private static Connection withoutTransactionControl(Connection connection) {
        return (Connection) Proxy.newProxyInstance(DefaultSQLExecutorJdbcBatchTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("getAutoCommit", "setAutoCommit", "commit", "rollback").contains(method.getName())) {
                        throw new AssertionError("Batch execution must not control transactions: " + method.getName());
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records(id INT PRIMARY KEY)");
        }
    }

    private static int countRows(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM records")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static Statement blockingStatement(Statement real, AtomicInteger cancelCalls,
            CountDownLatch executeStarted) {
        CountDownLatch cancelled = new CountDownLatch(1);
        return (Statement) Proxy.newProxyInstance(
                DefaultSQLExecutorJdbcBatchTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if ("executeBatch".equals(method.getName())) {
                        executeStarted.countDown();
                        if (!cancelled.await(10, TimeUnit.SECONDS)) {
                            throw new SQLException("timed out waiting for cancellation");
                        }
                        throw new SQLException("statement cancelled");
                    }
                    if ("cancel".equals(method.getName())) {
                        cancelCalls.incrementAndGet();
                        cancelled.countDown();
                        return null;
                    }
                    return method.invoke(real, args);
                });
    }

    private static final class CountingStatementListener implements ISqlExecutionStatementListener {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public void onStatementCreated(Statement statement) {
            created.incrementAndGet();
        }

        @Override
        public void onStatementClosed(Statement statement) {
            closed.incrementAndGet();
        }
    }

    private static final class TestCancellation implements ISqlExecutionStatementListener {

        private final AtomicBoolean stopped = new AtomicBoolean();

        private final AtomicReference<Statement> statement = new AtomicReference<>();

        boolean stop() throws SQLException {
            if (!stopped.compareAndSet(false, true)) {
                return false;
            }
            Statement current = statement.get();
            if (current != null) {
                current.cancel();
            }
            return true;
        }

        void checkCancelled() {
            if (stopped.get()) {
                throw new CancellationException("cancelled");
            }
        }

        @Override
        public void onStatementCreated(Statement statement) {
            this.statement.set(statement);
        }

        @Override
        public void onStatementClosed(Statement statement) {
            this.statement.compareAndSet(statement, null);
        }
    }
}
