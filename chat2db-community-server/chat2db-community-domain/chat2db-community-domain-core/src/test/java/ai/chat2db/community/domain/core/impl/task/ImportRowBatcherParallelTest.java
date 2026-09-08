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
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.api.model.task.TaskStage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parallel import path end to end: multiple workers, each on its own dedicated connection,
 * execute partitioned batches while the caller keeps producing. Verifies that parallel workers
 * insert every row exactly once and that SKIP replay distinguishes bad data from connection
 * failures.
 */
class ImportRowBatcherParallelTest {

    private static final String DB_TYPE = "PARALLEL_IMPORT_TEST";

    private static final String PARALLELISM_PROPERTY = "chat2db.task.import.parallelism";
    private static final String MIN_BYTES_PROPERTY = "chat2db.task.import.parallel.min-bytes";
    private static final String MIN_ROWS_PROPERTY = "chat2db.task.import.parallel.min-rows";

    private static final String H2_DRIVER_NAME = "parallel-import-test-h2.jar";

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private java.sql.Connection connection;
    private IPlugin previousPlugin;
    private InMemoryTaskStorage storage;
    private String previousParallelism;
    private String previousMinBytes;
    private String previousMinRows;

    @BeforeAll
    static void isolateHomeAndSeedDriver() throws Exception {
        // JdbcJarUtils resolves driver names against the driver library under user.home and
        // cannot load an absolute jar path, so seed a copy of the H2 jar and isolate the home
        // directory exactly like ShardedKeysetExportTest does.
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
        previousParallelism = System.clearProperty(PARALLELISM_PROPERTY);
        previousMinBytes = System.setProperty(MIN_BYTES_PROPERTY, "0");
        previousMinRows = System.setProperty(MIN_ROWS_PROPERTY, "0");
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
        if (previousParallelism == null) {
            System.clearProperty(PARALLELISM_PROPERTY);
        } else {
            System.setProperty(PARALLELISM_PROPERTY, previousParallelism);
        }
        restoreProperty(MIN_BYTES_PROPERTY, previousMinBytes);
        restoreProperty(MIN_ROWS_PROPERTY, previousMinRows);
        connection.close();
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
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
                storage, new ArtifactService());
    }

    private ImportTaskSpec csvSpec(Path csv, String onError) {
        return ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.toString())
                .importFileId("parallel-import-fixture")
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("BULK_ROWS").build())
                .mode("ULTRA_FAST")
                .confirmedNoStrongRelations(true)
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError(onError)
                        .maxErrors(1000)
                        .columnMappings(List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME")))
                        .build())
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
        System.setProperty(PARALLELISM_PROPERTY, "4");
        int rows = 2000;
        String[] lines = new String[rows];
        for (int index = 0; index < rows; index++) {
            lines[index] = (index + 1) + ",name-" + (index + 1);
        }
        Path csv = writeCsv(lines);
        ImportTaskSpec spec = csvSpec(csv, "FAIL_FAST");

        new CSVImporter().run(spec, contextFor(spec));

        List<Integer> ids = importedIds();
        assertEquals(rows, ids.size(), "parallel import must not lose or duplicate rows");
        assertEquals(1, ids.get(0));
        assertEquals(rows, ids.get(rows - 1));
        assertTrue(ImportRowBatcher.lastTuningSnapshot().peakInFlightBatches() > 1,
                "the producer must have more than one submitted batch in flight");
    }

    @Test
    void skipReplayRejectsConsecutiveBadRowsWithoutAbortingHealthyRows() throws Exception {
        System.setProperty(PARALLELISM_PROPERTY, "2");
        Path csv = writeCsv("1,ok", "1,dup-a", "1,dup-b", "1,dup-c", "2,ok");
        ImportTaskSpec spec = csvSpec(csv, "SKIP");

        new CSVImporter().run(spec, contextFor(spec));

        List<Integer> ids = importedIds();
        assertEquals(List.of(1, 2), ids,
                "adjacent constraint violations are rejected without hiding healthy rows");
    }

    @Test
    void isolatedBadRowsAreStillSkippedWhenSurroundedBySuccessfulRows() throws Exception {
        System.setProperty(PARALLELISM_PROPERTY, "2");
        Path csv = writeCsv("1,ok", "1,isolated-dup", "2,ok", "3,ok");
        ImportTaskSpec spec = csvSpec(csv, "SKIP");

        new CSVImporter().run(spec, contextFor(spec));

        List<Integer> ids = importedIds();
        assertEquals(List.of(1, 2, 3), ids, "the isolated duplicate row is rejected, others imported");
    }

    /**
     * Task storage good enough for the import pipeline; mirrors the stub used by
     * {@code CsvImportPipelineTest}.
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
