package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.SqlImportOptions;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.sql.SQLImporter;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.plugin.postgresql.PostgreSQLPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Required opt-in fixture: run script/test/run-sql-batch-postgres-it.py; never reuse a user DB. */
class SQLScriptTransactionPostgreSQLIT {
    private static final String DB_TYPE = "POSTGRESQL";
    @TempDir Path directory;
    private Connection connection;
    private IPlugin previousPlugin;
    private String jdbcUrl;
    private String password;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = System.getenv("C2D_SCRIPT_TX_JDBC_URL");
        password = System.getenv("C2D_SCRIPT_TX_PASSWORD");
        assertNotNull(jdbcUrl, "Use the owned loopback fixture runner");
        assertTrue(jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/c2d_script_tx_[a-z0-9]+"));
        assertNotNull(password, "Owned fixture password is required");
        connection = connect();
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS C2D_SCRIPT_ROWS");
            statement.execute("CREATE TABLE C2D_SCRIPT_ROWS (ID INT PRIMARY KEY)");
        }
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new PostgreSQLPlugin());
        bindContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        else Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        if (connection != null) connection.close();
    }

    @Test
    void rollbackDiscardsRowsImportedFromARealSqlFile() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); INSERT INTO C2D_SCRIPT_ROWS VALUES (2); ROLLBACK;", new Context());
        assertEquals(List.of(), ids());
    }

    @Test
    void commitKeepsExactlyTheRowsImportedFromTheFile() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); INSERT INTO C2D_SCRIPT_ROWS VALUES (2); COMMIT;", new Context());
        assertEquals(List.of(1, 2), ids());
    }

    @Test
    void savepointRollbackDoesNotAllowALaterBatchToCommitTheOuterTransaction() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); SAVEPOINT keep; INSERT INTO C2D_SCRIPT_ROWS VALUES (2); "
                + "ROLLBACK TO SAVEPOINT keep; INSERT INTO C2D_SCRIPT_ROWS VALUES (3); ROLLBACK;", new Context());
        assertEquals(List.of(), ids());
    }

    @Test
    void commitAndChainLeavesTheNextTransactionUnderScriptControl() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); COMMIT AND CHAIN; "
                + "INSERT INTO C2D_SCRIPT_ROWS VALUES (2); ROLLBACK;", new Context());
        assertEquals(List.of(1), ids());
    }

    @Test
    void rollbackAndChainLeavesTheNextTransactionUnderScriptControl() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); ROLLBACK AND CHAIN; "
                + "INSERT INTO C2D_SCRIPT_ROWS VALUES (2); ROLLBACK;", new Context());
        assertEquals(List.of(), ids());
    }

    @Test
    void callerOwnedJdbcTransactionIsNotCommittedByTheImporter() throws Exception {
        connection.setAutoCommit(false);
        importSql("INSERT INTO C2D_SCRIPT_ROWS VALUES (1); INSERT INTO C2D_SCRIPT_ROWS VALUES (2);", new Context());
        assertFalse(connection.getAutoCommit());
        assertEquals(List.of(), ids());
        connection.commit();
        assertEquals(List.of(1, 2), ids());
    }

    @Test
    void endAndAbortAliasesPreserveActualCommitAndRollbackOutcomes() throws Exception {
        importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); END; BEGIN; "
                + "INSERT INTO C2D_SCRIPT_ROWS VALUES (2); ABORT;", new Context());
        assertEquals(List.of(1), ids());
    }

    @Test
    void cancellingARealRunningStatementLeavesNoCommittedRows() throws Exception {
        Context context = new Context();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var execution = worker.submit(() -> {
                bindContext();
                try {
                    importSql("BEGIN; INSERT INTO C2D_SCRIPT_ROWS VALUES (1); SELECT pg_sleep(10); "
                            + "INSERT INTO C2D_SCRIPT_ROWS VALUES (2); COMMIT;", context);
                } finally {
                    Chat2DBContext.removeContext();
                }
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean running = false;
            try (Connection probe = connect(); Statement query = probe.createStatement()) {
                while (System.nanoTime() < deadline) {
                    try (ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND state='active' AND query LIKE '%SELECT pg_sleep(10)%' AND pid<>pg_backend_pid()")) {
                        rows.next();
                        if (rows.getInt(1) > 0) { running = true; break; }
                    }
                    Thread.sleep(25);
                }
            }
            assertTrue(running, "The actual PostgreSQL statement must be running before cancellation");
            context.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> execution.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TaskCancelledException.class, failure.getCause());
            assertEquals(List.of(), ids());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void importSql(String sql, Context context) throws Exception {
        Path source = Files.createTempFile(directory, "script-", ".sql");
        Files.writeString(source, sql, StandardCharsets.UTF_8);
        SqlImportOptions options = new SqlImportOptions();
        options.setEncoding("UTF-8");
        new SQLImporter().run(ImportTaskSpec.builder().sourceFile(source.toString())
                .format("SQL").sqlImportOptions(options).build(), context);
    }

    private void bindContext() {
        ConnectInfo info = new ConnectInfo();
        info.setDbType(DB_TYPE);
        info.setDriverConfig(new DriverConfig());
        info.setConnection(connection);
        Chat2DBContext.putContext(info);
    }

    private Connection connect() throws Exception { return DriverManager.getConnection(jdbcUrl, "postgres", password); }

    private List<Integer> ids() throws Exception {
        try (Connection probe = connect(); Statement query = probe.createStatement();
                ResultSet rows = query.executeQuery("SELECT ID FROM C2D_SCRIPT_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) ids.add(rows.getInt(1));
            return ids;
        }
    }

    private static final class Context implements TaskExecutionContext {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Statement> active = new AtomicReference<>();
        void cancel() throws Exception {
            cancelled.set(true);
            Statement statement = active.get();
            if (statement != null) statement.cancel();
        }
        @Override public void checkCancelled() { if (cancelled.get()) throw new TaskCancelledException(); }
        @Override public void onStatementCreated(Statement statement) { active.set(statement); }
        @Override public void onStatementClosed(Statement statement) { active.compareAndSet(statement, null); }
        @Override public void reportProgress(int progress, String stage, String message) { }
        @Override public void logInfo(String code, String message) { }
        @Override public void logInfo(String code, String message, Map<String, Object> attributes) { }
        @Override public void logWarn(String code, String message, Map<String, Object> attributes) { }
        @Override public void logError(String code, String message, Map<String, Object> attributes) { }
        @Override public void registerCancelable(TaskCancelable resource) { }
        @Override public ArtifactDraft createArtifact(String directory, String name, String type) { throw new UnsupportedOperationException(); }
        @Override public void write(String content) { }
    }
}
