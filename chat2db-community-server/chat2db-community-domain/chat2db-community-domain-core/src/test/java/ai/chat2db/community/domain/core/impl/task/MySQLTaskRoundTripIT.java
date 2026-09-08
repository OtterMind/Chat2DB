package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
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
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.plugin.mysql.MysqlDBManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.imports.ImportResourceSnapshot;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Local-MySQL integration test for the task pipeline: a CSV export/import round trip, a parallel
 * (multi-worker) import against real MySQL connections, and a checkpointed export interrupted
 * mid-run and resumed from its durable checkpoint.
 *
 * <p>The test talks to a dedicated local database and skips entirely unless MySQL credentials
 * are provided through environment variables:
 * <pre>
 *   C2D_MYSQL_HOST (default 127.0.0.1), C2D_MYSQL_PORT (default 3306),
 *   C2D_MYSQL_USER (default root), C2D_MYSQL_PASSWORD (required),
 *   C2D_MYSQL_DB (default c2d_task_it; dropped and recreated)
 * </pre>
 */
class MySQLTaskRoundTripIT {

    private static final String DB_TYPE = "MYSQL_TASK_IT";

    private static final String MYSQL_DRIVER_NAME = "mysql-it-connector.jar";

    private static String previousUserHome;

    private static final int ROWS = 1000;

    @TempDir
    Path tempDirectory;

    @BeforeAll
    static void seedMysqlDriver() throws Exception {
        // JdbcJarUtils cannot load an absolute jar path (it treats the input as a URI), so copy
        // the MySQL driver into the driver library and reference it by file name, mirroring
        // ShardedKeysetExportTest.
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-mysql-it-home").toFile();
        System.setProperty("user.home", tempHome.getAbsolutePath());
        File libDir = new File(JdbcDriverConstants.DRIVER_LIB_PATH);
        libDir.mkdirs();
        File mysqlJar = new File(com.mysql.cj.jdbc.Driver.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        Files.copy(mysqlJar.toPath(), new File(libDir, MYSQL_DRIVER_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    @AfterAll
    static void restoreHome() {
        System.setProperty("user.home", previousUserHome);
    }

    private Connection connection;
    private IPlugin previousPlugin;
    private StorageStub storage;
    private final IDbManager mysqlDbManager = new MysqlDBManager();
    private String url;
    private String database;

    @BeforeEach
    void setUp() throws Exception {
        String host = envOr("C2D_MYSQL_HOST", "127.0.0.1");
        String port = envOr("C2D_MYSQL_PORT", "3306");
        String user = envOr("C2D_MYSQL_USER", "root");
        String password = System.getenv("C2D_MYSQL_PASSWORD");
        database = envOr("C2D_MYSQL_DB", "c2d_task_it");
        assumeTrue(password != null && !password.isBlank(),
                "C2D_MYSQL_PASSWORD is not set; local MySQL integration test skipped");

        String bootstrapUrl = "jdbc:mysql://" + host + ":" + port + "/?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=120000"
                + "&rewriteBatchedStatements=true";
        connection = DriverManager.getConnection(bootstrapUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            statement.execute("USE " + database);
            statement.execute("CREATE TABLE C2D_SRC (ID INT PRIMARY KEY, NAME VARCHAR(50), VAL INT)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO C2D_SRC (ID, NAME, VAL) VALUES (?, ?, ?)")) {
            for (int id = 1; id <= ROWS; id++) {
                insert.setInt(1, id);
                insert.setString(2, "row-" + id);
                insert.setInt(3, id * 2);
                insert.addBatch();
                if (id % 200 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }

        url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=120000"
                + "&rewriteBatchedStatements=true";

        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(mysqlDriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService();
            }

            @Override
            public IDbManager getDbManager() {
                return mysqlDbManager;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setUrl(url);
        connectInfo.setUser(user);
        connectInfo.setPassword(password);
        connectInfo.setDriverConfig(mysqlDriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
        storage = new StorageStub();
        System.setProperty("chat2db.task.import.parallel.min-bytes", "1");
        System.setProperty("chat2db.task.import.parallel.min-rows", "1");
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
        System.clearProperty("chat2db.task.import.parallelism");
        System.clearProperty("chat2db.task.import.parallel.min-bytes");
        System.clearProperty("chat2db.task.import.parallel.min-rows");
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
            } catch (Exception cleanupFailure) {
                // the database is a named local fixture; leave it but never mask the test result
            }
            connection.close();
        }
    }

    private static DriverConfig mysqlDriverConfig() {
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriver(MYSQL_DRIVER_NAME);
        driverConfig.setJdbcDriverClass("com.mysql.cj.jdbc.Driver");
        return driverConfig;
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private TaskExecutionContextImpl contextFor() {
        Long taskId = storage.create(Task.builder().type("TASK_IT").name("mysql-it")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build()).build(),
                TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId), storage, new ArtifactService());
    }

    private ExportTaskSpec exportSpec(String tableName, Integer checkpointRows) {
        return ExportTaskSpec.builder()
                .taskType("TABLE_DATA_EXPORT")
                .format("CSV")
                .tableNames(List.of(tableName))
                .checkpointRows(checkpointRows)
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName(tableName).build())
                .build();
    }

    private List<Integer> exportedIds(File artifact) throws Exception {
        List<String> lines = Files.readAllLines(artifact.toPath(), StandardCharsets.UTF_8);
        assertEquals("ID,NAME,VAL", lines.get(0).replace("﻿", ""), "CSV header");
        assertEquals(1, lines.stream().filter(line -> line.replace("﻿", "").equals("ID,NAME,VAL")).count(),
                "header must appear exactly once");
        return lines.subList(1, lines.size()).stream()
                .map(line -> Integer.parseInt(line.substring(0, line.indexOf(','))))
                .toList();
    }

    private void createCopyTable(String tableName) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tableName
                    + " (ID INT PRIMARY KEY, NAME VARCHAR(50), VAL INT)");
        }
    }

    private long countRows(String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void importCsvInto(String tableName, File csv) {
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.getAbsolutePath())
                .importFileId("mysql-roundtrip-staged")
                .format("CSV")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName(tableName).build())
                .mode("ULTRA_FAST")
                .confirmedNoStrongRelations(true)
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError("ABORT")
                        .columnMappings(List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME"),
                                new ImportColumnMapping("VAL", "VAL")))
                        .build())
                .build();
        new CSVImporter().run(spec, contextFor());
    }

    @Test
    void csvExportImportRoundTripPreservesData() throws Exception {
        File artifact = tempDirectory.resolve("roundtrip.csv").toFile();
        new CsvITExporter().run(exportSpec("C2D_SRC", null), contextFor(), artifact);

        List<Integer> ids = exportedIds(artifact);
        assertEquals(ROWS, ids.size());
        assertEquals(1, ids.get(0));
        assertEquals(ROWS, ids.get(ROWS - 1));

        createCopyTable("C2D_RT");
        importCsvInto("C2D_RT", artifact);

        assertEquals(ROWS, countRows("C2D_RT"));
        try (Statement statement = connection.createStatement();
             ResultSet sums = statement.executeQuery(
                     "SELECT (SELECT SUM(VAL) FROM C2D_SRC), (SELECT SUM(VAL) FROM C2D_RT)")) {
            assertTrue(sums.next());
            assertEquals(sums.getLong(1), sums.getLong(2), "VAL sums must match after the round trip");
        }
    }

    @Test
    void parallelImportHandlesMysqlTarget() throws Exception {
        System.setProperty("chat2db.task.import.parallelism", "4");
        Path csv = tempDirectory.resolve("parallel.csv");
        StringBuilder content = new StringBuilder("ID,NAME,VAL\n");
        for (int id = 1; id <= ROWS * 2; id++) {
            content.append(id).append(",par-").append(id).append(',').append(id * 3).append('\n');
        }
        Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);

        createCopyTable("C2D_PAR");
        importCsvInto("C2D_PAR", csv.toFile());

        assertEquals(ROWS * 2, countRows("C2D_PAR"), "parallel workers must insert every row exactly once");
        try (Statement statement = connection.createStatement();
             ResultSet distinct = statement.executeQuery("SELECT COUNT(DISTINCT ID) FROM C2D_PAR")) {
            assertTrue(distinct.next());
            assertEquals(ROWS * 2, distinct.getLong(1), "no duplicated ids");
        }
    }

    @Test
    void interruptedCheckpointedExportResumesAgainstMysql() throws Exception {
        File artifact = tempDirectory.resolve("resumable.csv").toFile();
        RecordingContext first = new RecordingContext(2);

        assertThrows(TaskCancelledException.class,
                () -> new CsvITExporter().run(exportSpec("C2D_SRC", 100), first, artifact));
        assertTrue(first.checkpointCalls >= 2, "at least two checkpoint writes before cancellation");
        ResumeState last = first.saved.get(first.saved.size() - 1);
        assertNotNull(last.getBytesDone(), "durable byte count recorded");
        assertNotNull(last.getCursorJson(), "keyset cursor recorded");

        RecordingContext second = new RecordingContext(Integer.MAX_VALUE);
        second.resumeStates.addAll(first.saved);
        new CsvITExporter().run(exportSpec("C2D_SRC", 100), second, artifact);

        List<Integer> ids = exportedIds(artifact);
        assertEquals(ROWS, ids.size(), "resumed export must produce every row exactly once");
        assertEquals(1, ids.get(0));
        assertEquals(ROWS, ids.get(ROWS - 1));
    }

    @Test
    void mysqlResourceProbeReadsLiveCapacityReplicationAndTriggerFacts() {
        ImportResourceSnapshot resources = mysqlDbManager.probeImportResources(connection, database, null);

        assertTrue(resources.connectionCapacityKnown());
        assertTrue(resources.maxConnections() > resources.activeConnections());
        assertTrue(resources.replicationStatusKnown());
        assertEquals(0, resources.triggerCount());
        assertTrue(!resources.diskCapacityKnown());
    }

    private static final class CsvITExporter extends BaseExporter {

        private CsvITExporter() {
            super(new ExportCellProcessorChain(List.of()), new SqlExecutionPolicyManager(List.of()));
            this.suffix = ".csv";
        }

        @Override
        public String type() {
            return "csv";
        }

        @Override
        protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
                java.io.OutputStream output, boolean resuming) {
            streamTable(spec, tableName, context, output,
                    (stream, effectiveSpec, effectiveTable, resume) ->
                            new ai.chat2db.community.domain.core.impl.task.export.sink.CsvSink(
                                    stream, true, resume),
                    ExportValueMode.NATIVE, 2,
                    new ExportProgressLogger(context, "CSV", tableName), resuming);
        }
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final List<ResumeState> saved = new ArrayList<>();
        private final List<ResumeState> resumeStates = new ArrayList<>();
        private final int cancelAfterCheckpoints;
        private int checkpointCalls;

        private RecordingContext(int cancelAfterCheckpoints) {
            this.cancelAfterCheckpoints = cancelAfterCheckpoints;
        }

        @Override
        public Long taskId() {
            return 99L;
        }

        @Override
        public List<ResumeState> resumeStates() {
            return List.copyOf(resumeStates);
        }

        @Override
        public void checkpoint(ResumeState state) {
            checkpointCalls++;
            saved.removeIf(existing -> existing.getShardNo().equals(state.getShardNo()));
            saved.add(state);
            if (checkpointCalls >= cancelAfterCheckpoints) {
                throw new TaskCancelledException();
            }
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return createArtifact(ai.chat2db.community.domain.api.model.task.TaskArtifactRole.OUTPUT,
                    outputDirectory, fileName, mediaType);
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
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
    }

    private static final class StorageStub implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<ResumeState> states = new ArrayList<>();
        private long sequence;

        @Override
        public Task create(Task task, TaskEvent createdEvent) {
            task.setId((long) (tasks.size() + 1));
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
            return List.of();
        }

        @Override
        public void saveArtifact(Long taskId, TaskArtifact artifact) {
        }

        @Override
        public void deleteArtifact(Long taskId, String artifactId) {
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
