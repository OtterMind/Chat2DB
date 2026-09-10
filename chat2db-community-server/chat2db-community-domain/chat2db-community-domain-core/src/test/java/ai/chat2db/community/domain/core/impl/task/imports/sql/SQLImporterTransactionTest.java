package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IPlugin;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLImporterTransactionTest {

    private static final String DB_TYPE = "H2";

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() throws Exception {
        String database = "sql_import_tx_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(32))");
        }

        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new H2Plugin());
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void commitsOnlyAfterTheValidatedSecondPassCompletes() throws Exception {
        Path source = sql("success.sql", """
                CREATE TABLE SHOULD_BE_FILTERED (ID INT);
                SET FOREIGN_KEY_CHECKS = 0;
                INSERT INTO TARGET_ROWS VALUES (1, 'one');
                INSERT INTO TARGET_ROWS VALUES (2, 'two');
                SET FOREIGN_KEY_CHECKS = 1;
                """);

        new SQLImporter().run(spec(source), new NoopContext());

        assertEquals(2L, rowCount());
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void rollsBackEarlierStatementsWhenALaterStatementFails() throws Exception {
        Path source = sql("failure.sql", """
                INSERT INTO TARGET_ROWS VALUES (1, 'first');
                INSERT INTO TARGET_ROWS VALUES (1, 'duplicate');
                """);

        assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(source), new NoopContext()));

        assertEquals(0L, rowCount());
        assertTrue(connection.getAutoCommit());
    }

    private ImportTaskSpec spec(Path source) {
        return ImportTaskSpec.builder()
                .sourceFile(source.toString())
                .format("SQL")
                .scope(ImportScope.TABLE)
                .target(TaskTargetSnapshot.builder().tableName("TARGET_ROWS").build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .sqlExporterProfile("NAVICAT")
                        .build())
                .build();
    }

    private Path sql(String name, String contents) throws Exception {
        return Files.writeString(tempDirectory.resolve(name), contents, StandardCharsets.UTF_8);
    }

    private long rowCount() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static final class NoopContext implements TaskExecutionContext {

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException("SQL import does not create artifacts");
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
