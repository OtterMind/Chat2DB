package ai.chat2db.community.domain.core.benchmark;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.CsvOptions;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.excel.CSVImporter;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.JdbcUrlRewriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "After" measurement for the import path: drives the real {@link CSVImporter} in STANDARD mode over
 * a 100000 row CSV file and records wall time, rows/s, peak heap and the server side counter deltas.
 *
 * <p>The connection is opened the way production opens it, through
 * {@link JdbcUrlRewriter#withBatchRewrite(String)}, so the driver is allowed to merge a batch of
 * INSERT statements into one multi row statement. Without that parameter a JDBC batch is still sent
 * as one statement per row, which is what the P0 baseline shows.
 *
 * <p>Run it with:
 *
 * <pre>
 * mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core -am \
 *   -Dmaven.test.skip=false -DskipTests=false \
 *   -Dtest=ImportExportCsvImporterBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dmaven.test.failure.ignore=false -Dbench.rows=100000 test
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportExportCsvImporterBenchmarkTest {

    private static final String DEFAULT_JDBC_URL =
            "jdbc:mysql://127.0.0.1:13306/bench?useSSL=false&allowPublicKeyRetrieval=true";

    private static final String JDBC_URL = env("C2DB_BENCH_JDBC_URL", DEFAULT_JDBC_URL);
    private static final String DB_USER = env("C2DB_BENCH_USER", "root");
    private static final String DB_PASSWORD = env("C2DB_BENCH_PASSWORD", "bench");
    private static final int ROWS = Integer.parseInt(System.getProperty("bench.rows", "100000"));

    private static final String DB_TYPE = "MYSQL_BENCH";

    private static final String RUN_TAG = "p" + ProcessHandle.current().pid();
    private static final String TABLE = "bench_wide_csvimport_" + RUN_TAG;

    private static final List<String> DATA_COLUMNS = List.of(
            "c_varchar_01", "c_varchar_02", "c_varchar_03", "c_varchar_04", "c_varchar_05", "c_varchar_06",
            "c_text_01", "c_text_02",
            "c_int_01", "c_int_02", "c_int_03", "c_bigint_01",
            "c_decimal_01", "c_decimal_02",
            "c_datetime_01", "c_datetime_02", "c_date_01",
            "c_json_01", "c_json_02",
            "c_bool_01");

    private static final List<String> WIDE_COLUMN_DDL = List.of(
            "id BIGINT NOT NULL AUTO_INCREMENT",
            "c_varchar_01 VARCHAR(64) NULL",
            "c_varchar_02 VARCHAR(64) NULL",
            "c_varchar_03 VARCHAR(64) NULL",
            "c_varchar_04 VARCHAR(128) NULL",
            "c_varchar_05 VARCHAR(128) NULL",
            "c_varchar_06 VARCHAR(255) NULL",
            "c_text_01 TEXT NULL",
            "c_text_02 TEXT NULL",
            "c_int_01 INT NULL",
            "c_int_02 INT NULL",
            "c_int_03 INT NULL",
            "c_bigint_01 BIGINT NULL",
            "c_decimal_01 DECIMAL(18,4) NULL",
            "c_decimal_02 DECIMAL(18,4) NULL",
            "c_datetime_01 DATETIME NULL",
            "c_datetime_02 DATETIME NULL",
            "c_date_01 DATE NULL",
            "c_json_01 JSON NULL",
            "c_json_02 JSON NULL",
            "c_bool_01 TINYINT(1) NULL",
            "PRIMARY KEY (id)");

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE %s (" + String.join(", ", WIDE_COLUMN_DDL)
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private static final String PAD = "0123456789abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuv";

    private static final List<String> STATUS_KEYS = List.of("Questions", "Com_insert", "Com_stmt_prepare");

    private Connection connection;
    private IPlugin previousPlugin;
    private Path fixture;
    private ExecListener listener;

    @BeforeAll
    void setUp() throws Exception {
        assumeTrue(reachable(urlHost(JDBC_URL), urlPort(JDBC_URL)),
                "MySQL benchmark instance not reachable at " + urlHost(JDBC_URL) + ":" + urlPort(JDBC_URL)
                        + "; skipping benchmark");
        Class.forName("com.mysql.cj.jdbc.Driver");

        String batchUrl = JdbcUrlRewriter.withBatchRewrite(stripBatchRewrite(JDBC_URL));
        try {
            connection = DriverManager.getConnection(batchUrl, DB_USER, DB_PASSWORD);
        } catch (SQLException e) {
            assumeTrue(false, "MySQL benchmark connection failed: " + e.getMessage() + "; skipping benchmark");
            return;
        }

        registerChat2DBContext();
        recreateTable();
        generateFixture();

        System.out.println("[csv-import-bench] JDBC URL: " + connection.getMetaData().getURL());
        System.out.println("[csv-import-bench] rows=" + ROWS + ", fixture=" + fixture.toAbsolutePath()
                + " (" + Files.size(fixture) + " B)");
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            Chat2DBContext.removeContext();
            if (previousPlugin == null) {
                Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
            } else {
                Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
            }
            if (connection != null && !connection.isClosed()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + TABLE);
                } catch (SQLException ignored) {
                    // best effort cleanup of this run's own table
                }
                connection.close();
            }
        } catch (RuntimeException ignored) {
            // cleanup must never mask the measurement
        }
    }

    @Test
    void standardCsvImportWritePath() throws Exception {
        listener = new ExecListener();
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .taskType("DATA_FILE_IMPORT")
                .sourceFile(fixture.toString())
                .format("CSV")
                .mode("STANDARD")
                .csvOptions(CsvOptions.defaults())
                .target(TaskTargetSnapshot.builder()
                        .dataSourceId(1L)
                        .databaseName("bench")
                        .tableName(TABLE)
                        .build())
                .columnMappings(DATA_COLUMNS.stream()
                        .map(column -> new ImportColumnMapping(column, column))
                        .toList())
                .build();
        BenchmarkTaskContext context = new BenchmarkTaskContext(listener);

        Map<String, Long> before = sessionStatus();
        HeapPeakSampler sampler = new HeapPeakSampler();
        sampler.start();
        long t0 = System.nanoTime();
        new CSVImporter().run(spec, context);
        long elapsed = System.nanoTime() - t0;
        long peakHeap = sampler.stop();
        long peakOldGen = sampler.peakOldGen();
        Map<String, Long> delta = delta(before, sessionStatus());

        long imported = countRows();
        assertEquals(ROWS, imported, "the real CSV import path should land every row");

        String summary = renderSummary(elapsed, peakHeap, peakOldGen, delta);
        System.out.println(summary);
        Path out = Paths.get(System.getProperty("bench.out.dir", "target/bench-results"))
                .resolve("after-csv-import-" + RUN_TAG + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, summary, StandardCharsets.UTF_8);
        System.out.println("[csv-import-bench] written to " + out.toAbsolutePath());
    }

    private String renderSummary(long elapsedNanos, long peakHeap, long peakOldGen, Map<String, Long> delta)
            throws SQLException {
        long roundTrips = delta.getOrDefault("Questions", -1L);
        long rowsPerSecond = elapsedNanos <= 0 ? -1L : Math.round(ROWS / (elapsedNanos / 1_000_000_000.0d));
        StringBuilder md = new StringBuilder(2048);
        md.append("# After - real CSVImporter STANDARD import (").append(RUN_TAG).append(")\n\n");
        md.append("- generated: ").append(Instant.now()).append('\n');
        md.append("- JDBC URL: ").append(connection.getMetaData().getURL())
                .append("  (opened through JdbcUrlRewriter.withBatchRewrite)\n");
        md.append("- rows: ").append(ROWS).append(", table: ").append(TABLE)
                .append(", max heap ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append(" MiB\n");
        md.append("\n| 口径 | 行数 | 耗时 (ms) | rows per s | 峰值堆 (MiB) | old gen 峰值 (MiB) | 往返次数 (Questions) | Com_insert |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        md.append("| 真实 CSVImporter STANDARD 导入（参数化/字符串批 + rewriteBatchedStatements=true） | ")
                .append(ROWS).append(" | ").append(elapsedNanos / 1_000_000L)
                .append(" | ").append(rowsPerSecond)
                .append(" | ").append(peakHeap / (1024 * 1024))
                .append(" | ").append(peakOldGen / (1024 * 1024))
                .append(" | ").append(roundTrips < 0 ? "-" : Math.max(0L, roundTrips - 1))
                .append(" | ").append(delta.getOrDefault("Com_insert", -1L))
                .append(" |\n");
        md.append("\n- JDBC statements created by the import: ").append(listener.created.get())
                .append(", closed: ").append(listener.closed.get())
                .append(", observed fetchSizes: ").append(listener.fetchSizes).append('\n');
        md.append("- raw Questions delta (includes the trailing SHOW SESSION STATUS): ").append(roundTrips).append('\n');
        return md.toString();
    }

    // ---------------------------------------------------------------- harness

    private void registerChat2DBContext() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDatabaseName("bench");
        connectInfo.setDriverConfig(config.getDefaultDriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private void recreateTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + TABLE);
            statement.execute(String.format(CREATE_TABLE_SQL, TABLE));
        }
    }

    private long countRows() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void generateFixture() throws IOException {
        Path dir = Paths.get("target", "bench-fixtures");
        Files.createDirectories(dir);
        fixture = dir.resolve("csv-import-" + ROWS + "-" + RUN_TAG + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(fixture, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", DATA_COLUMNS));
            writer.newLine();
            for (int row = 0; row < ROWS; row++) {
                writer.write(csvLine(plainValues(row)));
                writer.newLine();
            }
        }
    }

    private static List<String> plainValues(int row) {
        List<String> values = new ArrayList<>(DATA_COLUMNS.size());
        values.add("v01-" + row);
        values.add("v02-" + row);
        values.add("v03-" + row);
        values.add("v04-" + row);
        values.add("v05-" + row);
        values.add("v06-" + row);
        values.add("t01-" + row + "-" + PAD);
        values.add("t02-" + row + "-" + PAD);
        values.add(String.valueOf(row));
        values.add(String.valueOf(row * 7L));
        values.add(String.valueOf(row % 1000));
        values.add(String.valueOf(7_000_000_000L + row));
        values.add((row % 100_000) + ".2500");
        values.add((row % 10_000) + ".5000");
        values.add("2024-01-01 00:00:00");
        values.add("2024-06-15 12:30:45");
        values.add("2024-01-01");
        values.add("{\"k\":" + row + "}");
        values.add("{\"a\":[1,2,3],\"b\":true}");
        values.add(String.valueOf(row % 2));
        return values;
    }

    private Map<String, Long> sessionStatus() throws SQLException {
        Map<String, Long> status = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SHOW SESSION STATUS")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!STATUS_KEYS.contains(name)) {
                    continue;
                }
                try {
                    status.put(name, Long.parseLong(rs.getString(2)));
                } catch (NumberFormatException ignored) {
                    // not numeric, not needed
                }
            }
        }
        return status;
    }

    private static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> delta = new LinkedHashMap<>();
        for (String key : STATUS_KEYS) {
            Long from = before.get(key);
            Long to = after.get(key);
            if (from != null && to != null) {
                delta.put(key, to - from);
            }
        }
        return delta;
    }

    private static String stripBatchRewrite(String url) {
        return url.replaceAll("([?&])rewriteBatchedStatements=[^&]*", "$1").replace("?&", "?")
                .replaceAll("[?&]$", "");
    }

    /** RFC4180 escaping so JSON columns containing commas or quotes survive the CSV reader. */
    private static String csvField(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String csvLine(List<String> values) {
        StringBuilder sb = new StringBuilder(320);
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(values.get(i)));
        }
        return sb.toString();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String authorityOf(String url) {
        int start = url.indexOf("//");
        start = start < 0 ? 0 : start + 2;
        int end = url.indexOf('/', start);
        return url.substring(start, end < 0 ? url.length() : end);
    }

    private static String urlHost(String url) {
        String authority = authorityOf(url);
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static int urlPort(String url) {
        String authority = authorityOf(url);
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? 3306 : Integer.parseInt(authority.substring(colon + 1));
    }

    private static final class ExecListener implements ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener {

        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final Set<Integer> fetchSizes = ConcurrentHashMap.newKeySet();

        @Override
        public void onStatementCreated(Statement statement) {
            created.incrementAndGet();
            try {
                fetchSizes.add(statement.getFetchSize());
            } catch (SQLException ignored) {
                // ignore
            }
        }

        @Override
        public void onStatementClosed(Statement statement) {
            closed.incrementAndGet();
        }
    }

    private static final class BenchmarkTaskContext implements TaskExecutionContext {

        private final ExecListener listener;

        BenchmarkTaskContext(ExecListener listener) {
            this.listener = listener;
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
            return null;
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
            listener.onStatementCreated(statement);
        }

        @Override
        public void onStatementClosed(Statement statement) {
            listener.onStatementClosed(statement);
        }
    }

    private static final class HeapPeakSampler {

        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();

        private volatile boolean running;
        private volatile long peakUsed;
        private Thread thread;

        void start() {
            peakUsed = memoryBean.getHeapMemoryUsage().getUsed();
            for (MemoryPoolMXBean pool : heapPools) {
                try {
                    pool.resetPeakUsage();
                } catch (UnsupportedOperationException ignored) {
                    // ignore
                }
            }
            running = true;
            thread = new Thread(() -> {
                while (running) {
                    long used = memoryBean.getHeapMemoryUsage().getUsed();
                    if (used > peakUsed) {
                        peakUsed = used;
                    }
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "csv-import-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        long stop() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return peakUsed;
        }

        long peakOldGen() {
            long peak = -1L;
            for (MemoryPoolMXBean pool : heapPools) {
                String name = pool.getName();
                if (name.contains("Old") || name.contains("Tenured")) {
                    try {
                        peak = Math.max(peak, pool.getPeakUsage().getUsed());
                    } catch (UnsupportedOperationException ignored) {
                        // ignore
                    }
                }
            }
            return peak;
        }
    }
}
