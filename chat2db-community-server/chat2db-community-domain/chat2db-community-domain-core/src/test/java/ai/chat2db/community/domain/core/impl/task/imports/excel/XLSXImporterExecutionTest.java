package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the XLSX execution path with no client options, so the reader defaults must reach the row
 * builder for text values to be normalized like the preview shows.
 */
class XLSXImporterExecutionTest {

    private static final String TEST_DB_TYPE = "XLSX_IMPORT_EXECUTION_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:xlsx_import_execution;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS shop CASCADE");
            statement.execute("CREATE SCHEMA shop");
            statement.execute("CREATE TABLE shop.excel_rows (id INT PRIMARY KEY, event_date DATE, note VARCHAR(50))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(11L);
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
    void excelImportUsesReaderDefaultsWhenTheClientSendsNoOptions(@TempDir Path directory) throws Exception {
        Path workbook = directory.resolve("rows.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = book.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("event_date");
            header.createCell(2).setCellValue("note");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("2026-9-6");
            try (var out = Files.newOutputStream(workbook)) {
                book.write(out);
            }
        }
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(workbook.toString())
                .target(TaskTargetSnapshot.builder().tableName("excel_rows").build())
                .build();
        TableColumn id = TableColumn.builder().name("id").columnType("INTEGER").dataType(Types.INTEGER).build();
        TableColumn eventDate = TableColumn.builder().name("event_date").columnType("DATE")
                .dataType(Types.DATE).build();
        TableColumn note = TableColumn.builder().name("note").columnType("VARCHAR")
                .dataType(Types.VARCHAR).build();

        new XLSXImporter().doImportData(spec, new NoOpTaskExecutionContext(), List.of(id, eventDate, note));

        assertNotNull(spec.getExcelOptions(), "the reader defaults must be visible to the row builder");
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, event_date, note FROM shop.excel_rows")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("2026-09-06", rows.getString(2));
            assertEquals(null, rows.getString(3));
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
