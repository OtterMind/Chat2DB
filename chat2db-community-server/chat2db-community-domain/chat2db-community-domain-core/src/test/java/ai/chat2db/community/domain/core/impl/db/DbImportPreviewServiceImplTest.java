package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbImportPreviewServiceImplTest {

    private static final String TEST_DB_TYPE = "IMPORT_PREVIEW_POLICY_TEST";

    private final RecordingMetaData metaData = new RecordingMetaData();

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection("jdbc:h2:mem:import_preview_policy;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDataSourceId(10L);
        connectInfo.setConnection(connection);
        connectInfo.setDatabaseName("trusted_db");
        connectInfo.setSchemaName("trusted_schema");
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
    void previewUsesContextDatabaseSchemaAndResolvedTableForColumnLookup(@TempDir Path directory) throws Exception {
        Path input = writeWorkbook(directory);

        Map<String, Object> preview = new DbImportPreviewServiceImpl().preview(10L, "trusted_db", null, "orders", input.toFile(),
                Map.of("sheetName", "visible", "headerRow", 1));

        assertEquals("trusted_db", metaData.lastTablesRequest.getDatabaseName());
        assertEquals("trusted_schema", metaData.lastTablesRequest.getSchemaName());
        assertEquals(null, metaData.lastTablesRequest.getTableName());
        assertEquals("trusted_db", metaData.lastColumnsRequest.getDatabaseName());
        assertEquals("trusted_schema", metaData.lastColumnsRequest.getSchemaName());
        assertEquals("ORDERS", metaData.lastColumnsRequest.getTableName());
        assertFalse(((List<?>) preview.get("targetColumns")).isEmpty());
    }

    @Test
    void previewReportsDuplicateBlankHeadersAndLargeFileLimit(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("id");
            header.createCell(2).setBlank();
            for (int i = 1; i <= 51; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(String.valueOf(i));
            }
            workbook.write(output);
            Files.write(input, output.toByteArray());
        }

        Map<String, Object> preview = new DbImportPreviewServiceImpl().preview(10L, "trusted_db", null, "orders",
                input.toFile(), Map.of("sheetName", "visible", "headerRow", 1));

        assertEquals(List.of("id"), preview.get("duplicateHeaders"));
        assertEquals(List.of("column_3"), preview.get("invalidHeaders"));
        assertEquals(true, preview.get("hasMoreRows"));
        assertEquals(50, preview.get("previewRows"));
    }

    @Test
    void previewCountsEmptyRowsSkippedInsideExcelDataRange(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("orders.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.createRow(1).createCell(0).setCellValue("1");
            sheet.createRow(3).createCell(0).setBlank();
            sheet.createRow(4).createCell(0).setCellValue("2");
            workbook.write(output);
            Files.write(input, output.toByteArray());
        }

        Map<String, Object> preview = new DbImportPreviewServiceImpl().preview(10L, "trusted_db", null, "orders",
                input.toFile(), Map.of("sheetName", "visible", "headerRow", 1));

        assertEquals(2, preview.get("previewRows"));
        assertEquals(2L, preview.get("skippedCount"));
        assertEquals(false, preview.get("hasMoreRows"));
    }

    @Test
    void previewRejectsRequestDatabaseMismatchBeforeMetadataLookup(@TempDir Path directory) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbImportPreviewServiceImpl().preview(10L, "other_db", null, "orders",
                        directory.resolve("missing.xlsx").toFile(), Map.of()));

        assertEquals("import.target.contextMismatch", exception.getMessage());
        assertEquals(null, metaData.lastTablesRequest);
        assertEquals(null, metaData.lastColumnsRequest);
    }

    @Test
    void previewRejectsRequestDatasourceMismatchBeforeMetadataLookup(@TempDir Path directory) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbImportPreviewServiceImpl().preview(11L, "trusted_db", null, "orders",
                        directory.resolve("missing.xlsx").toFile(), Map.of()));

        assertEquals("import.target.contextMismatch", exception.getMessage());
        assertEquals(null, metaData.lastTablesRequest);
        assertEquals(null, metaData.lastColumnsRequest);
    }

    @Test
    void previewRejectsWildcardTableNameBeforeMetadataLookup(@TempDir Path directory) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbImportPreviewServiceImpl().preview(10L, "trusted_db", null, "orders%",
                        directory.resolve("missing.xlsx").toFile(), Map.of()));

        assertEquals("import.target.unsafeTableName", exception.getMessage());
        assertEquals(null, metaData.lastTablesRequest);
        assertEquals(null, metaData.lastColumnsRequest);
    }

    private static Path writeWorkbook(Path directory) throws Exception {
        Path input = directory.resolve("orders.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1");
            workbook.write(output);
            Files.write(input, output.toByteArray());
        }
        return input;
    }

    private IPlugin plugin() {
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
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

    private static final class RecordingMetaData extends DefaultMetaService {

        private TablesRequest lastTablesRequest;

        private TableMetadataRequest lastColumnsRequest;

        @Override
        public List<Table> tables(Connection connection, TablesRequest tablesRequest) {
            lastTablesRequest = tablesRequest;
            return List.of(Table.builder().databaseName(tablesRequest.getDatabaseName())
                    .schemaName(tablesRequest.getSchemaName()).name("ORDERS").build());
        }

        @Override
        public List<TableColumn> columns(Connection connection, TableMetadataRequest tableMetadataRequest) {
            lastColumnsRequest = tableMetadataRequest;
            return List.of(TableColumn.builder().tableName(tableMetadataRequest.getTableName())
                    .name("id").columnType("INT").nullable(1).build());
        }
    }
}
