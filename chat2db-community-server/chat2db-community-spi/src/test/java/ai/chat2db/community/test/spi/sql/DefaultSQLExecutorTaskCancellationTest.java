package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSQLExecutorTaskCancellationTest {

    @Test
    void legacyBatchOverloadStillExecutesEveryStatement() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:legacy_batch;DB_CLOSE_DELAY=-1")) {
            createTable(connection);

            DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                    "INSERT INTO records VALUES (1)",
                    "INSERT INTO records VALUES (2)"));

            assertEquals(2, countRows(connection));
        }
    }

    @Test
    void cancellationBeforeNextPrepareStopsRemainingBatch() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:cancel_batch;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            AtomicInteger checks = new AtomicInteger();
            CountingStatementListener listener = new CountingStatementListener();

            assertThrows(CancellationException.class,
                    () -> DefaultSQLExecutor.getInstance().executeBatchInsert(connection, List.of(
                                    "INSERT INTO records VALUES (1)",
                                    "INSERT INTO records VALUES (2)"),
                            listener, () -> {
                                if (checks.incrementAndGet() >= 3) {
                                    throw new CancellationException("cancelled between statements");
                                }
                            }));

            assertEquals(0, countRows(connection));
            assertEquals(1, listener.created.get());
            assertEquals(1, listener.closed.get());
        }
    }

    @Test
    void stopCancelsExecutingStatementAndPreventsNextStatement() throws Exception {
        AtomicInteger prepareCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        AtomicInteger cancelCalls = new AtomicInteger();
        CountDownLatch executeStarted = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        PreparedStatement statement = blockingStatement(executeCalls, cancelCalls, executeStarted, cancelled);
        Connection connection = connection(statement, prepareCalls);
        TestCancellation cancellation = new TestCancellation();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            var execution = executor.submit(() -> DefaultSQLExecutor.getInstance().executeBatchInsert(
                    connection, List.of("first", "second"), cancellation, cancellation::checkCancelled));
            assertTrue(executeStarted.await(5, TimeUnit.SECONDS), "statement did not start executing");

            assertTrue(cancellation.stop());

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(1, prepareCalls.get());
            assertEquals(1, executeCalls.get());
            assertEquals(1, cancelCalls.get());
        } finally {
            cancelled.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void statementCloseNotificationFollowsJdbcClose() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:close_notification;DB_CLOSE_DELAY=-1")) {
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

            assertThrows(SQLException.class, () -> DefaultSQLExecutor.getInstance()
                    .execute(connection, "INSERT INTO records VALUES (1)", listener, null));

            assertEquals(1, notifications.get());
            assertTrue(statementClosed.get());
        }
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

    private static Connection connection(PreparedStatement statement, AtomicInteger prepareCalls) {
        return (Connection) Proxy.newProxyInstance(
                DefaultSQLExecutorTaskCancellationTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        prepareCalls.incrementAndGet();
                        return statement;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement blockingStatement(
            AtomicInteger executeCalls, AtomicInteger cancelCalls,
            CountDownLatch executeStarted, CountDownLatch cancelled) {
        return (PreparedStatement) Proxy.newProxyInstance(
                DefaultSQLExecutorTaskCancellationTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        executeCalls.incrementAndGet();
                        executeStarted.countDown();
                        if (!cancelled.await(5, TimeUnit.SECONDS)) {
                            throw new SQLException("timed out waiting for cancellation");
                        }
                        throw new SQLException("statement cancelled");
                    }
                    if ("cancel".equals(method.getName())) {
                        cancelCalls.incrementAndGet();
                        cancelled.countDown();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
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
