package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImportBatchTransactionsTest {
    private static final String INSERT = "INSERT INTO rows_for_retry VALUES (?)";

    @Test void defaultSequentialPathStopsAtTheFirstFailureAndReusesThePreparedStatement() throws Exception {
        try (Connection connection = database()) {
            AtomicInteger prepares = new AtomicInteger();
            Connection wrapped = proxy(connection, (name, args) -> {
                if (name.equals("prepareStatement")) prepares.incrementAndGet();
            });
            assertThrows(RuntimeException.class, () -> ImportBatchTransactions.executeSequentialParameterizedBatch(
                    wrapped, INSERT, List.of(List.of(1), List.of(1), List.of(2)), null, () -> { }));
            assertEquals(1, count(connection), "rows after the rejected row must never execute");
            assertEquals(1, prepares.get(), "typed parameters reuse the prepared statement");
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test void autoCommitResponseLossPreservesUnsafeCodeAndNeverReplaysTheCommittedRow() throws Exception {
        try (Connection connection = database();
                Connection observer = DriverManager.getConnection(connection.getMetaData().getURL())) {
            AtomicInteger writes = new AtomicInteger();
            Connection wrapped = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result;
                        try { result = method.invoke(connection, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        if (!(result instanceof java.sql.PreparedStatement statement)) return result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{java.sql.PreparedStatement.class}, (statementProxy, operation, parameters) -> {
                                    Object value;
                                    try { value = operation.invoke(statement, parameters); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                    if (operation.getName().equals("executeUpdate")) {
                                        writes.incrementAndGet();
                                        throw new SQLException("response lost after auto-commit", "08006");
                                    }
                                    return value;
                                });
                    });
            var failure = assertThrows(ai.chat2db.community.domain.api.model.task.TaskExecutionException.class,
                    () -> ImportBatchTransactions.executeSequentialParameterizedBatch(wrapped, INSERT,
                            List.of(List.of(1), List.of(2)), null, () -> { }));
            assertEquals(ImportBatchTransactions.UNSAFE_REPLAY_CODE, failure.getCode());
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertEquals(1, writes.get());
            assertEquals(1, count(observer), "the server really committed despite the failed response");
            assertTrue(connection.isClosed());
        }
    }

    @Test void successfulOwnTransactionCommitsAndRestoresAutoCommit() throws Exception {
        try (Connection connection = database()) {
            assertEquals(2, execute(connection, List.of(List.of(1), List.of(2))));
            assertTrue(connection.getAutoCommit());
            assertEquals(2, count(connection));
        }
    }

    @Test void callerTransactionRollsBackOnlyTheRejectedAttemptAndCanRetryRows() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("INSERT INTO rows_for_retry VALUES (99)");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(connection, List.of(List.of(1), List.of(1))));
            assertTrue(ImportBatchTransactions.canRetry(failure));
            assertFalse(connection.getAutoCommit());
            assertEquals(1, count(connection), "the caller's earlier row survives the savepoint rollback");
            assertEquals(1, execute(connection, List.of(List.of(1))));
            assertEquals(2, count(connection));
            connection.rollback();
            assertEquals(0, count(connection), "neither successful retry nor savepoint release commits the caller");
        }
    }

    @Test void missingSavepointStopsBeforeAnyBatchWrite() throws Exception {
        try (Connection connection = database()) {
            connection.setAutoCommit(false);
            AtomicInteger writes = new AtomicInteger();
            Connection wrapped = proxy(connection, (name, args) -> {
                if (name.equals("setSavepoint")) throw new SQLException("unsupported savepoint");
                if (name.equals("prepareStatement")) writes.incrementAndGet();
            });
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(wrapped, List.of(List.of(1))));
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertEquals(0, writes.get());
            assertFalse(connection.getAutoCommit());
            assertFalse(connection.isClosed(), "the caller's untouched transaction remains its own");
        }
    }

    @Test void rollbackFailureClosesWithoutEnablingAutoCommit() throws Exception {
        try (Connection connection = database()) {
            List<String> changes = new ArrayList<>();
            Connection wrapped = proxy(connection, (name, args) -> {
                if (name.equals("setAutoCommit")) changes.add("auto=" + args[0]);
                if (name.equals("rollback")) throw new SQLException("rollback unavailable");
            });
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(wrapped, List.of(List.of(1), List.of(1))));
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertEquals(List.of("auto=false"), changes, "restoring auto-commit could commit an unrolled-back prefix");
            assertTrue(connection.isClosed());
        }
    }

    @Test void commitFailureAfterServerCommitIsNeverReplayable() throws Exception {
        try (Connection connection = database()) {
            Connection wrapped = proxy(connection, (name, args) -> {
                if (name.equals("commit")) {
                    connection.commit();
                    throw new SQLException("reply lost after commit", "08006");
                }
            });
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(wrapped, List.of(List.of(1))));
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertTrue(connection.isClosed());
        }
    }

    @Test void restoreFailureAfterCommitClosesAndCannotReplay() throws Exception {
        try (Connection connection = database()) {
            Connection wrapped = proxy(connection, (name, args) -> {
                if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))
                    throw new SQLException("restore unavailable");
            });
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> execute(wrapped, List.of(List.of(1))));
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertTrue(connection.isClosed());
        }
    }

    @Test void cancellationAfterExecutionRollsBackBeforeCommit() throws Exception {
        try (Connection connection = database()) {
            AtomicBoolean cancelled = new AtomicBoolean();
            ISqlExecutionStatementListener listener = new ISqlExecutionStatementListener() {
                public void onStatementCreated(Statement statement) { }
                public void onStatementClosed(Statement statement) { cancelled.set(true); }
            };
            var failure = assertThrows(CancellationException.class,
                    () -> ImportBatchTransactions.executeParameterizedBatch(true, connection, INSERT,
                            List.of(List.of(1)), listener, () -> {
                                if (cancelled.get()) throw new CancellationException();
                            }));
            assertFalse(ImportBatchTransactions.canRetry(failure));
            assertEquals(0, count(connection));
            assertTrue(connection.getAutoCommit());
        }
    }

    private static Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:retry_" + System.nanoTime());
        connection.createStatement().execute("CREATE TABLE rows_for_retry (id INT PRIMARY KEY)");
        return connection;
    }

    private static long execute(Connection connection, List<List<Object>> rows) {
        return ImportBatchTransactions.executeParameterizedBatch(true, connection, INSERT, rows, null, () -> { });
    }

    private static int count(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM rows_for_retry")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static Connection proxy(Connection connection, Call before) {
        return (Connection) Proxy.newProxyInstance(ImportBatchTransactionsTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    before.run(method.getName(), args);
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private interface Call { void run(String name, Object[] args) throws SQLException; }
}
