package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.ImportCheckpoint;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end resume against a real JDBC target: a cancelled import keeps a readable checkpoint, and
 * continuing from it stores exactly the rows a single uninterrupted run would have stored.
 *
 * <p>The interruption happens between batches, which is the deterministic case: the running import
 * notices the cancellation at the next batch boundary. A cancellation in the middle of a batch can
 * leave driver-applied rows from that one batch uncounted, which the checkpoint cannot observe.
 */
class ImportResumePipelineTest {

    private static final String DB_TYPE = "IMPORT_RESUME_PIPELINE_TEST";

    private static final int ROWS = 2500;

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
        connection = DriverManager.getConnection("jdbc:h2:mem:import_resume_pipeline");
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
    void cancelledImportResumesToTheSameRowsAsASingleRun() throws Exception {
        Path csv = writeCsv(rowsCsv());
        ImportTaskSpec oneShot = csvSpec(csv);
        new CSVImporter().run(oneShot, contextFor(oneShot));
        List<Integer> expected = importedIds();
        assertEquals(ROWS, expected.size(), "the one shot run is the baseline");

        execute("DELETE FROM TARGET_ROWS");

        // 1) An import that is cancelled after its first committed batch.
        ImportTaskSpec interrupted = csvSpec(csv);
        Long interruptedTaskId = createRunningTask(interrupted);
        StoppingContext stopping = new StoppingContext(
                new TaskExecutionContextImpl(interruptedTaskId, new RunningTask(interruptedTaskId), storage,
                        new ArtifactServiceImpl()),
                storage, interruptedTaskId, 1);
        assertThrows(TaskCancelledException.class, () -> new CSVImporter().run(interrupted, stopping));

        assertEquals(1000, importedIds().size(), "only the committed batch is stored");
        assertEquals(TaskStatus.CANCELLED.name(), storage.get(interruptedTaskId).orElseThrow().getStatus());
        String checkpointJson = storage.checkpointOf(interruptedTaskId);
        assertNotNull(checkpointJson, "a cancelled task must still carry its checkpoint");
        assertEquals(1000L, ImportCheckpoint.fromJson(checkpointJson).processedRows());

        // 2) Resume reads the stored spec and checkpoint exactly like TaskService#resumeImport.
        String specJson = TaskServiceImpl.specJson(interrupted);
        ImportTaskSpec resumed = TaskServiceImpl.resumedSpec(specJson, checkpointJson);
        assertEquals(1000L, resumed.getResumeFromRow());
        assertEquals(interrupted.getSourceFile(), resumed.getSourceFile());
        assertEquals(interrupted.getColumnMappings().size(), resumed.getColumnMappings().size());

        Long resumedTaskId = createRunningTask(resumed);
        new CSVImporter().run(resumed, new TaskExecutionContextImpl(resumedTaskId, new RunningTask(resumedTaskId),
                storage, new ArtifactServiceImpl()));

        assertEquals(expected, importedIds(), "the resumed run stores every row exactly once");
        List<TaskEvent> events = storage.listEvents(resumedTaskId, 0L, 1000);
        TaskEvent resumeEvent = events.stream()
                .filter(event -> "IMPORT_RESUME_STARTED".equals(event.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals(1000L, ((Number) resumeEvent.getDetails().get("resumeFromRow")).longValue());
        assertEquals("INSERT", resumeEvent.getDetails().get("duplicatePolicy"),
                "a serial checkpoint is exact, so the resumed run keeps the original duplicate policy");
        assertTrue(resumeEvent.getMessage().contains("source file must be unchanged"), resumeEvent.getMessage());
    }

    @Test
    void resumeWithoutAStoredCheckpointIsRejectedBeforeAnythingRuns() throws Exception {
        Path csv = writeCsv(rowsCsv());
        ImportTaskSpec spec = csvSpec(csv);

        assertEquals("task.resume.missingCheckpoint",
                assertThrows(ai.chat2db.community.tools.exception.BusinessException.class,
                        () -> TaskServiceImpl.resumedSpec(TaskServiceImpl.specJson(spec), null)).getCode());
        assertEquals("task.resume.missingSpec",
                assertThrows(ai.chat2db.community.tools.exception.BusinessException.class,
                        () -> TaskServiceImpl.resumedSpec(null, "{\"processedRows\":5}")).getCode());
        assertEquals("task.resume.invalidSpec",
                assertThrows(ai.chat2db.community.tools.exception.BusinessException.class,
                        () -> TaskServiceImpl.resumedSpec("{not json", "{\"processedRows\":5}")).getCode());
        assertEquals("task.resume.invalidCheckpoint",
                assertThrows(ai.chat2db.community.tools.exception.BusinessException.class,
                        () -> TaskServiceImpl.resumedSpec(TaskServiceImpl.specJson(spec), "[]")).getCode());
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String rowsCsv() {
        StringBuilder content = new StringBuilder("ROW_ID,ROW_NAME\n");
        for (int id = 1; id <= ROWS; id++) {
            content.append(id).append(",ok\n");
        }
        return content.toString();
    }

    private Path writeCsv(String content) throws Exception {
        Path csv = tempDirectory.resolve("input.csv");
        Files.writeString(csv, content, StandardCharsets.UTF_8);
        return csv;
    }

    private ImportTaskSpec csvSpec(Path csv) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .taskName("import")
                .sourceFile(csv.toString())
                .format("CSV")
                .mode("STANDARD")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .columnMappings(List.of(
                        new ImportColumnMapping("ROW_ID", "ID"),
                        new ImportColumnMapping("ROW_NAME", "NAME")))
                .build();
    }

    private Long createRunningTask(ImportTaskSpec spec) {
        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("import")
                .target(spec.getTarget()).build(), TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        storage.compareAndSetStatus(taskId, TaskStatus.PENDING.name(), TaskStatus.RUNNING.name(),
                TaskStatusPatch.builder().build(), event("TASK_STARTED"));
        return taskId;
    }

    private TaskExecutionContextImpl contextFor(ImportTaskSpec spec) {
        Long taskId = createRunningTask(spec);
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

    /**
     * Cancels the run after {@code batchesBeforeStop} committed batches, persisting the terminal
     * status first - exactly the order {@code LocalTaskManager#cancelTask} uses, which is what makes
     * the per-batch checkpoint the only durable position a cancelled import can have.
     */
    private static final class StoppingContext implements TaskExecutionContext {

        private final TaskExecutionContextImpl delegate;
        private final InMemoryTaskStorage storage;
        private final Long taskId;
        private final RunningTask runningTask;
        private final int batchesBeforeStop;
        private int batches;

        private StoppingContext(TaskExecutionContextImpl delegate, InMemoryTaskStorage storage, Long taskId,
                int batchesBeforeStop) {
            this.delegate = delegate;
            this.storage = storage;
            this.taskId = taskId;
            this.batchesBeforeStop = batchesBeforeStop;
            this.runningTask = new RunningTask(taskId);
        }

        @Override
        public Long taskId() {
            return delegate.taskId();
        }

        @Override
        public void logInfo(String code, String message) {
            delegate.logInfo(code, message);
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
            delegate.logInfo(code, message, details);
            if ("BATCH_EXECUTED".equals(code)) {
                batches++;
                if (batches >= batchesBeforeStop) {
                    storage.compareAndSetStatus(taskId, TaskStatus.RUNNING.name(), TaskStatus.CANCELLED.name(),
                            TaskStatusPatch.builder().build(), event("TASK_CANCELLED"));
                    runningTask.requestCancellation(false);
                }
            }
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
            delegate.reportProgress(progress, stage, message);
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            delegate.logWarn(code, message, details);
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
            delegate.logError(code, message, details);
        }

        /** The cancelled token of the stopping context, not the delegate's unlimited one. */
        @Override
        public void checkCancelled() {
            if (runningTask.cancellationToken().isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new TaskCancelledException();
            }
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public void onStatementCreated(java.sql.Statement statement) {
            delegate.onStatementCreated(statement);
        }

        @Override
        public void onStatementClosed(java.sql.Statement statement) {
            delegate.onStatementClosed(statement);
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return delegate.createArtifact(outputDirectory, fileName, mediaType);
        }

        @Override
        public void write(String content) {
            delegate.write(content);
        }

        @Override
        public void saveCheckpoint(String checkpointJson) {
            delegate.saveCheckpoint(checkpointJson);
        }
    }

    /** Storage double with the checkpoint rules of the file storage: terminal tasks are rejected. */
    private static final class InMemoryTaskStorage implements TaskStorage {

        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, Task> tasks = new LinkedHashMap<>();
        private final Map<Long, List<TaskEvent>> events = new LinkedHashMap<>();
        private final Map<Long, String> checkpoints = new LinkedHashMap<>();
        private final Map<Long, String> errorArtifacts = new LinkedHashMap<>();

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId(ids.incrementAndGet());
            task.setStatus(TaskStatus.PENDING.name());
            tasks.put(task.getId(), task);
            createdEvent.setTaskId(task.getId());
            appendEvent(createdEvent);
            return task;
        }

        @Override
        public Optional<Task> get(Long taskId) {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public PageResponse<Task> list(TaskQuery query) {
            return PageResponse.of(List.copyOf(tasks.values()), (long) tasks.size(), 1, 20);
        }

        @Override
        public boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
                TaskStatusPatch patch, TaskEvent lifecycleEvent) {
            Task task = tasks.get(taskId);
            if (task == null || !expectedStatus.equals(task.getStatus())) {
                return false;
            }
            task.setStatus(targetStatus);
            if (lifecycleEvent != null) {
                lifecycleEvent.setTaskId(taskId);
                appendEvent(lifecycleEvent);
            }
            return true;
        }

        @Override
        public boolean updateProgressIfRunning(Long taskId, TaskProgress progress) {
            return true;
        }

        @Override
        public TaskEvent appendEvent(TaskEvent event) {
            List<TaskEvent> taskEvents = events.computeIfAbsent(event.getTaskId(), ignored -> new ArrayList<>());
            event.setSequence((long) taskEvents.size() + 1L);
            taskEvents.add(event);
            return event;
        }

        @Override
        public List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit) {
            return events.getOrDefault(taskId, List.of()).stream()
                    .filter(event -> event.getSequence() > afterSequence).limit(limit).toList();
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
            Task task = tasks.get(taskId);
            if (task == null || checkpointJson == null) {
                return false;
            }
            // Terminal tasks are accepted: cancellation persists the status before the workers drain.
            checkpoints.put(taskId, checkpointJson);
            return true;
        }

        @Override
        public boolean attachErrorArtifact(Long taskId, String errorArtifactId) {
            errorArtifacts.put(taskId, errorArtifactId);
            return true;
        }

        String checkpointOf(Long taskId) {
            return checkpoints.get(taskId);
        }
    }
}
