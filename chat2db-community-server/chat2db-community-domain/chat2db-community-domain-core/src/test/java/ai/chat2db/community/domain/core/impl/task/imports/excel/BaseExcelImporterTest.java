package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseExcelImporterTest {

    private static final String TEST_DB_TYPE = "EXCEL_IMPORT_POLICY_TEST";

    private IPlugin previousPlugin;

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, plugin());
        connection = DriverManager.getConnection("jdbc:h2:mem:excel_import_policy;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDataSourceId(10L);
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
    void xlsxImportRejectsExplicitlySelectedHiddenSheet(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id INT PRIMARY KEY)");
        Path input = writeWorkbook(directory, workbook -> {
            workbook.createSheet("visible").createRow(0).createCell(0).setCellValue("id");
            workbook.createSheet("hidden").createRow(0).createCell(0).setCellValue("id");
            workbook.getSheet("hidden").createRow(1).createCell(0).setCellValue(1);
            workbook.setSheetHidden(1, true);
        });

        ImportTaskSpec spec = spec(input, Map.of("sheetName", "hidden", "headerRow", 1),
                List.of(Map.of("sourceColumn", "id", "targetColumn", "id")), "DEFAULT");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new XLSXImporter().doImportData(spec, new RecordingTaskExecutionContext(), columns("id")));

        assertEquals("import.excel.noVisibleSheet", exception.getMessage());
    }

    @Test
    void xlsxImportHonorsDataStartAfterHeaderAndLeavesDefaultColumnsUnbound(@TempDir Path directory)
            throws Exception {
        createOrdersTable("CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(20) DEFAULT 'guest')");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("intro");
            sheet.createRow(1).createCell(0).setCellValue("id");
            sheet.createRow(2).createCell(0).setCellValue("skip-me");
            sheet.createRow(3).createCell(0).setCellValue("1");
        });
        RecordingTaskExecutionContext context = new RecordingTaskExecutionContext();

        new XLSXImporter().doImportData(spec(input, Map.of("sheetName", "visible", "headerRow", 2, "startRow", 3),
                        List.of(Map.of("sourceColumn", "id", "targetColumn", "id")), "DEFAULT"),
                context, columns("id", "name"));

        assertEquals(List.of(), context.events(TaskEventKind.ERROR));
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT id, name FROM orders")) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt("id"));
            assertEquals("guest", resultSet.getString("name"));
            assertEquals(false, resultSet.next());
        }
    }

    @Test
    void xlsxImportBatchesLargeExactValueImport(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id INT PRIMARY KEY, amount VARCHAR(40))");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("amount");
            for (int i = 1; i <= 1001; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(i);
                row.createCell(1).setCellValue(0.1D);
            }
        });
        RecordingTaskExecutionContext context = new RecordingTaskExecutionContext();

        new XLSXImporter().doImportData(spec(input, Map.of("sheetName", "visible", "headerRow", 1),
                        List.of(Map.of("sourceColumn", "id", "targetColumn", "id"),
                                Map.of("sourceColumn", "amount", "targetColumn", "amount")), "DEFAULT"),
                context, columns("id", "amount"));

        assertEquals(1001, countRows());
        assertEquals("0.1", firstAmount());
        assertEquals(List.of(1000, 1), context.batchStatementCounts());
    }

    @Test
    void queuedXlsxImportLoadsMetadataFromContextAndImports(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(20) DEFAULT 'guest')");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1");
            row.createCell(1).setCellValue("alice");
        });
        RecordingTaskExecutionContext context = new RecordingTaskExecutionContext();

        new XLSXImporter().run(spec(input, Map.of("sheetName", "visible", "headerRow", 1),
                List.of(Map.of("sourceColumn", "id", "targetColumn", "id"),
                        Map.of("sourceColumn", "name", "targetColumn", "name")), "DEFAULT"), context);

        assertEquals(1, countRows());
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT name FROM orders WHERE id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("alice", resultSet.getString(1));
        }
    }

    @Test
    void queuedXlsxImportUsesSharedParserForTypedValues(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id VARCHAR(20), amount VARCHAR(40), created_at VARCHAR(40), "
                + "active VARCHAR(10), formula_total VARCHAR(40))");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("id");
            header.createCell(1).setCellValue("amount");
            header.createCell(2).setCellValue("created_at");
            header.createCell(3).setCellValue("active");
            header.createCell(4).setCellValue("formula_total");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("1");
            row.createCell(1).setCellValue(0.1D);
            var dateCell = row.createCell(2);
            dateCell.setCellValue(new GregorianCalendar(2026, Calendar.AUGUST, 31, 8, 30, 0));
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            dateCell.setCellStyle(style);
            row.createCell(3).setCellValue(true);
            row.createCell(4).setCellFormula("B2*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(4));
        });

        new XLSXImporter().doImportData(spec(input, Map.of("sheetName", "visible", "headerRow", 1),
                        List.of(Map.of("sourceColumn", "id", "targetColumn", "id"),
                                Map.of("sourceColumn", "amount", "targetColumn", "amount"),
                                Map.of("sourceColumn", "created_at", "targetColumn", "created_at"),
                                Map.of("sourceColumn", "active", "targetColumn", "active"),
                                Map.of("sourceColumn", "formula_total", "targetColumn", "formula_total")),
                        "DEFAULT"),
                new RecordingTaskExecutionContext(), columns("id", "amount", "created_at", "active", "formula_total"));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT amount, created_at, active, formula_total FROM orders WHERE id = '1'")) {
            assertTrue(resultSet.next());
            assertEquals("0.1", resultSet.getString("amount"));
            assertEquals("2026-08-31 08:30:00", resultSet.getString("created_at"));
            assertEquals("true", resultSet.getString("active"));
            assertEquals("0.2", resultSet.getString("formula_total"));
        }
    }

    @Test
    void noHeaderXlsxImportKeepsSparseBlankCellsWhenConfigured(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id VARCHAR(20), note VARCHAR(20))");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            Row firstRow = sheet.createRow(0);
            firstRow.createCell(0).setCellValue("1");
            Row secondRow = sheet.createRow(1);
            secondRow.createCell(0).setCellValue("2");
            secondRow.createCell(1).setCellValue("later");
        });

        new XLSXImporter().doImportData(spec(input,
                        Map.of("sheetName", "visible", "hasHeader", false, "headerRow", 0, "emptyAsNull", false),
                        List.of(Map.of("sourceColumn", "column_1", "targetColumn", "id"),
                                Map.of("sourceColumn", "column_2", "targetColumn", "note")),
                        "NULL"),
                new RecordingTaskExecutionContext(), columns("id", "note"));

        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT note FROM orders ORDER BY id")) {
            assertTrue(resultSet.next());
            assertEquals("", resultSet.getString(1));
            assertTrue(resultSet.next());
            assertEquals("later", resultSet.getString(1));
            assertEquals(false, resultSet.next());
        }
    }

    @Test
    void xlsxImportCountsSkippedEmptyRowsInTaskSummary(@TempDir Path directory) throws Exception {
        createOrdersTable("CREATE TABLE orders (id INT PRIMARY KEY)");
        Path input = writeWorkbook(directory, workbook -> {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("id");
            sheet.createRow(1).createCell(0).setCellValue("1");
            sheet.createRow(3).createCell(0).setBlank();
            sheet.createRow(4).createCell(0).setCellValue("2");
        });
        RecordingTaskExecutionContext context = new RecordingTaskExecutionContext();

        new XLSXImporter().doImportData(spec(input, Map.of("sheetName", "visible", "headerRow", 1),
                        List.of(Map.of("sourceColumn", "id", "targetColumn", "id")), "DEFAULT"),
                context, columns("id"));

        assertEquals(2, countRows());
        Map<String, Object> summary = context.detailsFor("IMPORT_SUMMARY");
        assertEquals(2L, summary.get("successCount"));
        assertEquals(0L, summary.get("failedCount"));
        assertEquals(2L, summary.get("skippedCount"));
    }

    @Test
    void queuedXlsxImportRejectsRequestDatabaseMismatchBeforeMetadataLookup(@TempDir Path directory) {
        Chat2DBContext.getConnectInfo().setDatabaseName("trusted_db");
        ImportTaskSpec spec = spec(directory.resolve("missing.xlsx"), Map.of(),
                List.of(Map.of("sourceColumn", "id", "targetColumn", "id")), "DEFAULT");
        spec.getTarget().setDatabaseName("other_db");

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> new XLSXImporter().run(spec, new RecordingTaskExecutionContext()));

        assertEquals("IMPORT_FAILED", exception.getCode());
        assertTrue(exception.getCause() instanceof BusinessException);
        assertEquals("import.target.contextMismatch", exception.getCause().getMessage());
    }

    @Test
    void queuedXlsxImportRejectsWildcardTableNameBeforeMetadataLookup(@TempDir Path directory) {
        ImportTaskSpec spec = spec(directory.resolve("missing.xlsx"), Map.of(),
                List.of(Map.of("sourceColumn", "id", "targetColumn", "id")), "DEFAULT");
        spec.getTarget().setTableName("orders%");

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> new XLSXImporter().run(spec, new RecordingTaskExecutionContext()));

        assertEquals("IMPORT_FAILED", exception.getCode());
        assertTrue(exception.getCause() instanceof BusinessException);
        assertEquals("import.target.unsafeTableName", exception.getCause().getMessage());
    }

    private void createOrdersTable(String ddl) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders");
            statement.execute(ddl);
        }
    }

    private static Path writeWorkbook(Path directory, WorkbookWriter writer) throws Exception {
        Path input = directory.resolve("orders.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.write(workbook);
            workbook.write(output);
            Files.write(input, output.toByteArray());
        }
        return input;
    }

    private ImportTaskSpec spec(Path input, Map<String, Object> options, List<Map<String, String>> mappings,
            String unmappedTarget) {
        return ImportTaskSpec.builder()
                .sourceFile(input.toString())
                .target(TaskTargetSnapshot.builder().dataSourceId(10L).tableName("orders").build())
                .columnMappings(mappings)
                .unmappedTarget(unmappedTarget)
                .importOptions(options)
                .build();
    }

    private static List<TableColumn> columns(String... names) {
        List<TableColumn> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(TableColumn.builder().name(name).columnType("VARCHAR").build());
        }
        return columns;
    }

    private int countRows() throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM orders")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private String firstAmount() throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT amount FROM orders WHERE id = 1")) {
            resultSet.next();
            return resultSet.getString(1);
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

    private interface WorkbookWriter {
        void write(XSSFWorkbook workbook) throws Exception;
    }

    private enum TaskEventKind {
        INFO,
        ERROR
    }

    private record RecordedTaskEvent(TaskEventKind kind, String code, Map<String, Object> details) {
    }

    private static final class RecordingTaskExecutionContext implements TaskExecutionContext {

        private final List<RecordedTaskEvent> events = new ArrayList<>();

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
            logInfo(code, message, Map.of());
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
            events.add(new RecordedTaskEvent(TaskEventKind.INFO, code, details));
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
            events.add(new RecordedTaskEvent(TaskEventKind.ERROR, code, details));
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

        private List<RecordedTaskEvent> events(TaskEventKind kind) {
            return events.stream().filter(event -> event.kind() == kind).toList();
        }

        private List<Integer> batchStatementCounts() {
            return events.stream()
                    .filter(event -> TaskEventCode.BATCH_EXECUTED.name().equals(event.code()))
                    .map(RecordedTaskEvent::details)
                    .filter(details -> details.containsKey("statementCount"))
                    .map(details -> ((Number) details.get("statementCount")).intValue())
                    .toList();
        }

        private Map<String, Object> detailsFor(String code) {
            return events.stream()
                    .filter(event -> code.equals(event.code()))
                    .findFirst()
                    .map(RecordedTaskEvent::details)
                    .orElse(Map.of());
        }
    }
}
