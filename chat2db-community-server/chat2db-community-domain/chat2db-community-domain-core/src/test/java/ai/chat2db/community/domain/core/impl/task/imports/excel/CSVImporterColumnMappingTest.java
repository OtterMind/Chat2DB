package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy;
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
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            statement.execute("CREATE TABLE formatted_rows ("
                    + "name VARCHAR(64) NOT NULL, event_date DATE, event_time TIMESTAMP, amount DECIMAL(10,2))");
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
    void explicitMappingOmitsDefaultColumnsAndExecutesRows(@TempDir Path directory)
            throws Exception {
        Path input = directory.resolve("orders.xlsx");
        EasyExcel.write(input.toFile())
                .head(List.of(List.of("Full Name")))
                .sheet()
                .doWrite(List.of(List.of("Alice"), List.of("Bob")));
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .columnMappings(List.of(ImportColumnMapping.builder()
                        .sourceColumn("Full Name").targetColumn("name").build()))
                .unmappedTarget(UnmappedTargetStrategy.DEFAULT)
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
    void nullStrategyWritesNullInsteadOfUsingColumnDefault(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders-null.xlsx");
        EasyExcel.write(input.toFile())
                .head(List.of(List.of("Full Name")))
                .sheet()
                .doWrite(List.of(List.of("Alice")));
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .columnMappings(List.of(ImportColumnMapping.builder()
                        .sourceColumn("Full Name").targetColumn("name").build()))
                .unmappedTarget(UnmappedTargetStrategy.NULL)
                .build();

        new XLSXImporter().doImportData(spec, new RecordingTaskExecutionContext(), columns());

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT status, note FROM orders")) {
            resultSet.next();
            assertEquals(null, resultSet.getString("status"));
            assertEquals(null, resultSet.getString("note"));
        }
    }

    @Test
    void csvOptionsDriveExecutionWithoutChangingFormulaPrefixedData(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.csv");
        Files.writeString(input, "Full Name;Note\nAlice;=1+1\n");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .csvOptions(CsvOptions.builder()
                        .encoding("UTF-8")
                        .delimiter(";")
                        .quote("\"")
                        .escape("\"")
                        .hasHeader(true)
                        .emptyAsNull(true)
                        .build())
                .columnMappings(List.of(
                        ImportColumnMapping.builder().sourceColumn("Full Name").targetColumn("name").build(),
                        ImportColumnMapping.builder().sourceColumn("Note").targetColumn("note").build()))
                .unmappedTarget(UnmappedTargetStrategy.DEFAULT)
                .build();

        new CSVImporter().doImportData(spec, new RecordingTaskExecutionContext(), columns());

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT name, note FROM orders")) {
            resultSet.next();
            assertEquals("Alice", resultSet.getString("name"));
            assertEquals("=1+1", resultSet.getString("note"));
        }
    }

    @Test
    void csvRowRangeAndFormatsDriveThePersistedValues(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("formatted.csv");
        Files.writeString(input, "Generated report\n"
                + "name,event_date,event_time,amount\n"
                + "Alice,24/8/23,24/August/2023 15:30:38,\"12,50\"\n"
                + "Skipped,25/8/23,25/August/2023 16:30:38,\"99,99\"\n");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().tableName("formatted_rows").build())
                .csvOptions(CsvOptions.builder()
                        .headerRow(2)
                        .dataStartRow(3)
                        .dataEndRow(3)
                        .dateOrder("DMY")
                        .dateTimeOrder("DATE_TIME")
                        .dateDelimiter("/")
                        .timeDelimiter(":")
                        .decimalSymbol(",")
                        .build())
                .columnMappings(List.of(
                        ImportColumnMapping.builder().sourceColumn("name").targetColumn("name").build(),
                        ImportColumnMapping.builder().sourceColumn("event_date").targetColumn("event_date").build(),
                        ImportColumnMapping.builder().sourceColumn("event_time").targetColumn("event_time").build(),
                        ImportColumnMapping.builder().sourceColumn("amount").targetColumn("amount").build()))
                .unmappedTarget(UnmappedTargetStrategy.DEFAULT)
                .build();
        List<TableColumn> targetColumns = List.of(
                TableColumn.builder().name("name").columnType("VARCHAR").dataType(Types.VARCHAR).build(),
                TableColumn.builder().name("event_date").columnType("DATE").dataType(Types.DATE).build(),
                TableColumn.builder().name("event_time").columnType("TIMESTAMP").dataType(Types.TIMESTAMP).build(),
                TableColumn.builder().name("amount").columnType("DECIMAL").dataType(Types.DECIMAL).build());

        new CSVImporter().doImportData(spec, new RecordingTaskExecutionContext(), targetColumns);

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT name, event_date, event_time, amount FROM formatted_rows")) {
            resultSet.next();
            assertEquals("Alice", resultSet.getString("name"));
            assertEquals("2023-08-24", resultSet.getString("event_date"));
            assertEquals("2023-08-24 15:30:38", resultSet.getString("event_time"));
            assertEquals("12.50", resultSet.getString("amount"));
            assertEquals(false, resultSet.next());
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

        private List<Integer> batchStatementCounts() {
            return batchStatementCounts;
        }

        private List<String> events() {
            return events;
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
            }
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            events.add(code + ":" + message + ":" + details);
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
            events.add(code + ":" + message + ":" + details);
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
