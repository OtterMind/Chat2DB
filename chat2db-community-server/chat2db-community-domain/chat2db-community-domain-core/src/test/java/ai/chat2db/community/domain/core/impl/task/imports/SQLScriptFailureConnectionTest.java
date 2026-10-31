package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.sql.SQLImporter;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2 script transactions; connection detachment prevents a failed lease returning to a pool. */
class SQLScriptFailureConnectionTest {
    @TempDir Path directory;

    @Test void cancelledScriptDiscardsItsUncommittedConnection() throws Exception {
        run(true, true);
    }

    @Test void failedScriptDiscardsItsUncommittedConnection() throws Exception {
        run(true, false);
    }

    @Test void failedScriptKeepsCallerOwnedTransaction() throws Exception {
        run(false, false);
    }

    @Test void failedScriptPreservesPreviouslyCommittedChunks() throws Exception {
        String url = "jdbc:h2:mem:failed_prefix_" + UUID.randomUUID().toString().replace("-", "");
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("H2", h2FixturePlugin());
        try (Connection observer = DriverManager.getConnection(url);
             Connection borrowed = DriverManager.getConnection(url)) {
            try (Statement statement = observer.createStatement()) {
                statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY)");
            }
            ConnectInfo info = new ConnectInfo();
            info.setDbType("H2"); info.setDriverConfig(new DriverConfig()); info.setConnection(borrowed);
            Chat2DBContext.putContext(info);
            Path file = directory.resolve("prefix.sql");
            Files.writeString(file, "INSERT INTO TARGET_ROWS VALUES (1);\nSELECT * FROM MISSING_TABLE;\n");
            assertThrows(TaskExecutionException.class, () -> new SQLImporter().run(
                    ImportTaskSpec.builder().sourceFile(file.toString()).build(), new Context(false)));
            assertNull(info.getConnection());
            assertTrue(borrowed.isClosed());
            try (Statement statement = observer.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT ID FROM TARGET_ROWS")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("H2");
            else Chat2DBContext.PLUGIN_MAP.put("H2", previous);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"BEGIN", "SET AUTOCOMMIT FALSE"})
    void taskCannotFinishWithAnUncommittedScriptTransaction(String opener) throws Exception {
        String url = "jdbc:h2:mem:unfinished_" + UUID.randomUUID().toString().replace("-", "");
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("H2", h2FixturePlugin());
        try (Connection observer = DriverManager.getConnection(url);
             Connection borrowed = DriverManager.getConnection(url)) {
            try (Statement statement = observer.createStatement()) { statement.execute("CREATE TABLE TARGET_ROWS (ID INT)"); }
            ConnectInfo info = new ConnectInfo(); info.setDbType("H2"); info.setDriverConfig(new DriverConfig());
            info.setConnection(borrowed); Chat2DBContext.putContext(info);
            Path file = directory.resolve("unfinished.sql");
            Files.writeString(file, opener + ";\nINSERT INTO TARGET_ROWS VALUES (1);\n");
            Context taskContext = new Context(false) { @Override public Long taskId() { return 17L; } };
            TaskExecutionException failure = assertThrows(TaskExecutionException.class, () -> new SQLImporter().run(
                    ImportTaskSpec.builder().sourceFile(file.toString()).build(), taskContext));
            assertEquals("IMPORT_SQL_TRANSACTION_OPEN", failure.getCode());
            assertNull(info.getConnection()); assertTrue(borrowed.isClosed());
            try (Statement statement = observer.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("H2");
            else Chat2DBContext.PLUGIN_MAP.put("H2", previous);
        }
    }

    private void run(boolean autoCommit, boolean cancel) throws Exception {
        String url = "jdbc:h2:mem:failed_script_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        IPlugin previous = Chat2DBContext.PLUGIN_MAP.put("H2", h2FixturePlugin());
        try (Connection physical = DriverManager.getConnection(url)) {
            try (Statement statement = physical.createStatement()) {
                statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY)");
            }
            physical.setAutoCommit(autoCommit);
            ConnectInfo info = new ConnectInfo();
            info.setDbType("H2");
            info.setDriverConfig(new DriverConfig());
            info.setConnection(physical);
            Chat2DBContext.putContext(info);
            Path file = directory.resolve("failed.sql");
            Files.writeString(file, (autoCommit ? "BEGIN;\n" : "")
                    + "INSERT INTO TARGET_ROWS VALUES (1);\n"
                    + (cancel ? "COMMIT;\n" : "INSERT INTO TARGET_ROWS VALUES (1);\n"));
            Context context = new Context(cancel);
            if (cancel) {
                assertThrows(TaskCancelledException.class,
                        () -> new SQLImporter().run(ImportTaskSpec.builder().sourceFile(file.toString()).build(), context));
            } else {
                assertThrows(TaskExecutionException.class,
                        () -> new SQLImporter().run(ImportTaskSpec.builder().sourceFile(file.toString()).build(), context));
            }
            if (autoCommit) {
                assertNull(info.getConnection(), "failed script must detach its borrowed connection");
                assertTrue(physical.isClosed(), "a pooled lease must not retain an open script transaction");
            } else {
                assertSame(physical, info.getConnection());
                assertFalse(physical.isClosed());
                assertFalse(physical.getAutoCommit());
                physical.rollback();
            }
            try (Connection observer = DriverManager.getConnection(url);
                 Statement statement = observer.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        } finally {
            Chat2DBContext.removeContext();
            if (previous == null) Chat2DBContext.PLUGIN_MAP.remove("H2");
            else Chat2DBContext.PLUGIN_MAP.put("H2", previous);
        }
    }

    private static IPlugin h2FixturePlugin() {
        DBConfig config = new DBConfig();
        config.setDbType("H2");
        config.setDefaultDriverConfig(new DriverConfig());
        return () -> config;
    }

    private static class Context implements TaskExecutionContext {
        final boolean cancelAfterInsert;
        int closed;
        boolean cancelled;
        Context(boolean cancelAfterInsert) { this.cancelAfterInsert = cancelAfterInsert; }
        public void reportProgress(int progress, String stage, String message) { }
        public void logInfo(String code, String message) { }
        public void logInfo(String code, String message, Map<String,Object> details) { }
        public void logWarn(String code, String message, Map<String,Object> details) { }
        public void logError(String code, String message, Map<String,Object> details) { }
        public void checkCancelled() { if (cancelled) throw new TaskCancelledException(); }
        public void registerCancelable(TaskCancelable resource) { }
        public ArtifactDraft createArtifact(String path, String name, String mediaType) { throw new UnsupportedOperationException(); }
        public void write(String content) { throw new UnsupportedOperationException(); }
        public void onStatementCreated(Statement statement) { }
        public void onStatementClosed(Statement statement) { if (++closed == 2 && cancelAfterInsert) cancelled = true; }
    }
}
