package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parallel import path end to end: multiple workers, each on its own dedicated connection,
 * execute partitioned batches while the caller keeps producing. Verifies that parallel workers
 * insert every row exactly once and propagate failed batches without retrying their rows.
 */
class ImportRowBatcherParallelTest {

    private static final String DB_TYPE = "PARALLEL_IMPORT_TEST";

    private static final String H2_DRIVER_NAME = "parallel-import-test-h2.jar";

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private java.sql.Connection connection;
    private IPlugin previousPlugin;
    private InMemoryTaskStorage storage;

    @BeforeAll
    static void isolateHomeAndSeedDriver() throws Exception {
        // JdbcJarUtils resolves driver names against the driver library under user.home and
        // cannot load an absolute jar path, so seed a copy of the H2 jar and isolate the home
        // directory so worker connections can load the fixture driver.
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-parallel-import-home").toFile();
        System.setProperty("user.home", tempHome.getAbsolutePath());
        File libDir = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
        libDir.mkdirs();
        File h2Jar = new File(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(h2Jar.toPath(), new File(libDir, H2_DRIVER_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterAll
    static void restoreHome() {
        System.setProperty("user.home", previousUserHome);
    }

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
                return new DefaultMetaService();
            }
        });
        connection = DriverManager.getConnection("jdbc:h2:mem:parallel_import");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE BULK_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(50))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        // A real URL plus driver config so parallel workers can build their own dedicated
        // connections through ConnectionPool, exactly like the production call path.
        connectInfo.setUrl("jdbc:h2:mem:parallel_import");
        connectInfo.setDriverConfig(h2DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new InMemoryTaskStorage();
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        connection.close();
    }

    private static DriverConfig h2DriverConfig() {
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriver(H2_DRIVER_NAME);
        driverConfig.setJdbcDriverClass("org.h2.Driver");
        return driverConfig;
    }

    private TaskExecutionContextImpl contextFor(ImportTaskSpec spec) {
        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("import")
                .target(spec.getTarget()).build(), TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId),
                storage, new ArtifactServiceImpl());
    }

    private ImportTaskSpec csvSpec(Path csv) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .importFileId("parallel-import-test-source")
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("BULK_ROWS").build())
                .mode("FAST")
                .columnMappings(List.of(
                        new ImportColumnMapping("ID", "ID"),
                        new ImportColumnMapping("NAME", "NAME")))
                .build();
    }

    private Path writeCsv(String... lines) throws Exception {
        Path csv = tempDirectory.resolve("bulk.csv");
        StringBuilder content = new StringBuilder("ID,NAME\n");
        for (String line : lines) {
            content.append(line).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);
        return csv;
    }

    private List<Integer> importedIds() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID FROM BULK_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }

    @Test
    void parallelWorkersInsertEveryRowExactlyOnce() throws Exception {
        // Enough rows that even the contract baseline batch (20000 rows) splits into many batches,
        // so the assertion below observes real overlap instead of a single-batch edge case.
        int rows = 200_000;
        String[] lines = new String[rows];
        for (int index = 0; index < rows; index++) {
            lines[index] = (index + 1) + ",name-" + (index + 1);
        }
        Path csv = writeCsv(lines);
        ImportTaskSpec spec = csvSpec(csv);

        new CSVImporter().run(spec, contextFor(spec));

        List<Integer> ids = importedIds();
        assertEquals(rows, ids.size(), "parallel import must not lose or duplicate rows");
        assertEquals(1, ids.get(0));
        assertEquals(rows, ids.get(rows - 1));
        ImportRowBatcher.ImportTuningSnapshot tuning = ImportRowBatcher.lastTuningSnapshot();
        assertTrue(tuning.batches() > 1, "the import must be split into several batches");
        assertTrue(tuning.peakInFlightBatches() > 1,
                "the producer must have more than one submitted batch in flight");
        assertTrue(tuning.gatePermits() <= Runtime.getRuntime().availableProcessors(),
                "the fan-out must never exceed the machine's available parallelism");
    }

    @Test
    void parallelImportUsesParsedRowsWithQuotedNewlines() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("1,\"Alice\nCooper\"", "2,Bob"));

        new CSVImporter().run(spec, contextFor(spec));

        assertEquals(List.of(1, 2), importedIds());
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT NAME FROM BULK_ROWS WHERE ID=1")) {
            assertTrue(rows.next());
            assertEquals("Alice\nCooper", rows.getString(1));
        }
        assertEquals(Math.min(4, Runtime.getRuntime().availableProcessors()),
                ImportRowBatcher.lastTuningSnapshot().workers());
    }

    @Test
    void excelPreservesSequentialFailureEvenWithAFastModeField() throws Exception {
        Path workbook = tempDirectory.resolve("bulk.xlsx");
        com.alibaba.excel.EasyExcel.write(workbook.toFile())
                .head(List.of(List.of("ID"), List.of("NAME")))
                .sheet().doWrite(List.of(List.of(1, "Alice"), List.of(1, "duplicate"), List.of(2, "Bob")));
        ImportTaskSpec spec = csvSpec(workbook);
        spec.setFormat("XLSX");

        assertThrows(TaskExecutionException.class,
                () -> new ai.chat2db.community.domain.core.impl.task.imports.excel.XLSXImporter().run(spec, contextFor(spec)));

        assertEquals(List.of(1), importedIds());
    }

    @Test
    void failedFinalBatchPropagatesWorkerFailureAndKeepsCommittedRows() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("1,ok", "1,duplicate", "2,ok"));
        TaskExecutionContextImpl context = contextFor(spec);

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, context));

        assertEquals(List.of(1, 2), importedIds(), "H2 commits successful statements in the failed JDBC batch");
        assertNull(context.artifactDraft());
        assertTrue(storage.events.stream().anyMatch(event -> "IMPORT_BATCH_FAILED".equals(event.getCode())));
        assertFalse(storage.events.stream().anyMatch(event -> "IMPORT_SUMMARY".equals(event.getCode())));
    }

    @Test
    void failedParallelBatchesReportFailureWithPartialWrites() throws Exception {
        String[] lines = new String[80_000];
        for (int index = 0; index < lines.length; index++) {
            // Every batch contains constraint violations, so the task must report failure.
            lines[index] = (index % 2) + ",ok";
        }
        ImportTaskSpec spec = csvSpec(writeCsv(lines));

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, contextFor(spec)));

        assertEquals(List.of(0, 1), importedIds());
        assertFalse(storage.events.stream().anyMatch(event -> "IMPORT_SUMMARY".equals(event.getCode())));
    }

    /**
     * Task storage good enough for the import pipeline; mirrors the stub used by
     * {@code CsvImportPipelineTest}.
     */
    private static final class InMemoryTaskStorage implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();

        private long sequence;

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId(1L);
            task.setStatus(TaskStatus.PENDING.name());
            tasks.add(task);
            createdEvent.setTaskId(task.getId());
            appendEvent(createdEvent);
            return task;
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return tasks.stream().filter(task -> task.getId().equals(taskId)).findFirst();
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            return PageResponse.of(tasks, (long) tasks.size(), 1, 20);
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            return true;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            return true;
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            event.setSequence(++sequence);
            events.add(event);
            return event;
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.getSequence() > afterSequence).limit(limit).toList();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return events.stream().filter(event -> beforeSequence == null || event.getSequence() < beforeSequence)
                    .toList();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            return false;
        }

    }
}
