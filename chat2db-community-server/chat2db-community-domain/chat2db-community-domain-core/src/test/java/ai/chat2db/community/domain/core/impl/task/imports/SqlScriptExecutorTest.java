package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.*;
import ai.chat2db.community.domain.api.service.task.*;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptExecutorTest {
    private ai.chat2db.spi.IPlugin previousPlugin;
    private Connection connection;
    private RecordingContext context;
    private SqlScriptExecutor executor;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:script_" + System.nanoTime() + ";MODE=MySQL");
        connection.createStatement().execute("CREATE TABLE test_rows (id INT PRIMARY KEY)");
        var config = new ai.chat2db.community.domain.api.config.DBConfig();
        config.setDbType("H2");
        config.setDefaultDriverConfig(new ai.chat2db.community.domain.api.config.DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put("H2", new ai.chat2db.spi.IPlugin() {
            public ai.chat2db.community.domain.api.config.DBConfig getDBConfig() { return config; }
            public ai.chat2db.spi.IDbMetaData getDbMetaData() { return new ai.chat2db.spi.DefaultMetaService(); }
        });
        ConnectInfo info = new ConnectInfo();
        info.setDbType("H2");
        info.setConnection(connection);
        Chat2DBContext.putContext(info);
        context = new RecordingContext();
        executor = new SqlScriptExecutor(context);
    }
    @AfterEach void cleanup() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) Chat2DBContext.PLUGIN_MAP.remove("H2");
        else Chat2DBContext.PLUGIN_MAP.put("H2", previousPlugin);
        connection.close();
    }

    @Test void multiValueInsertReportsRowsRatherThanStatementCount() {
        executor.executeBatch(List.of("INSERT INTO test_rows VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9),(10)"));
        executor.reportSummary();
        assertEquals(10L, context.events.get(0).details.get("affectedRows"));
        assertEquals(1, context.events.get(0).details.get("statementCount"));
        assertEquals(10L, context.last().details.get("affectedRows"));
        assertEquals(1L, context.last().details.get("statementCount"));
    }

    @Test void reportsEveryActualChunkWithoutThrottlingAndKeepsSqlInOrder() {
        var sqls = IntStream.rangeClosed(1, 1001).mapToObj(i -> "INSERT INTO test_rows VALUES (" + i + ")").toList();
        executor.executeBatch(sqls);
        executor.reportSummary();
        assertEquals(List.of(500, 500, 1), context.events.stream().filter(e -> e.code.equals("BATCH_EXECUTED"))
                .map(e -> e.details.get("statementCount")).toList());
        assertEquals(sqls, context.events.stream().filter(e -> e.code.equals("BATCH_EXECUTED"))
                .flatMap(e -> ((List<?>) e.details.get("statements")).stream()).toList());
        assertEquals(3, context.last().details.get("batchCount"));
        assertEquals(1001L, context.last().details.get("affectedRows"));
    }

    @Test void mixedStatementsPreserveExecutionOrderAndDmlCounts() {
        executor.executeBatch(List.of("INSERT INTO test_rows VALUES (1),(2)",
                "UPDATE test_rows SET id=3 WHERE id=2", "DELETE FROM test_rows WHERE id=1"));
        executor.reportSummary();
        assertEquals(3, context.last().details.get("batchCount"));
        assertEquals(3L, context.last().details.get("statementCount"));
        assertEquals(4L, context.last().details.get("affectedRows"));
    }

    @Test void unknownCountsAreNeverAssumedToBeOneRow() {
        executor.started(List.of("INSERT many", "INSERT other", "INSERT known"));
        executor.completed(List.of("INSERT many", "INSERT other", "INSERT known"),
                new int[]{Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO, 10}, true, 3);
        executor.reportSummary();
        assertEquals(10L, context.last().details.get("affectedRows"));
        assertEquals(2L, context.last().details.get("unknownStatements"));
    }

    @Test void partialFailureKeepsEarlierBatchAndReportsDriverResults() throws Exception {
        var sqls = new ArrayList<>(IntStream.rangeClosed(1, 500)
                .mapToObj(i -> "INSERT INTO test_rows VALUES (" + i + ")").toList());
        sqls.add("INSERT INTO test_rows VALUES (501)");
        sqls.add("INSERT INTO test_rows VALUES (1)");
        sqls.add("INSERT INTO test_rows VALUES (502)");
        assertThrows(TaskExecutionException.class, () -> executor.executeBatch(sqls));
        assertEquals("IMPORT_BATCH_FAILED", context.last().code);
        assertEquals(2L, context.last().details.get("affectedRows"));
        assertEquals(1, context.last().details.get("failedStatements"));
        assertEquals(502, rowCount());
        assertFalse(context.events.stream().anyMatch(e -> e.code.equals("IMPORT_SUMMARY")));
    }

    @Test void cancellationAfterFirstChunkDoesNotSubmitSecondChunk() throws Exception {
        context.cancelAfterBatch = true;
        assertThrows(TaskCancelledException.class, () -> executor.executeBatch(IntStream.rangeClosed(1, 1001)
                .mapToObj(i -> "INSERT INTO test_rows VALUES (" + i + ")").toList()));
        assertEquals(500, rowCount());
        assertEquals(1, context.events.size());
        assertEquals("BATCH_EXECUTED", context.last().code);
    }

    @Test void missingPartialResultsRemainUnknownRatherThanCompleted() {
        executor.started(List.of("a", "b", "c"));
        executor.completed(List.of("a", "b", "c"), new int[]{7}, false, 1);
        assertEquals(1, context.last().details.get("completedStatements"));
        assertEquals(2, context.last().details.get("unknownStatements"));
        assertEquals(7L, context.last().details.get("affectedRows"));
    }

    @Test void importerReportsOneSummaryWithConfiguredEncoding(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var file = java.nio.file.Files.writeString(dir.resolve("script.sql"),
                "INSERT INTO test_rows VALUES (1),(2),(3);\nUPDATE test_rows SET id=4 WHERE id=3;\n");
        var options = new SqlImportOptions();
        options.setEncoding("UTF-8");
        new ai.chat2db.community.domain.core.impl.task.imports.sql.SQLImporter().run(
                ImportTaskSpec.builder().sourceFile(file.toString()).sqlImportOptions(
                        options).build(), context);
        assertEquals(1, context.events.stream().filter(e -> e.code.equals("IMPORT_SUMMARY")).count());
        assertEquals(2L, context.last().details.get("statementCount"));
        assertEquals(4L, context.last().details.get("affectedRows"));
        assertEquals(3, rowCount());
    }

    private int rowCount() throws Exception {
        try (var s = connection.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM test_rows")) {
            r.next(); return r.getInt(1);
        }
    }
    private record Event(String code, Map<String,Object> details) { }
    private static final class RecordingContext implements TaskExecutionContext {
        final List<Event> events = new ArrayList<>();
        boolean cancelAfterBatch;
        Event last() { return events.get(events.size()-1); }
        public void reportProgress(int p, String s, String m) { }
        public void logInfo(String c, String m) { logInfo(c,m,Map.of()); }
        public void logInfo(String c, String m, Map<String,Object> d) { events.add(new Event(c,d)); }
        public void logWarn(String c, String m, Map<String,Object> d) { logInfo(c,m,d); }
        public void logError(String c, String m, Map<String,Object> d) { logInfo(c,m,d); }
        public void checkCancelled() {
            if (cancelAfterBatch && events.stream().anyMatch(e -> e.code.equals("BATCH_EXECUTED")))
                throw new TaskCancelledException();
        }
        public void registerCancelable(TaskCancelable r) { }
        public ArtifactDraft createArtifact(String d,String f,String m) { throw new UnsupportedOperationException(); }
        public void write(String s) { throw new UnsupportedOperationException(); }
        public void onStatementCreated(Statement s) { }
        public void onStatementClosed(Statement s) { }
    }
}
