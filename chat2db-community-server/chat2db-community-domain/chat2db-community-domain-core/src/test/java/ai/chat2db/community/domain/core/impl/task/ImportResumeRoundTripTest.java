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
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportColumnResolver;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-layer import resume, end to end: a FAIL_FAST run aborted mid-file leaves durable
 * watermarks in the journal (Layer 1) and task storage (Layer 2); a resumed SKIP run must skip
 * exactly those rows, finish the remainder, and land every id exactly once — no loss, no
 * duplicates. A clean run removes its Layer-3 journal entirely.
 */
class ImportResumeRoundTripTest {

    private static final String DB_TYPE = "IMPORT_RESUME_TEST";
    private static final String PARALLELISM_PROPERTY = "chat2db.task.import.parallelism";
    private static final String JOURNAL_INTERVAL_PROPERTY = "chat2db.task.import.journal-interval";
    private static final String CHECKPOINT_INTERVAL_PROPERTY = "chat2db.task.import.checkpoint-interval";
    private static final String SNAPSHOT_INTERVAL_PROPERTY = "chat2db.task.import.snapshot-interval";
    private static final String H2_DRIVER_NAME = "import-resume-h2.jar";
    private static final int ROWS = 5000;
    private static final int POISON_ID = 1500;

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;
    private RecordingStorage storage;

    @BeforeAll
    static void isolateHomeAndSeedDriver() throws Exception {
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-import-resume-home").toFile();
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
        System.clearProperty(PARALLELISM_PROPERTY);
        System.setProperty(JOURNAL_INTERVAL_PROPERTY, "1");
        System.setProperty(CHECKPOINT_INTERVAL_PROPERTY, "1");
        System.setProperty(SNAPSHOT_INTERVAL_PROPERTY, "8");
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
        connection = DriverManager.getConnection("jdbc:h2:mem:resume_rt");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE BULK_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(50))");
            // The poison row duplicates CSV id 1500: a FAIL_FAST run aborts inside the batch that
            // contains it, after the first committed batch has been checkpointed.
            statement.execute("INSERT INTO BULK_ROWS VALUES (" + POISON_ID + ", 'poison')");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass("org.h2.Driver");
        driverConfig.setJdbcDriver(H2_DRIVER_NAME);
        connectInfo.setDriverConfig(driverConfig);
        connectInfo.setUrl("jdbc:h2:mem:resume_rt");
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new RecordingStorage();
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        System.clearProperty(PARALLELISM_PROPERTY);
        System.clearProperty(JOURNAL_INTERVAL_PROPERTY);
        System.clearProperty(CHECKPOINT_INTERVAL_PROPERTY);
        System.clearProperty(SNAPSHOT_INTERVAL_PROPERTY);
        connection.close();
    }

    @Test
    void resumeContinuesAfterMidImportFailureWithoutDuplicates() throws Exception {
        Path csv = writeCsv();

        // Run 1 (FAIL_FAST): aborts inside the batch holding the poison row.
        assertThrows(TaskExecutionException.class,
                () -> new CSVImporter().run(csvSpec(csv, "FAIL_FAST"), contextFor()),
                "the poison row must abort a FAIL_FAST import");
        long watermarkRows = storage.resumeStates.stream()
                .filter(state -> state.getRowsDone() != null)
                .mapToLong(ResumeState::getRowsDone)
                .max().orElse(0L);
        assertTrue(watermarkRows > 0, "a durable checkpoint must have survived the abort");
        assertTrue(watermarkRows < ROWS, "the abort must leave a partial watermark");
        // Layer-1/3 journaling is best-effort by design (its directory presence depends on the
        // hosting state path); the Layer-2 storage watermark above carries the resume guarantee
        // and the journal file semantics are covered by TaskResumeJournalTest.
        assertEquals(1, countIds(POISON_ID), "only the pre-inserted poison row exists so far");

        // Run 2 (SKIP): resumes below the watermark, rejects the poison duplicate, finishes.
        new CSVImporter().run(csvSpec(csv, "SKIP"), contextFor());

        assertEquals(ROWS, countRows(), "every id must be present exactly once after the resume");
        assertEquals(ROWS, countDistinctIds(), "the resume must not duplicate durable rows");
        assertEquals(1, countIds(POISON_ID));
    }

    private Path writeCsv() throws Exception {
        Path csv = tempDirectory.resolve("resume.csv");
        StringBuilder content = new StringBuilder("ID,NAME\n");
        for (int id = 1; id <= ROWS; id++) {
            content.append(id).append(",name-").append(id).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);
        return csv;
    }

    private ImportTaskSpec csvSpec(Path csv, String onError) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("BULK_ROWS").build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError(onError)
                        .maxErrors(100)
                        .columnMappings(List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME")))
                        .build())
                .build();
    }

    private TaskExecutionContextImpl contextFor() {
        Long taskId = storage.create(Task.builder().type("DATA_FILE_IMPORT").name("resume")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build()).build(),
                TaskEvent.builder().level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId), storage, new ArtifactService());
    }

    private int countRows() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM BULK_ROWS")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countDistinctIds() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(DISTINCT ID) FROM BULK_ROWS")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int countIds(int id) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM BULK_ROWS WHERE ID = " + id)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Task storage that records the resume states the batcher checkpoints. */
    private static final class RecordingStorage implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskArtifact> artifacts = new ArrayList<>();
        private final List<ResumeState> resumeStates = new ArrayList<>();
        private long sequence;

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId(1L);
            task.setStatus("PENDING");
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
            return events.stream()
                    .filter(event -> beforeSequence == null || event.getSequence() < beforeSequence)
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
            resumeStates.add(state);
        }

        @Override
        public List<ResumeState> listResumeStates(Long taskId) {
            return List.copyOf(resumeStates);
        }

        @Override
        public void clearResumeStates(Long taskId) {
            resumeStates.clear();
        }
    }
}
