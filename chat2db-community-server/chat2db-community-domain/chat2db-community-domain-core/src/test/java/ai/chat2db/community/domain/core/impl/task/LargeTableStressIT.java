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
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.ArtifactService;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.imports.ImportRowBatcher;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.plugin.mysql.MysqlDBManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import ai.chat2db.community.domain.core.impl.task.ArtifactServiceImpl;

/**
 * Ten-million-row stress test of the parallel export/import pipeline against a real local MySQL.
 * The source table is seeded server-side with a recursive CTE, exported to CSV through the
 * parallel keyset shard path (multiple workers, adaptive gate and batch size), then imported back
 * through the parallel {@code ImportRowBatcher}; row count, key uniqueness and the SUM of VAL must
 * survive the round trip. Row count defaults to 10,000,000 and can be lowered through the
 * {@code C2D_STRESS_ROWS} environment variable for a quicker smoke.
 *
 * <p>Skips entirely unless MySQL credentials are provided through environment variables
 * (same contract as {@link MySQLTaskRoundTripIT}): C2D_MYSQL_HOST, C2D_MYSQL_PORT,
 * C2D_MYSQL_USER, C2D_MYSQL_PASSWORD (required), C2D_MYSQL_DB (default c2d_stress).
 */
class LargeTableStressIT {

    private static final String DB_TYPE = "MYSQL_STRESS_IT";

    private static final String MYSQL_DRIVER_NAME = "mysql-it-connector.jar";

    private static String previousUserHome;

    @TempDir
    Path tempDirectory;

    private Connection connection;
    private IPlugin previousPlugin;
    private final IDbManager mysqlDbManager = new MysqlDBManager();
    private StorageStub storage;
    private String url;
    private String database;
    private int rows;

    @BeforeAll
    static void seedMysqlDriver() throws Exception {
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-mysql-stress-home").toFile();
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

    @BeforeEach
    void setUp() throws Exception {
        String host = envOr("C2D_MYSQL_HOST", "127.0.0.1");
        String port = envOr("C2D_MYSQL_PORT", "3306");
        String user = envOr("C2D_MYSQL_USER", "root");
        String password = System.getenv("C2D_MYSQL_PASSWORD");
        database = envOr("C2D_MYSQL_DB", "c2d_stress");
        assumeTrue(password != null && !password.isBlank(),
                "C2D_MYSQL_PASSWORD is not set; local MySQL stress test skipped");
        rows = Integer.parseInt(envOr("C2D_STRESS_ROWS", "10000000"));

        // The seeding connection must not carry a socket timeout: one server-side
        // INSERT...SELECT easily runs longer than any per-statement client budget.
        String bootstrapUrl = "jdbc:mysql://" + host + ":" + port + "/?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000";
        connection = DriverManager.getConnection(bootstrapUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4");
            statement.execute("USE " + database);
            statement.execute("CREATE TABLE C2D_STRESS (ID INT PRIMARY KEY, NAME VARCHAR(32), VAL INT)");
            statement.execute("SET SESSION cte_max_recursion_depth = 10000000");
            long seedStarted = System.nanoTime();
            statement.execute("INSERT INTO C2D_STRESS (ID, NAME, VAL) "
                    + "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < " + rows + ") "
                    + "SELECT n, CONCAT('row-', n), n * 2 FROM seq");
            System.out.printf("[stress] seeded %,d rows in %.1fs%n",
                    rows, (System.nanoTime() - seedStarted) / 1_000_000_000.0D);
        }
        url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true"
                + "&useSSL=false&serverTimezone=UTC&connectTimeout=5000&socketTimeout=600000"
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
        Long taskId = storage.create(Task.builder().type("STRESS_IT").name("mysql-stress")
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).build()).build(),
                TaskEvent.builder()
                .level("INFO").code("TASK_CREATED").message("created").build()).getId();
        return new TaskExecutionContextImpl(taskId, new RunningTask(taskId, () -> { }), storage, new ArtifactServiceImpl());
    }

    @Test
    void stressTenMillionRowExportImportRoundTrip() throws Exception {
        File artifact = tempDirectory.resolve("stress.csv").toFile();

        // 1) Parallel shard export of the ten-million-row table. The exporter is instantiated
        // outside Spring, so the @Value-managed fan-out keeps its conservative default of 1;
        // pin it to 0 (automatic: min(16, cores)) exactly like a managed Community runtime.
        RecordingContext exportContext = new RecordingContext();
        CsvStressExporter exporter = new CsvStressExporter();
        setShardMaxParallelism(exporter, 0);
        long exportStarted = System.nanoTime();
        exporter.run(exportSpec(), exportContext, artifact);
        double exportSeconds = (System.nanoTime() - exportStarted) / 1_000_000_000.0D;

        long dataLines = 0;
        String first = null;
        String last = null;
        try (BufferedReader reader = Files.newBufferedReader(artifact.toPath(), StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            assertEquals("ID,NAME,VAL", line == null ? null : line.replace("﻿", ""), "CSV header");
            while ((line = reader.readLine()) != null) {
                if (first == null) {
                    first = line;
                }
                last = line;
                dataLines++;
            }
        }
        assertEquals(rows, dataLines, "exported data rows");
        assertEquals("1,row-1,2", first, "first exported row");
        assertEquals(rows + ",row-" + rows + "," + (rows * 2L), last, "last exported row");
        long shardThreads = exportContext.workerThreads.stream()
                .filter(name -> name.startsWith("chat2db-shard-")).count();
        if ("ULTRA_FAST".equals(envOr("C2D_STRESS_MODE", "ULTRA_FAST"))) {
            assertTrue(shardThreads >= 2, "parallel shard workers must be used, saw " + shardThreads);
        } else {
            assertEquals(0, shardThreads, "standard mode must stay on the serial path");
        }
        long csvBytes = Files.size(artifact.toPath());
        BaseExporter.ShardTuningStats shardStats = exporter.lastShardTuningStats();
        System.out.printf("[stress] export %,d rows (%.1f MB) in %.1fs -> %,.0f rows/s, %d shard threads, "
                        + "final batch size=%d, gate permits=%d%n",
                rows, csvBytes / 1_048_576.0D, exportSeconds, rows / exportSeconds, shardThreads,
                shardStats == null ? -1 : shardStats.batchSize,
                shardStats == null ? -1 : shardStats.gatePermits);

        // 2) Parallel import of the exported CSV back into a fresh table. No parallelism pin: the
        // adaptive worker band [2, min(16, cores)] and the AIMD gate tune themselves on this box.
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE C2D_STRESS_RT (ID INT PRIMARY KEY, NAME VARCHAR(32), VAL INT)");
        }
        long importStarted = System.nanoTime();
        importCsvInto("C2D_STRESS_RT", artifact);
        double importSeconds = (System.nanoTime() - importStarted) / 1_000_000_000.0D;
        ImportRowBatcher.ImportTuningSnapshot importStats = ImportRowBatcher.lastTuningSnapshot();
        System.out.printf("[stress] import %,d rows in %.1fs -> %,.0f rows/s, final batch size=%d, "
                        + "gate permits=%d%n",
                rows, importSeconds, rows / importSeconds,
                importStats == null ? -1 : importStats.batchSize(),
                importStats == null ? -1 : importStats.gatePermits());

        // 3) Round-trip integrity on the server.
        assertEquals(rows, countRows("C2D_STRESS_RT"), "imported row count");
        try (Statement statement = connection.createStatement();
             ResultSet sums = statement.executeQuery(
                     "SELECT (SELECT SUM(VAL) FROM C2D_STRESS), (SELECT SUM(VAL) FROM C2D_STRESS_RT), "
                             + "(SELECT COUNT(DISTINCT ID) FROM C2D_STRESS_RT)")) {
            assertTrue(sums.next());
            assertEquals(sums.getLong(1), sums.getLong(2), "VAL sums must match after the round trip");
            assertEquals(rows, sums.getLong(3), "imported ids must be unique");
        }
    }

    private static void setShardMaxParallelism(BaseExporter exporter, int value) throws Exception {
        java.lang.reflect.Field field = BaseExporter.class.getDeclaredField("shardMaxParallelism");
        field.setAccessible(true);
        field.setInt(exporter, value);
    }

    private ExportTaskSpec exportSpec() {
        return ExportTaskSpec.builder()
                .taskType("TABLE_DATA_EXPORT")
                .format("CSV")
                .tableNames(List.of("C2D_STRESS"))
                .mode(envOr("C2D_STRESS_MODE", "ULTRA_FAST"))
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName("C2D_STRESS").build())
                .build();
    }

    private void importCsvInto(String tableName, File csv) {
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(csv.getAbsolutePath())
                .importFileId("stress-it-staged")
                .format("CSV")
                .mode(envOr("C2D_STRESS_MODE", "ULTRA_FAST"))
                .confirmedNoStrongRelations(true)
                .target(TaskTargetSnapshot.builder().dataSourceId(1L).databaseName(database)
                        .tableName(tableName).build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .delimiter(",")
                        .onError("ABORT")
                        .columnMappings(List.of(
                                new ImportColumnMapping("ID", "ID"),
                                new ImportColumnMapping("NAME", "NAME"),
                                new ImportColumnMapping("VAL", "VAL")))
                        .build())
                .mode(envOr("C2D_STRESS_MODE", "ULTRA_FAST"))
                .build();
        new CSVImporter().run(spec, contextFor());
    }

    private long countRows(String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static final class CsvStressExporter extends BaseExporter {

        private CsvStressExporter() {
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

        private final Set<String> workerThreads = ConcurrentHashMap.newKeySet();

        @Override
        public Long taskId() {
            return 1L;
        }

        @Override
        public void checkpoint(ResumeState state) {
        }

        @Override
        public List<ResumeState> resumeStates() {
            return List.of();
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
        public void onStatementCreated(java.sql.Statement statement) {
            workerThreads.add(Thread.currentThread().getName());
        }

        @Override
        public void onStatementClosed(java.sql.Statement statement) {
        }
    }

    /**
     * Task storage good enough for the pipeline; mirrors the stub used by
     * {@code MySQLTaskRoundTripIT}.
     */
    private static final class StorageStub implements TaskStorage {

        private final List<Task> tasks = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskArtifact> artifacts = new ArrayList<>();
        private final List<ResumeState> states = new ArrayList<>();
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
