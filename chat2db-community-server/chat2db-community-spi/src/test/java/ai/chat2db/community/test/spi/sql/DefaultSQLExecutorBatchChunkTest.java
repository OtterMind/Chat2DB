package ai.chat2db.community.test.spi.sql;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chunking semantics of the chunked JDBC batch used by the import write path. */
class DefaultSQLExecutorBatchChunkTest {

    @Test
    void splitsStatementsIntoSingleStatementPerChunk() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch_chunk_split;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            List<String> sqls = inserts(1200);
            RecordingListener listener = new RecordingListener();

            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection, sqls, listener, null, 500);

            assertEquals(1200, countRows(connection));
            assertEquals(3, listener.created.get(), "1200 rows at chunk size 500 must use three statements");
            assertEquals(listener.created.get(), listener.closed.get(), "every statement must be closed");
        }
    }

    @Test
    void defaultChunkSizeIsUsedWhenNotSpecified() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch_chunk_default;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            RecordingListener listener = new RecordingListener();
            int rows = DefaultSQLExecutor.DEFAULT_BATCH_CHUNK_SIZE + 1;

            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection, inserts(rows), listener, null);

            assertEquals(rows, countRows(connection));
            assertEquals(2, listener.created.get());
        }
    }

    @Test
    void emptyBatchTouchesNothing() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch_chunk_empty;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            RecordingListener listener = new RecordingListener();

            DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(connection, List.of(), listener, null, 500);

            assertEquals(0, listener.created.get());
            assertEquals(0, countRows(connection));
        }
    }

    @Test
    void cancellationBetweenChunksStopsFurtherWrites() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch_chunk_cancel;DB_CLOSE_DELAY=-1")) {
            createTable(connection);
            AtomicInteger checks = new AtomicInteger();
            Runnable cancellingChecker = () -> {
                if (checks.incrementAndGet() > 2) {
                    throw new IllegalStateException("cancelled");
                }
            };

            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(
                            connection, inserts(1500), null, cancellingChecker, 500));

            assertEquals("cancelled", failure.getMessage());
            assertTrue(countRows(connection) < 1500, "cancellation must stop before every chunk is written");
        }
    }

    @Test
    void rejectsNonPositiveChunkSize() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:batch_chunk_guard;DB_CLOSE_DELAY=-1")) {
            assertThrows(IllegalArgumentException.class, () ->
                    DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(
                            connection, inserts(1), null, null, 0));
        }
    }

    private static List<String> inserts(int rows) {
        List<String> sqls = new ArrayList<>(rows);
        for (int index = 1; index <= rows; index++) {
            sqls.add("INSERT INTO records VALUES (" + index + ")");
        }
        return sqls;
    }

    private static void createTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (id INT PRIMARY KEY)");
        }
    }

    private static int countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT COUNT(*) FROM records")) {
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