package ai.chat2db.community.domain.core.impl.task.imports.excel;

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
import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CSVImporterColumnMappingTest {

    private static final String TEST_DB_TYPE = "CSV_IMPORT_MAPPING_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:csv_import_mapping_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(64) NOT NULL, "
                    + "status VARCHAR(16) DEFAULT 'NEW', "
                    + "note VARCHAR(64))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(7L);
        connectInfo.setDbType(TEST_DB_TYPE);
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
    void mappedXlsxImportOmitsDefaultColumnsAndExecutesRows(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("orders.xlsx");
        EasyExcel.write(input.toFile())
                .head(List.of(List.of("Name")))
                .sheet()
                .doWrite(List.of(List.of("Alice"), List.of("Bob")));
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .build();
        RecordingTaskExecutionContext taskContext = new RecordingTaskExecutionContext();

        new XLSXImporter().doImportData(spec, taskContext, columns());

        assertEquals(List.of(2), taskContext.batchStatementCounts(), taskContext.events().toString());
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT name, status, note FROM orders ORDER BY id")) {
            resultSet.next();
            assertEquals("Alice", resultSet.getString("name"));
            assertEquals("NEW", resultSet.getString("status"));
            assertEquals(null, resultSet.getString("note"));
            resultSet.next();
            assertEquals("Bob", resultSet.getString("name"));
            assertEquals("NEW", resultSet.getString("status"));
            assertEquals(null, resultSet.getString("note"));
        }
    }

    @Test
    void nonMappedImportRollsBackWholeFileWhenBatchFails(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("orders-invalid.xlsx");
        EasyExcel.write(input.toFile())
                .head(List.of(List.of("Name")))
                .sheet()
                .doWrite(List.of(List.of("Alice"), List.of("x".repeat(80))));
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .columnMappings(List.of(Map.of("sourceColumn", "Name", "targetColumn", "name")))
                .unmappedTarget("DEFAULT")
                .build();
        RecordingTaskExecutionContext taskContext = new RecordingTaskExecutionContext();

        TaskExecutionException error = assertThrows(TaskExecutionException.class,
                () -> new XLSXImporter().doImportData(spec, taskContext, columns()));

        assertEquals("Could not execute imported SQL", error.publicMessage());
        assertEquals(List.of("IMPORT_BATCH_FAILED"), taskContext.errorCodes(), taskContext.events().toString());
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            resultSet.next();
            assertEquals(0, resultSet.getInt(1));
        }
    }

    private static List<TableColumn> columns() {
        return List.of(
                TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER)
                        .autoIncrement(true).build(),
                TableColumn.builder().name("name").columnType("VARCHAR").dataType(Types.VARCHAR)
                        .build(),
                TableColumn.builder().name("status").columnType("VARCHAR").dataType(Types.VARCHAR)
                        .defaultValue("'NEW'").build(),
                TableColumn.builder().name("note").columnType("VARCHAR").dataType(Types.VARCHAR)
                        .build());
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

    private static final class RecordingTaskExecutionContext implements TaskExecutionContext {

        private final List<Integer> batchStatementCounts = new ArrayList<>();

        private final List<String> events = new ArrayList<>();

        private final List<String> errorCodes = new ArrayList<>();

        private Map<String, Object> summaryDetails = Map.of();

        private List<Integer> batchStatementCounts() {
            return batchStatementCounts;
        }

        private List<String> events() {
            return events;
        }

        private List<String> errorCodes() {
            return errorCodes;
        }

        private Map<String, Object> summaryDetails() {
            return summaryDetails;
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
            events.add(code + ":" + message);
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
            events.add(code + ":" + message + ":" + details);
            if ("BATCH_EXECUTED".equals(code)) {
                batchStatementCounts.add((Integer) details.get("statementCount"));
            } else if ("IMPORT_SUMMARY".equals(code)) {
                summaryDetails = details;
            }
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            events.add(code + ":" + message + ":" + details);
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
            events.add(code + ":" + message + ":" + details);
            errorCodes.add(code);
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
