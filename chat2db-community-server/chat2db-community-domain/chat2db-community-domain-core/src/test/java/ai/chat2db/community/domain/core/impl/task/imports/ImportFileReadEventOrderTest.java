package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportFileReadEventOrderTest {
    private static final String DB_TYPE = "IMPORT_READ_EVENT_TEST";
    private static final String READ_STARTED = "FILE_READ_STARTED";
    private static final String READ_COMPLETED = "FILE_READ_COMPLETED";
    private static final String BATCH_EXECUTED = "BATCH_EXECUTED";
    private Connection connection;
    private IPlugin previousPlugin;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() throws Exception {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService() {
                    @Override
                    public List<TableColumn> columns(Connection ignored, TableMetadataRequest request) {
                        return List.of(
                                TableColumn.builder().name("ID").columnType("INTEGER")
                                        .dataType(Types.INTEGER).nullable(0).build(),
                                TableColumn.builder().name("NAME").columnType("VARCHAR")
                                        .dataType(Types.VARCHAR).build());
                    }
                };
            }
        });
        connection = DriverManager.getConnection("jdbc:h2:mem:read_event_" + System.nanoTime() + ";MODE=MySQL");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IMPORT_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(64))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        else Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        if (connection != null) connection.close();
    }

    @ParameterizedTest
    @CsvSource({"CSV,STANDARD", "CSV,FAST", "XLS,STANDARD", "XLSX,STANDARD", "JSON,STANDARD"})
    void reportsReadCompletionBeforeSubmittingTheTailBatch(String format, String mode) throws Exception {
        RecordingContext context = new RecordingContext();
        ImportFactory.get(format).run(spec(writeFile(format, 10), format, mode), context);

        assertEquals(1, Collections.frequency(context.events, READ_COMPLETED), context.events.toString());
        assertTrue(context.events.indexOf(READ_STARTED) < context.events.indexOf(READ_COMPLETED));
        assertTrue(context.events.indexOf(READ_COMPLETED) < context.events.indexOf(BATCH_EXECUTED),
                "the ten-row tail must be submitted after the reader finishes: " + context.events);
        assertEquals(List.of(0), context.rowsAtReadCompletion,
                "read-completed must describe EOF, not the return of the whole importer");
        assertEquals(10, rowCount());
    }

    @Test
    void keepsFullBatchesStreamingBeforeReadCompletion() throws Exception {
        RecordingContext context = new RecordingContext();
        ImportFactory.get("CSV").run(spec(writeFile("CSV", 1001), "CSV", "STANDARD"), context);

        assertEquals(1, Collections.frequency(context.events, READ_COMPLETED));
        assertTrue(context.events.indexOf(BATCH_EXECUTED) < context.events.indexOf(READ_COMPLETED));
        assertEquals(List.of(1000), context.rowsAtReadCompletion,
                "the full batch is already stored at EOF, but the last row is still buffered");
        assertEquals(1001, rowCount());
    }

    @Test
    void readCompletionRemainsTrueWhenTheTailWriteFails() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO IMPORT_ROWS VALUES (1, 'original')");
        }
        RecordingContext context = new RecordingContext();
        // Every row conflicts, so this test is independent of JDBC partial-batch commit behavior.
        Path input = Files.writeString(directory.resolve("duplicates.csv"),
                "ID,NAME\n" + "1,duplicate\n".repeat(10));
        ImportTaskSpec spec = spec(input, "CSV", "STANDARD");

        assertThrows(RuntimeException.class, () -> ImportFactory.get("CSV").run(spec, context));

        assertEquals(1, Collections.frequency(context.events, READ_COMPLETED),
                "a later SQL failure does not mean that reading the file failed");
        assertEquals(List.of(1), context.rowsAtReadCompletion);
        assertFalse(context.events.contains(BATCH_EXECUTED));
        assertEquals(1, rowCount());
    }

    @Test
    void doesNotReportReadCompletionForAMalformedFile() throws Exception {
        Path input = Files.writeString(directory.resolve("broken.json"), "[{\"ID\":1,\"NAME\":");
        RecordingContext context = new RecordingContext();

        assertThrows(RuntimeException.class,
                () -> ImportFactory.get("JSON").run(spec(input, "JSON", "STANDARD"), context));

        assertTrue(context.events.contains(READ_STARTED));
        assertFalse(context.events.contains(READ_COMPLETED));
        assertEquals(0, rowCount());
    }

    @ParameterizedTest
    @CsvSource({"CSV,STANDARD", "CSV,FAST", "XLS,STANDARD", "XLSX,STANDARD", "JSON,STANDARD"})
    void cancellationDuringReadingDoesNotReportReadCompletion(String format, String mode) throws Exception {
        RecordingContext context = new RecordingContext();
        context.cancelDuringRead = true;
        ImportTaskSpec spec = spec(writeFile(format, 10), format, mode);

        assertThrows(TaskCancelledException.class, () -> ImportFactory.get(format).run(spec, context));

        assertTrue(context.events.contains(READ_STARTED));
        assertFalse(context.events.contains(READ_COMPLETED));
        assertEquals(0, rowCount());
    }

    private ImportTaskSpec spec(Path input, String format, String mode) {
        return ImportTaskSpec.builder().sourceFile(input.toString()).format(format).mode(mode)
                .target(TaskTargetSnapshot.builder().tableName("IMPORT_ROWS").build())
                .columnMappings(List.of(new ImportColumnMapping("ID", "ID"),
                        new ImportColumnMapping("NAME", "NAME"))).build();
    }

    private Path writeFile(String format, int count) throws Exception {
        Path input = directory.resolve("rows." + format.toLowerCase(java.util.Locale.ROOT));
        if ("CSV".equals(format)) {
            StringBuilder text = new StringBuilder("ID,NAME\n");
            IntStream.rangeClosed(1, count).forEach(id -> text.append(id).append(",row-").append(id).append('\n'));
            return Files.writeString(input, text);
        }
        if ("JSON".equals(format)) {
            return Files.writeString(input, "[" + String.join(",", IntStream.rangeClosed(1, count)
                    .mapToObj(id -> "{\"ID\":" + id + ",\"NAME\":\"row-" + id + "\"}").toList()) + "]");
        }
        try (Workbook book = "XLS".equals(format) ? new HSSFWorkbook() : new XSSFWorkbook()) {
            var sheet = book.createSheet("Data");
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("ID");
            head.createCell(1).setCellValue("NAME");
            for (int id = 1; id <= count; id++) {
                var row = sheet.createRow(id);
                row.createCell(0).setCellValue(id);
                row.createCell(1).setCellValue("row-" + id);
            }
            try (var out = Files.newOutputStream(input)) { book.write(out); }
        }
        return input;
    }

    private int rowCount() {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM IMPORT_ROWS")) {
            rows.next();
            return rows.getInt(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private final class RecordingContext implements TaskExecutionContext {
        private final List<String> events = new ArrayList<>();
        private final List<Integer> rowsAtReadCompletion = new ArrayList<>();
        private boolean cancelDuringRead;
        private int readChecks;

        @Override
        public void reportProgress(int progress, String stage, String message) { }

        @Override
        public void logInfo(String code, String message) {
            events.add(code);
            if (READ_COMPLETED.equals(code)) rowsAtReadCompletion.add(rowCount());
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) { logInfo(code, message); }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) { events.add(code); }

        @Override
        public void logError(String code, String message, Map<String, Object> details) { events.add(code); }

        @Override
        public void checkCancelled() {
            if (cancelDuringRead && events.contains(READ_STARTED) && ++readChecks >= 3) {
                throw new TaskCancelledException();
            }
        }

        @Override
        public void registerCancelable(TaskCancelable resource) { }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(String content) { throw new UnsupportedOperationException(); }

        @Override
        public void onStatementCreated(Statement statement) { }

        @Override
        public void onStatementClosed(Statement statement) { }
    }
}
