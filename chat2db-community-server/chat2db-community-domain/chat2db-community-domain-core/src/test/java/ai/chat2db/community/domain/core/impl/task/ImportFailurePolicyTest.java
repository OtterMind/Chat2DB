package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
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
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpoint;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.task.imports.json.JSONImporter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rejected-rows file of both failure policies against a real JDBC target.
 *
 * <p>{@code ABORT} lists the whole rejected batch, because a parameterized batch reports one failure
 * for the batch. {@code SKIP_ERROR_ROWS} retries the batch row by row and lists only the rows that
 * really fail - which is only correct while the failed batch is rolled back as a whole, so the good
 * rows of that batch are stored exactly once.
 */
class ImportFailurePolicyTest {

    private static final String DB_TYPE = "IMPORT_FAILURE_POLICY_TEST";

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
        connection = DriverManager.getConnection("jdbc:h2:mem:import_failure_policy");
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
    void abortPolicyListsTheWholeRejectedBatchWithReasonAndValues() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\n2,this-value-is-too-long\n"));
        TaskExecutionContextImpl context = contextFor(spec);

        assertThrows(TaskExecutionException.class, () -> new CSVImporter().run(spec, context));

        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft, "a rejected batch must stage an error artifact");
        List<String> lines = readErrorRows(errorDraft);
        assertEquals("rowNumber,reason,content", lines.get(0));
        assertEquals(2, lines.size() - 1, lines.toString());
        // File row numbers, not batch indexes: the header is row 1, so the two data rows are 2 and 3.
        assertTrue(lines.get(1).startsWith("2,"), lines.get(1));
        assertTrue(lines.get(1).contains("1|ok"), lines.get(1));
        assertTrue(lines.get(2).startsWith("3,"), lines.get(2));
        assertTrue(lines.get(2).contains("this-value-is-too-long"), lines.get(2));
        assertTrue(lines.get(2).contains("import.sql.invalidValue"), lines.get(2));
        assertEquals(List.of(1), importedIds(), "the batch is committed before the failure is reported");
    }

    @Test
    void skipPolicyRetriesTheRejectedBatchRowByRowAndKeepsGoing() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv(
                "ROW_ID,ROW_NAME\n1,ok\n2,this-value-is-too-long\n3,fine\n"));
        spec.setFailurePolicy("SKIP_ERROR_ROWS");
        TaskExecutionContextImpl context = contextFor(spec);

        new CSVImporter().run(spec, context);

        assertEquals(List.of(1, 3), importedIds(), "the storable rows of the batch are stored exactly once");
        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft);
        List<String> lines = readErrorRows(errorDraft);
        assertEquals(1, lines.size() - 1, lines.toString());
        assertTrue(lines.get(1).startsWith("3,"), lines.get(1));
        assertTrue(lines.get(1).contains("this-value-is-too-long"), lines.get(1));
        assertTrue(storage.events.stream().anyMatch(event -> "IMPORT_ROWS_SKIPPED".equals(event.getCode())));
        // The checkpoint counts source rows, so the rejected row does not hold the position back.
        assertEquals(3L, ImportCheckpoint.fromJson(storage.lastCheckpoint).processedRows());
    }

    @Test
    void skipPolicyRecordsAValueThatCannotBeConvertedAndContinues() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\nnot-a-number,bad\n3,fine\n"));
        spec.setFailurePolicy("SKIP_ERROR_ROWS");
        TaskExecutionContextImpl context = contextFor(spec);

        new CSVImporter().run(spec, context);

        assertEquals(List.of(1, 3), importedIds(), "the unconvertible row must not fail the import");
        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft);
        List<String> lines = readErrorRows(errorDraft);
        assertEquals(1, lines.size() - 1, lines.toString());
        assertTrue(lines.get(1).startsWith("3,"), lines.get(1));
        assertTrue(lines.get(1).contains("not-a-number"), lines.get(1));
        assertTrue(lines.get(1).contains("import.value.invalidValue"), lines.get(1));
    }

    @Test
    void fastSkipPolicyRecordsAValueThatCannotBeConvertedAndContinues() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\nnot-a-number,bad\n3,fine\n"));
        spec.setMode("FAST");
        spec.setFailurePolicy("SKIP_ERROR_ROWS");
        TaskExecutionContextImpl context = contextFor(spec);

        new CSVImporter().run(spec, context);

        assertEquals(List.of(1, 3), importedIds(), "fast mode applies the same row policy");
        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft);
        List<String> lines = readErrorRows(errorDraft);
        assertEquals(1, lines.size() - 1, lines.toString());
        assertTrue(lines.get(1).startsWith("3,"), lines.get(1));
        assertTrue(lines.get(1).contains("import.value.invalidValue"), lines.get(1));
    }

    @Test
    void jsonSkipPolicyCountsARejectedRecordInTheResumablePosition(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("input.json");
        Files.writeString(input, "[{\"id\":1},{\"id\":\"bad\"},{\"id\":3}]", StandardCharsets.UTF_8);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(input.toString())
                .format("JSON")
                .mode("STANDARD")
                .failurePolicy("SKIP_ERROR_ROWS")
                .columnMappings(List.of(
                        new ImportColumnMapping("id", "ID"),
                        new ImportColumnMapping("name", "NAME")))
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .build();
        TaskExecutionContextImpl context = contextFor(spec);

        new JSONImporter().run(spec, context);

        assertEquals(List.of(1, 3), importedIds());
        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft);
        List<String> lines = readErrorRows(errorDraft);
        assertEquals(1, lines.size() - 1, lines.toString());
        assertTrue(lines.get(1).contains("import.value.invalidValue"), lines.get(1));
        assertEquals(3L, ImportCheckpoint.fromJson(storage.lastCheckpoint).processedRows(),
                "the rejected record must not hold the resumable position back");
    }

    @Test
    void abortPolicyFailsOnAnUnconvertibleValueAndRecordsTheRow() throws Exception {
        ImportTaskSpec spec = csvSpec(writeCsv("ROW_ID,ROW_NAME\n1,ok\nnot-a-number,bad\n3,fine\n"));
        TaskExecutionContextImpl context = contextFor(spec);

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new CSVImporter().run(spec, context));

        assertTrue(failure.publicMessage().contains("import.value.invalidValue"), failure.publicMessage());
        assertTrue(storage.events.stream().anyMatch(event -> "Rejected rows recorded".equals(event.getMessage())));
        assertTrue(storage.events.stream().noneMatch(event -> "Import finished with rejected rows".equals(event.getMessage())),
                "closing the error file during an aborted import must not announce import completion");
        ArtifactDraft errorDraft = context.errorArtifactDraft();
        assertNotNull(errorDraft);
        List<String> lines = readErrorRows(errorDraft);
        assertEquals(1, lines.size() - 1, lines.toString());
        assertTrue(lines.get(1).startsWith("3,"), lines.get(1));
        assertTrue(lines.get(1).contains("not-a-number"), lines.get(1));
    }

    @Test
    void skipPolicyStoresEveryRowExactlyOnceWhenNothingFails() throws Exception {
        StringBuilder content = new StringBuilder("ROW_ID,ROW_NAME\n");
        for (int id = 1; id <= 1500; id++) {
            content.append(id).append(",ok\n");
        }
        ImportTaskSpec spec = csvSpec(writeCsv(content.toString()));
        spec.setFailurePolicy("SKIP_ERROR_ROWS");

        new CSVImporter().run(spec, contextFor(spec));

        assertEquals(1500, importedIds().size());
        assertEquals(1500, importedIds().stream().distinct().count(),
                "the transactional batch must not change the happy path");
        assertTrue(connection.getAutoCommit(), "the batch transaction must be restored");
    }

    private List<String> readErrorRows(ArtifactDraft draft) throws Exception {
        List<String> lines = Files.readAllLines(draft.getTemporaryFile().toPath(), StandardCharsets.UTF_8);
        // The CSV writer may prefix a byte order mark for spreadsheet compatibility.
        if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
            lines.set(0, lines.get(0).substring(1));
        }
        return lines;
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
                .mode("STANDARD")
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
        storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().build(), event("TASK_STARTED"));
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

    private static TaskEvent event(String code) {
        return TaskEvent.builder().level("INFO").code(code).message(code).build();
    }

    /** Records checkpoints and error artifacts the way the file storage does. */
    private static final class InMemoryTaskStorage implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private String lastCheckpoint;

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
            Task task = get(taskId).orElse(null);
            if (task == null || !expectedStatus.equals(task.getStatus())) {
                return false;
            }
            task.setStatus(targetStatus);
            return true;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            return true;
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            event.setSequence((long) events.size() + 1L);
            events.add(event);
            return event;
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.getSequence() > afterSequence).limit(limit).toList();
        }

        @Override
        public List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit) {
            return List.of();
        }

        @Override
        public List<Task> listNonTerminalTasks() {
            return List.of();
        }

        @Override
        public boolean deleteTerminalTask(Long taskId, Runnable commitAction) {
            return false;
        }

        @Override
        public boolean saveCheckpoint(Long taskId, String checkpointJson) {
            Task task = get(taskId).orElse(null);
            if (task == null || TaskStatus.isTerminal(task.getStatus())) {
                return false;
            }
            lastCheckpoint = checkpointJson;
            return true;
        }
    }
}
