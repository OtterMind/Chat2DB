package ai.chat2db.community.domain.core.impl.task.imports.json;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JSONImporterExtensionTest {

    private static final String TEST_DB_TYPE = "JSON_IMPORT_POLICY_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:json_import_extension;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS shop CASCADE");
            statement.execute("CREATE SCHEMA shop");
            statement.execute("CREATE TABLE shop.orders (id INT PRIMARY KEY, note VARCHAR(255))");
            statement.execute("CREATE TABLE shop.nullable_orders (id INT)");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDatabaseName("shop");
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void jsonImportExecutesGeneratedStatementsThroughTheNewTaskSqlExecutor(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[{\"id\":1},{\"id\":2}]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();

        new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM shop.orders")) {
            resultSet.next();
            assertEquals(2, resultSet.getInt(1));
        }
    }

    @Test
    void explicitJsonNullIsImportedAsSqlNull(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[{\"id\":1,\"note\":null}]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();
        TableColumn note = TableColumn.builder().name("note").columnType("VARCHAR").build();

        new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id, note));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT note FROM shop.orders WHERE id = 1")) {
            resultSet.next();
            assertNull(resultSet.getString(1));
        }
    }

    @Test
    void nonObjectArrayRecordFailsWithoutInsertingRows(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.json");
        Files.writeString(input, "[1]");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("nullable_orders").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").build();

        assertThrows(TaskExecutionException.class,
                () -> new JSONImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id)));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM shop.nullable_orders")) {
            resultSet.next();
            assertEquals(0, resultSet.getInt(1));
        }
    }

    private IPlugin plugin() {
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService();
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private static final class NoOpTaskExecutionContext implements TaskExecutionContext {

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
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
