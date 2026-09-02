package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.sink.CsvSink;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parallel keyset shards must still produce one artifact in strict key order: workers fill
 * per-shard queues and the caller drains them shard by shard into the single sink. Each worker
 * opens its own JDBC connection through the standard driver manager, like production shards do.
 */
class ShardedKeysetExportTest {

    private static final String DB_TYPE = "SHARD_EXPORT_TEST";
    private static final int ROWS = 20_001;
    private static final String H2_DRIVER_NAME = "shard-test-h2.jar";

    private static String previousUserHome;

    private Connection connection;
    private IPlugin previousPlugin;

    @BeforeAll
    static void isolateHomeAndSeedDriver() throws Exception {
        previousUserHome = System.getProperty("user.home");
        File tempHome = Files.createTempDirectory("chat2db-shard-test-home").toFile();
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
        connection = DriverManager.getConnection("jdbc:h2:mem:shard_export;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS SHARD_ITEMS");
            statement.execute("CREATE TABLE SHARD_ITEMS (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            statement.execute("INSERT INTO SHARD_ITEMS SELECT X, 'name' FROM SYSTEM_RANGE(1, " + ROWS + ")");
        }
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setJdbcDriverClass("org.h2.Driver");
        driverConfig.setJdbcDriver(H2_DRIVER_NAME);
        connectInfo.setDriverConfig(driverConfig);
        connectInfo.setUrl("jdbc:h2:mem:shard_export;DB_CLOSE_DELAY=-1");
        Chat2DBContext.putContext(connectInfo);
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
    void shardsDrainInKeyOrderThroughOneSink() throws Exception {
        RecordingContext context = new RecordingContext();
        ShardTestExporter exporter = new ShardTestExporter();
        setShardMaxParallelism(exporter, 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        String csv = runThroughPipeline(exporter, context, out);
        List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(
                csv.replace("﻿", "").split("\r\n")));
        assertEquals("ID,NAME", lines.get(0));
        assertEquals(ROWS, lines.size() - 1);
        Matcher matcher = Pattern.compile("^(\\d+),name$").matcher("");
        for (int row = 1; row < lines.size(); row++) {
            matcher.reset(lines.get(row));
            assertTrue(matcher.matches() && Integer.parseInt(matcher.group(1)) == row, lines.get(row));
        }
        assertTrue(context.workerThreads.size() >= 2,
                "expected several shard threads, saw " + context.workerThreads);
    }

    private String runThroughPipeline(ShardTestExporter exporter, RecordingContext context,
            ByteArrayOutputStream out) throws Exception {
        exporter.exportDirect(context, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void setShardMaxParallelism(BaseExporter exporter, int value) throws Exception {
        Field field = BaseExporter.class.getDeclaredField("shardMaxParallelism");
        field.setAccessible(true);
        field.setInt(exporter, value);
    }

    private static final class ShardTestExporter extends BaseExporter {

        private ShardTestExporter() {
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
                    (stream, effectiveSpec, effectiveTable, resume) -> new CsvSink(stream, true),
                    ExportValueMode.NATIVE, 1000,
                    new ExportProgressLogger(context, "CSV", tableName), resuming);
        }

        private void exportDirect(RecordingContext context, java.io.ByteArrayOutputStream out) throws Exception {
            ExportTaskSpec spec = ExportTaskSpec.builder()
                    .tableNames(List.of("SHARD_ITEMS"))
                    .target(TaskTargetSnapshot.builder().dataSourceId(1L).tableName("SHARD_ITEMS").build())
                    .build();
            singleExport(spec, context, "SHARD_ITEMS", out, false);
        }
    }

    private static final class RecordingContext implements TaskExecutionContext {

        private final Set<String> workerThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger progress = new AtomicInteger();

        @Override
        public Long taskId() {
            return 42L;
        }

        @Override
        public void reportProgress(int value, String stage, String message) {
            progress.set(value);
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
            workerThreads.add(Thread.currentThread().getName());
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
}
