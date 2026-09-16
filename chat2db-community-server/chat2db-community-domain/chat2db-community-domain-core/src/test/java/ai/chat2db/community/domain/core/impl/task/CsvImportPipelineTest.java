package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
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
 * CSV mapping and failure propagation against a real JDBC target.
 */
class CsvImportPipelineTest {

    private static final String DB_TYPE = "CSV_IMPORT_TEST";

    @TempDir
    Path tempDirectory;

    private java.sql.Connection connection;
    private IPlugin previousPlugin;
    private InMemoryTaskStorage storage;

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
        connection = DriverManager.getConnection("jdbc:h2:mem:csv_import");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(10))");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
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

    @Test
    void failedBatchPreservesCommittedRowsWithoutCreatingOutput() throws Exception {
        Path csv = writeCsv("ROW_ID,ROW_NAME,EXTRA\n1,ok,ignored\n2,this-value-is-too-long,x\n");
        ImportTaskSpec spec = csvSpec(csv);
        TaskExecutionContextImpl context = contextFor(spec);

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, context));

        assertEquals(List.of(1), importedIds(), "the executor must preserve rows already committed by the driver");
        assertNull(context.artifactDraft());
        assertFailedWithoutSummary();
    }

    @Test
    void preservesCommittedBatchAndStopsBeforeLaterBatches() throws Exception {
        StringBuilder content = new StringBuilder("ROW_ID,ROW_NAME\n");
        for (int id = 1; id <= 60_000; id++) {
            content.append(id == 25_000 ? 1 : id).append(",ok\n");
        }
        ImportTaskSpec spec = csvSpec(writeCsv(content.toString()));

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, contextFor(spec)));

        List<Integer> ids = importedIds();
        assertTrue(ids.containsAll(java.util.stream.IntStream.rangeClosed(1, 20_000).boxed().toList()));
        assertTrue(ids.contains(20_001), "the failed batch can leave committed rows");
        assertTrue(ids.stream().allMatch(id -> id <= 40_000), "a later batch must not be submitted");
        assertEquals(1, storage.events.stream().filter(event -> "BATCH_EXECUTED".equals(event.getCode())).count());
        assertFailedWithoutSummary();
    }

    @Test
    void legacySkipOptionCannotEnableErrorTolerance() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\n1,duplicate\n"));
        String json = com.alibaba.fastjson2.JSON.toJSONString(spec);
        ImportTaskSpec legacySpec = com.alibaba.fastjson2.JSON.parseObject(json.substring(0, json.length() - 1)
                + ",\"options\":{\"onError\":\"SKIP\",\"maxErrors\":100}}", ImportTaskSpec.class);

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(legacySpec, contextFor(legacySpec)));

        assertEquals(List.of(1), importedIds());
        assertFailedWithoutSummary();
    }

    @Test
    void existingCsvSettingsAndMappingsIgnoreLegacyNestedOptions() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID;ROW_NAME\n1;kept\n2;NULL\n"));
        CsvOptions csvOptions = CsvOptions.defaults();
        csvOptions.setDelimiter(";");
        csvOptions.setEmptyAsNull(false);
        spec.setCsvOptions(csvOptions);
        String json = com.alibaba.fastjson2.JSON.toJSONString(spec);
        ImportTaskSpec legacySpec = com.alibaba.fastjson2.JSON.parseObject(json.substring(0, json.length() - 1)
                + ",\"options\":{\"delimiter\":\",\",\"skipRows\":99,\"nullString\":\"NULL\","
                + "\"columnMappings\":[{\"sourceColumn\":\"ROW_NAME\",\"targetColumn\":\"ID\"}]}}",
                ImportTaskSpec.class);

        new CSVImporter().run(legacySpec, contextFor(legacySpec));

        assertEquals(List.of(1, 2), importedIds());
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT NAME FROM TARGET_ROWS ORDER BY ID")) {
            assertTrue(rows.next());
            assertEquals("kept", rows.getString(1));
            assertTrue(rows.next());
            assertEquals("NULL", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void ordinaryModesPreserveSequentialWritesBeforeFailure() throws Exception {
        for (String mode : java.util.Arrays.asList(null, "STANDARD", "unknown")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM TARGET_ROWS");
            }
            ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\n1,duplicate\n2,later\n"));
            spec.setMode(mode);

            assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, contextFor(spec)));

            assertEquals(List.of(1), importedIds(), "ordinary import preserves its executed prefix: " + mode);
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void ordinaryImportPreservesThousandRowBatchesAndProgress() throws Exception {
        StringBuilder content = new StringBuilder("ROW_ID,ROW_NAME\n");
        for (int id = 1; id <= 2001; id++) {
            content.append(id).append(",ok\n");
        }
        ImportTaskSpec spec = csvSpec(writeCsv(content.toString()));
        spec.setMode("STANDARD");

        new CSVImporter().run(spec, contextFor(spec));

        assertEquals(2001, importedIds().size());
        assertEquals(List.of(1000, 1000, 1), storage.events.stream()
                .filter(event -> "BATCH_EXECUTED".equals(event.getCode()) && event.getDetails() != null
                        && event.getDetails().containsKey("statementCount"))
                .map(event -> ((Number) event.getDetails().get("statementCount")).intValue()).toList());
        assertEquals(List.of(30, 40, 40), storage.progressUpdates.stream()
                .filter(progress -> "IMPORTING".equals(progress.getStage()))
                .map(TaskProgress::getProgress).toList());
    }

    private Path writeCsv(String content) throws Exception {
        Path csv = tempDirectory.resolve("input.csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private ImportTaskSpec csvSpec(Path csv) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .format("CSV")
                .mode("FAST")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .columnMappings(List.of(
                        new ImportColumnMapping("ROW_ID", "ID"),
                        new ImportColumnMapping("ROW_NAME", "NAME")))
                .build();
    }

    private TaskExecutionContextImpl contextFor(ImportTaskSpec spec) {
        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("import")
                .target(spec.getTarget()).build(), TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId), storage, new ArtifactServiceImpl());
    }

    private List<Integer> importedIds() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID FROM TARGET_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }

    private void assertFailedWithoutSummary() {
        List<String> codes = storage.events.stream().map(TaskEvent::getCode).toList();
        assertTrue(codes.contains("IMPORT_BATCH_FAILED"), codes.toString());
        assertFalse(codes.contains("IMPORT_SUMMARY"), codes.toString());
    }

    private static final class InMemoryTaskStorage implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskProgress> progressUpdates = new ArrayList<>();

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
            return false;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            progressUpdates.add(progress);
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
