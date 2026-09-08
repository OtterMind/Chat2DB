package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStage;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CSV import path end to end: commons-csv grammar, explicit column mapping, error tolerance
 * with a REJECT sub-artifact, and true batched inserts against the target table.
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
    void skipsBadRowsIntoRejectArtifactAndMapsColumnsExplicitly() throws Exception {
        Path csv = tempDirectory.resolve("input.csv");
        Files.writeString(csv, "ROW_ID,ROW_NAME,EXTRA\n1,ok,ignored\n2,this-value-is-too-long,x\n",
                StandardCharsets.UTF_8);

        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("TARGET_ROWS").build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError("SKIP")
                        .maxErrors(5)
                        .columnMappings(List.of(
                                new ImportColumnMapping("ROW_ID", "ID"),
                                new ImportColumnMapping("ROW_NAME", "NAME")))
                        .build())
                .build();

        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("import")
                .target(spec.getTarget()).build(), TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        TaskExecutionContextImpl context = new TaskExecutionContextImpl(taskId, new RunningTask(taskId),
                storage, new ArtifactService());

        new CSVImporter().run(spec, context);

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID FROM TARGET_ROWS ORDER BY ID")) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            assertEquals(List.of(1), ids, "the over-long value row must be rejected, not truncated");
        }

        List<Path> rejectDrafts;
        try (var files = Files.list(tempDirectory)) {
            rejectDrafts = files.filter(path -> path.getFileName().toString().contains("rejects.ndjson"))
                    .toList();
        }
        assertEquals(1, rejectDrafts.size());
        String rejects = Files.readString(rejectDrafts.get(0), StandardCharsets.UTF_8);
        assertTrue(rejects.contains("this-value-is-too-long"), rejects);
        assertTrue(rejects.contains("\"row\":2"), rejects);

        List<String> codes = storage.listEvents(taskId, 0L, 100).stream().map(TaskEvent::getCode).toList();
        assertTrue(codes.contains("IMPORT_COLUMN_MAPPING"), "unmatched EXTRA column reported: " + codes);
        assertTrue(codes.contains("IMPORT_ROW_REJECTED"), codes.toString());
        assertTrue(codes.contains("IMPORT_SUMMARY"), codes.toString());
    }

    /**
     * Task storage good enough for the import pipeline: the interesting behaviour is the events
     * and the reject artifact it records.
     */
    private static final class InMemoryTaskStorage implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskArtifact> artifacts = new ArrayList<>();
        private final List<ResumeState> states = new ArrayList<>();
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

        @Override
        public List<TaskArtifact> listArtifacts(Long taskId) {
            return List.copyOf(artifacts);
        }

        @Override
        public void saveArtifact(Long taskId, TaskArtifact artifact) {
            artifacts.add(artifact);
        }

        @Override
        public void deleteArtifact(Long taskId, String artifactId) {
            artifacts.removeIf(artifact -> artifact.getArtifactId().equals(artifactId));
        }

        @Override
        public List<Task> listResumableTasks() {
            return List.of();
        }

        @Override
        public void saveResumeState(Long taskId, ResumeState state) {
            states.add(state);
        }

        @Override
        public List<ResumeState> listResumeStates(Long taskId) {
            return List.copyOf(states);
        }

        @Override
        public void clearResumeStates(Long taskId) {
            states.clear();
        }

    }
}
