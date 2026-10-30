package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chunking and parameter-binding semantics of the parameterized batch used by the import write path. */
class DefaultSQLExecutorParameterizedBatchTest {

    private static final String INSERT_ONE = "INSERT INTO records VALUES (?)";

    @Test
    void splitsParameterRowsIntoOnePreparedStatementPerChunk() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_chunk_split;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            RecordingListener listener = new RecordingListener();

            DefaultSQLExecutor.getInstance().executeParameterizedBatch(connection, INSERT_ONE,
                    integerRows(1200), listener, null, 500);

            assertEquals(1200, countRecords(connection));
            assertEquals(3, listener.created.get(), "1200 rows at chunk size 500 must use three statements");
            assertEquals(listener.created.get(), listener.closed.get(), "every statement must be closed");
        }
    }

    @Test
    void reportsStoredRowsAcrossChunks() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_counts;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);

            long stored = DefaultSQLExecutor.getInstance().executeParameterizedBatchWithCounts(connection,
                    INSERT_ONE, integerRows(1200), null, null, 500);

            assertEquals(1200L, stored);
            assertEquals(1200, countRecords(connection));
        }
    }

    @Test
    void ignoredRowsAreNotCountedAsStored() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_ignore;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO records VALUES (1)");
            }

            long stored = DefaultSQLExecutor.getInstance().executeParameterizedBatchWithCounts(connection,
                    "INSERT IGNORE INTO records VALUES (?)", integerRows(3), null, null, 500);

            assertEquals(2L, stored, "the duplicate key is skipped by the database, not stored");
            assertEquals(3, countRecords(connection));
        }
    }

    @Test
    void defaultChunkSizeIsUsedWhenNotSpecified() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_chunk_default;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            RecordingListener listener = new RecordingListener();
            int rows = DefaultSQLExecutor.DEFAULT_BATCH_CHUNK_SIZE + 1;

            DefaultSQLExecutor.getInstance().executeParameterizedBatch(connection, INSERT_ONE,
                    integerRows(rows), listener, null);

            assertEquals(rows, countRecords(connection));
            assertEquals(2, listener.created.get());
            assertEquals(2, listener.closed.get());
        }
    }

    @Test
    void bindsValuesByRuntimeType() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_types;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE typed_records ("
                        + "id INT PRIMARY KEY, null_value VARCHAR(32), decimal_value DECIMAL(20,4),"
                        + " long_value BIGINT, int_value INT, short_value SMALLINT, byte_value TINYINT,"
                        + " bool_value BOOLEAN, double_value DOUBLE, float_value REAL,"
                        + " sql_date DATE, sql_time TIME, sql_timestamp TIMESTAMP,"
                        + " local_date DATE, local_time TIME, local_datetime TIMESTAMP,"
                        + " bytes_value VARBINARY(64), string_value VARCHAR(64))");
            }
            java.sql.Date sqlDate = java.sql.Date.valueOf("2024-01-02");
            java.sql.Time sqlTime = java.sql.Time.valueOf("03:04:05");
            Timestamp sqlTimestamp = Timestamp.valueOf("2024-01-02 03:04:05");
            LocalDate localDate = LocalDate.of(2024, 1, 2);
            LocalTime localTime = LocalTime.of(3, 4, 5);
            LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
            byte[] bytes = {1, 2, 3};
            List<Object> row = Arrays.asList(1, null, new BigDecimal("12345.6789"), 9000000000L, 42, (short) 7,
                    (byte) 3, true, 1.5d, 2.5f, sqlDate, sqlTime, sqlTimestamp, localDate, localTime,
                    localDateTime, bytes, "hello");

            DefaultSQLExecutor.getInstance().executeParameterizedBatch(connection,
                    "INSERT INTO typed_records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Collections.singletonList(row), null, null);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT * FROM typed_records")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("id"));
                assertNull(resultSet.getString("null_value"));
                assertTrue(resultSet.wasNull(), "a null parameter must be stored as SQL NULL");
                assertEquals(new BigDecimal("12345.6789"), resultSet.getBigDecimal("decimal_value"));
                assertEquals(9000000000L, resultSet.getLong("long_value"));
                assertEquals(42, resultSet.getInt("int_value"));
                assertEquals((short) 7, resultSet.getShort("short_value"));
                assertEquals((byte) 3, resultSet.getByte("byte_value"));
                assertTrue(resultSet.getBoolean("bool_value"));
                assertEquals(1.5d, resultSet.getDouble("double_value"));
                assertEquals(2.5f, resultSet.getFloat("float_value"));
                assertEquals(sqlDate, resultSet.getDate("sql_date"));
                assertEquals(sqlTime, resultSet.getTime("sql_time"));
                assertEquals(sqlTimestamp, resultSet.getTimestamp("sql_timestamp"));
                assertEquals(localDate, resultSet.getObject("local_date", LocalDate.class));
                assertEquals(localTime, resultSet.getObject("local_time", LocalTime.class));
                assertEquals(localDateTime, resultSet.getObject("local_datetime", LocalDateTime.class));
                assertArrayEquals(bytes, resultSet.getBytes("bytes_value"));
                assertEquals("hello", resultSet.getString("string_value"));
            }
        }
    }

    @Test
    void cancellationBetweenChunksStopsFurtherWrites() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_chunk_cancel;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            RecordingListener listener = new RecordingListener();
            AtomicInteger checks = new AtomicInteger();
            Runnable cancellingChecker = () -> {
                if (checks.incrementAndGet() > 2) {
                    throw new IllegalStateException("cancelled");
                }
            };

            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    DefaultSQLExecutor.getInstance().executeParameterizedBatch(connection, INSERT_ONE,
                            integerRows(1500), listener, cancellingChecker, 500));

            assertEquals("cancelled", failure.getMessage());
            assertTrue(countRecords(connection) < 1500, "cancellation must stop before every chunk is written");
            assertEquals(1, listener.created.get(), "only the first chunk may be executed");
            assertEquals(1, listener.closed.get(), "the executed statement must still be closed");
        }
    }

    @Test
    void emptyOrNullRowsTouchNothing() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_empty;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            RecordingListener listener = new RecordingListener();
            DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

            executor.executeParameterizedBatch(connection, INSERT_ONE, List.of(), listener, null, 500);
            executor.executeParameterizedBatch(connection, INSERT_ONE, null, listener, null, 500);
            executor.executeParameterizedBatch(connection, INSERT_ONE, null, listener, null);

            assertEquals(0, countRecords(connection));
            assertEquals(0, listener.created.get());
            assertEquals(0, listener.closed.get());
        }
    }

    @Test
    void rejectsNonPositiveChunkSize() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_size_guard;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);
            DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

            assertThrows(IllegalArgumentException.class, () ->
                    executor.executeParameterizedBatch(connection, INSERT_ONE, integerRows(1), null, null, 0));
            assertThrows(IllegalArgumentException.class, () ->
                    executor.executeParameterizedBatch(connection, INSERT_ONE, integerRows(1), null, null, -1));

            assertEquals(0, countRecords(connection));
        }
    }

    @Test
    void parameterCountMismatchIsReportedInsteadOfTruncated() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_mismatch;DB_CLOSE_DELAY=-1")) {
            createLabeledRecordsTable(connection);
            DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();

            assertThrows(RuntimeException.class, () -> executor.executeParameterizedBatch(connection,
                    "INSERT INTO records (id, label) VALUES (?,?)",
                    Collections.singletonList(Collections.singletonList((Object) 1)), null, null));

            assertThrows(RuntimeException.class, () -> executor.executeParameterizedBatch(connection,
                    "INSERT INTO records (id) VALUES (?)",
                    Collections.singletonList(Arrays.asList(2, "extra")), null, null));

            assertEquals(0, countRecords(connection),
                    "a parameter count mismatch must fail instead of writing partial rows");
        }
    }

    @Test
    void doesNotControlTransactions() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_caller_owned;DB_CLOSE_DELAY=-1")) {
            createRecordsTable(connection);

            DefaultSQLExecutor.getInstance().executeParameterizedBatch(withoutTransactionControl(connection),
                    INSERT_ONE, integerRows(3), null, null, 2);

            assertEquals(3, countRecords(connection));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void callerOwnedTransactionKeepsAutoCommitDisabled() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:param_batch_caller_owned_rollback")) {
            createRecordsTable(connection);
            connection.setAutoCommit(false);

            DefaultSQLExecutor.getInstance().executeParameterizedBatch(connection, INSERT_ONE,
                    integerRows(3), null, null, 2);

            assertFalse(connection.getAutoCommit());
            connection.rollback();
            assertEquals(0, countRecords(connection));
        }
    }

    private static Connection withoutTransactionControl(Connection connection) {
        return (Connection) Proxy.newProxyInstance(DefaultSQLExecutorParameterizedBatchTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) {
                        throw new AssertionError(
                                "Parameterized batch must not control transactions: " + method.getName());
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static List<List<Object>> integerRows(int rows) {
        List<List<Object>> parameterRows = new ArrayList<>(rows);
        for (int index = 1; index <= rows; index++) {
            parameterRows.add(Collections.singletonList((Object) index));
        }
        return parameterRows;
    }

    private static void createRecordsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (id INT PRIMARY KEY)");
        }
    }

    private static void createLabeledRecordsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (id INT PRIMARY KEY, label VARCHAR(32))");
        }
    }

    private static int countRecords(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM records")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static final class RecordingListener implements ISqlExecutionStatementListener {
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
}
