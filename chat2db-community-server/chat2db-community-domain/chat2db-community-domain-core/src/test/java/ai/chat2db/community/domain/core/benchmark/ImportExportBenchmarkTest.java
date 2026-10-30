package ai.chat2db.community.domain.core.benchmark;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterBuilder;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P0 metric baseline for the import / export hot paths.
 *
 * <p>This is a measurement scaffold, not a functional test: it runs against a real MySQL
 * instance, drives the <em>current</em> production code paths, and writes the observed numbers
 * to {@code target/bench-results/baseline.md}. It is test scoped and opt in: with no reachable
 * MySQL it is skipped through {@link org.junit.jupiter.api.Assumptions#assumeTrue}.
 *
 * <p>Run it with tests explicitly enabled, because the reactor BOM defaults
 * {@code maven.test.skip=true}:
 *
 * <pre>
 * mvn -B -f chat2db-community-server/pom.xml -pl :chat2db-community-domain-core -am \
 *   -Dmaven.test.skip=false -DskipTests=false \
 *   -Dtest=ImportExportBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dmaven.test.failure.ignore=false -Dbench.rows=100000 test
 * </pre>
 *
 * <p>Connection parameters come from {@code C2DB_BENCH_JDBC_URL}, {@code C2DB_BENCH_USER} and
 * {@code C2DB_BENCH_PASSWORD}; the row count comes from {@code -Dbench.rows} (default 100000) and
 * the fixture row count from {@code -Dbench.fixture.rows} (default: same as bench.rows).
 *
 * <p>All four measurement methods should run in one class instance, because the read and CSV
 * phases reuse the rows written by the import phases. Running one method alone skips the
 * read/export phases with an explicit assumption message instead of reporting bogus numbers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportExportBenchmarkTest {

    // ---------------------------------------------------------------- configuration

    private static final String DEFAULT_JDBC_URL =
            "jdbc:mysql://127.0.0.1:13306/bench?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&rewriteBatchedStatements=false";

    private static final String JDBC_URL = env("C2DB_BENCH_JDBC_URL", DEFAULT_JDBC_URL);
    private static final String DB_USER = env("C2DB_BENCH_USER", "root");
    private static final String DB_PASSWORD = env("C2DB_BENCH_PASSWORD", "bench");

    private static final int ROWS = Integer.parseInt(System.getProperty("bench.rows", "100000"));

    /**
     * When false the two write measurements are skipped and the wide table is seeded through a
     * separate, batch rewriting connection instead. That keeps a 1000000 row read/CSV run inside a
     * few minutes, because the write phases are dominated by the per statement commit.
     */
    private static final boolean WRITE_PHASES =
            Boolean.parseBoolean(System.getProperty("bench.write.phases", "true"));
    private static final int FIXTURE_ROWS =
            Integer.parseInt(System.getProperty("bench.fixture.rows", String.valueOf(ROWS)));

    /** Mirrors BaseExcelImporter.BATCH_SIZE: production re-enters the write path every 1000 rows. */
    private static final int IMPORT_CHUNK = 1000;

    /**
     * The bench database can be shared with other running agents, so every run owns pid suffixed
     * tables and only ever drops its own.
     */
    private static final String RUN_TAG = "p" + ProcessHandle.current().pid();
    private static final String TABLE = "bench_wide_" + RUN_TAG;
    private static final String TABLE_JDBC_BATCH = "bench_wide_jdbc_" + RUN_TAG;
    private static final String SELECT_ALL = "SELECT * FROM " + TABLE;

    /** Session counters used to prove how many client/server round trips a phase actually made. */
    private static final List<String> STATUS_KEYS =
            List.of("Questions", "Com_insert", "Com_select", "Com_stmt_prepare", "Com_stmt_execute");

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

    // ---------------------------------------------------------------- mutable state

    private final DefaultSQLExecutor executor = DefaultSQLExecutor.getInstance();
    private final List<BenchResult> results = new ArrayList<>();
    private final Map<String, Long> fixtureBytes = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    /** Synthetic db type so Chat2DBContext can resolve a metadata service without a plugin module. */
    private static final String DB_TYPE = "MYSQL_BENCH";

    private Connection connection;
    private IPlugin previousPlugin;

    // ---------------------------------------------------------------- lifecycle

    @BeforeAll
    void setUp() throws Exception {
        String host = urlHost(JDBC_URL);
        int port = urlPort(JDBC_URL);
        assumeTrue(reachable(host, port),
                "MySQL benchmark instance not reachable at " + host + ":" + port + "; skipping benchmark");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "MySQL JDBC driver is missing from the test classpath; "
                            + "add com.mysql:mysql-connector-j with test scope",
                    e);
        }

        try {
            connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD);
        } catch (SQLException e) {
            assumeTrue(false, "MySQL benchmark connection failed: " + e.getMessage() + "; skipping benchmark");
            return;
        }

        registerChat2DBContext();

        recreateTable(TABLE);
        recreateTable(TABLE_JDBC_BATCH);
        generateFixtures();
        if (!WRITE_PHASES) {
            seedWideTable();
        }

        notes.add("JDBC URL: " + connection.getMetaData().getURL());
        notes.add("bench.write.phases: " + WRITE_PHASES
                + (WRITE_PHASES ? "" : " (wide table seeded through a batch rewriting connection)"));
        notes.add("bench.rows: " + ROWS + ", bench.fixture.rows: " + FIXTURE_ROWS);
        notes.add("wide table columns: " + (1 + DATA_COLUMNS.size())
                + " (id + " + DATA_COLUMNS.size() + " data columns), InnoDB, autocommit=true");
        notes.add("JVM: " + System.getProperty("java.version")
                + ", max heap " + toMib(Runtime.getRuntime().maxMemory()) + " MiB");
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            if (!results.isEmpty()) {
                writeReport();
            }
        } finally {
            try {
                Chat2DBContext.removeContext();
            } catch (RuntimeException ignored) {
                // context cleanup must never mask the measurement result
            }
            if (previousPlugin == null) {
                Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
            } else {
                Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
            }
            if (connection != null && !connection.isClosed()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + TABLE);
                    statement.execute("DROP TABLE IF EXISTS " + TABLE_JDBC_BATCH);
                } catch (SQLException ignored) {
                    // best effort cleanup of this run's own tables
                }
                connection.close();
            }
        }
    }

    /**
     * Registers a minimal plugin for a synthetic db type so that Chat2DBContext.getDbMetaData()
     * resolves. generateHeaderList() inside the executor requires it; the default meta service is
     * enough for header naming and the result set editor type.
     */
    private void registerChat2DBContext() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbManager getDbManager() {
                // The synthetic bench type has no registered plugin, so the dialect manager is
                // provided here. It mirrors MysqlDBManager, whose Connector/J streaming fetch size
                // is Integer.MIN_VALUE; without it the read path would measure the neutral default.
                return new DefaultDBManager() {
                    @Override
                    public int streamingFetchSize() {
                        return Integer.MIN_VALUE;
                    }
                };
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDatabaseName("bench");
        connectInfo.setDriverConfig(config.getDefaultDriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    // ---------------------------------------------------------------- 1. import write path

    /**
     * The method named by the P0 brief as "current state": {@code executeBatchInsert} prepares and
     * executes one statement per row.
     */
    @Test
    @Order(1)
    void importRowByRowWritePath() throws Exception {
        assumeTrue(WRITE_PHASES, "bench.write.phases=false: write measurements skipped");
        CapturingStatementListener listener = new CapturingStatementListener();
        Map<String, Long> before = sessionStatus();
        long wallStart = System.nanoTime();
        long nanos = 0L;
        for (int start = 0; start < ROWS; start += IMPORT_CHUNK) {
            int end = Math.min(start + IMPORT_CHUNK, ROWS);
            List<String> sqls = new ArrayList<>(end - start);
            for (int row = start; row < end; row++) {
                sqls.add(insertSql(TABLE, row));
            }
            long t0 = System.nanoTime();
            executor.executeBatchInsert(connection, sqls, listener, null);
            nanos += System.nanoTime() - t0;
        }
        long wallNanos = System.nanoTime() - wallStart;
        Map<String, Long> statusDelta = delta(before, sessionStatus());

        long imported = countRows(TABLE);
        assertEquals(ROWS, imported, "row by row import should land every generated row");

        results.add(new BenchResult(
                "import write / executeBatchInsert (per row prepare+execute)",
                ROWS, nanos, -1L, -1L,
                roundTripsOf(statusDelta),
                "chunk=" + IMPORT_CHUNK + ", wall=" + toMillis(wallNanos) + " ms, JDBC statements created="
                        + listener.created.get() + ", closed=" + listener.closed.get()
                        + ", fetchSize=" + listener.fetchSizes(),
                statusDelta));
    }

    /**
     * The write path production actually uses at this revision: {@code ImportSqlExecutor} and
     * {@code ImportRowBatcher} both call {@code DefaultSQLExecutor.executeJdbcBatchInsert}, i.e.
     * {@code Statement.addBatch} plus {@code executeBatch()} in chunks of 500. The benchmark URL
     * keeps {@code rewriteBatchedStatements=false}, so the driver still emits one round trip per row.
     */
    @Test
    @Order(2)
    void importJdbcBatchWritePath() throws Exception {
        assumeTrue(WRITE_PHASES, "bench.write.phases=false: write measurements skipped");
        CapturingStatementListener listener = new CapturingStatementListener();
        Map<String, Long> before = sessionStatus();
        long wallStart = System.nanoTime();
        long nanos = 0L;
        for (int start = 0; start < ROWS; start += IMPORT_CHUNK) {
            int end = Math.min(start + IMPORT_CHUNK, ROWS);
            List<String> sqls = new ArrayList<>(end - start);
            for (int row = start; row < end; row++) {
                sqls.add(insertSql(TABLE_JDBC_BATCH, row));
            }
            long t0 = System.nanoTime();
            executor.executeJdbcBatchInsert(connection, sqls, listener, null);
            nanos += System.nanoTime() - t0;
        }
        long wallNanos = System.nanoTime() - wallStart;
        Map<String, Long> statusDelta = delta(before, sessionStatus());

        long inserted = countRows(TABLE_JDBC_BATCH);
        assertEquals(ROWS, inserted, "JDBC batch import should land every generated row");

        results.add(new BenchResult(
                "import write / executeJdbcBatchInsert (current import hot path, chunk=500, "
                        + "rewriteBatchedStatements=false)",
                ROWS, nanos, -1L, -1L,
                roundTripsOf(statusDelta),
                "outer chunk=" + IMPORT_CHUNK + ", wall=" + toMillis(wallNanos)
                        + " ms, JDBC statements created=" + listener.created.get(),
                statusDelta));
    }

    // ---------------------------------------------------------------- 2. result set read path

    @Test
    @Order(3)
    void resultSetReadPath() throws Exception {
        long available = countRows(TABLE);
        assumeTrue(available > 0,
                "run the whole ImportExportBenchmarkTest class: " + TABLE + " is empty, nothing to read");

        CapturingStatementListener listener = new CapturingStatementListener();
        AtomicLong rowCount = new AtomicLong();
        AtomicInteger headerCount = new AtomicInteger();

        System.gc();
        HeapPeakSampler sampler = new HeapPeakSampler();
        Map<String, Long> before = sessionStatus();
        sampler.start();
        long t0 = System.nanoTime();
        executor.execute(connection, SELECT_ALL,
                headers -> headerCount.set(headers.size()),
                row -> rowCount.incrementAndGet(),
                JDBCDataValue::getStringValue,
                false,
                null,
                listener,
                null);
        long nanos = System.nanoTime() - t0;
        long peakHeap = sampler.stop();
        long peakOldGen = sampler.peakOldGen();
        Map<String, Long> statusDelta = delta(before, sessionStatus());

        assertEquals(available, rowCount.get(), "read path should consume every row");
        assertTrue(headerCount.get() > 0, "header consumer should see the result set columns");

        results.add(new BenchResult(
                "result read / DefaultSQLExecutor.execute (dialect streaming fetchSize)",
                rowCount.get(), nanos, peakHeap, -1L,
                roundTripsOf(statusDelta),
                "columns=" + headerCount.get()
                        + ", fetchSize at create=" + listener.fetchSizesAtCreate()
                        + ", fetchSize at close=" + listener.fetchSizesAtClose()
                        + ", statement classes=" + listener.statementClasses()
                        + ", statements created=" + listener.created.get()
                        + ", peak old gen=" + toMib(peakOldGen) + " MiB",
                statusDelta));
        notes.add("read path observed JDBC fetchSize at close=" + listener.fetchSizesAtClose()
                + " on " + listener.statementClasses());
    }

    // ---------------------------------------------------------------- 3. CSV export path

    @Test
    @Order(4)
    void csvExportPath() throws Exception {
        long available = countRows(TABLE);
        assumeTrue(available > 0,
                "run the whole ImportExportBenchmarkTest class: " + TABLE + " is empty, nothing to export");

        Path outputDir = resultDir();
        Files.createDirectories(outputDir);
        Path csv = outputDir.resolve("csv-export-" + RUN_TAG + ".csv");

        CapturingStatementListener listener = new CapturingStatementListener();
        AtomicLong rowCount = new AtomicLong();
        AtomicReference<String> fallbackReason = new AtomicReference<>();

        System.gc();
        HeapPeakSampler sampler = new HeapPeakSampler();
        Map<String, Long> before = sessionStatus();
        sampler.start();
        boolean easyExcel = true;
        long t0 = System.nanoTime();
        try (OutputStream outputStream = Files.newOutputStream(csv)) {
            exportCsvWithEasyExcel(outputStream, listener, rowCount);
        } catch (Throwable t) {
            easyExcel = false;
            fallbackReason.set(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        long nanos = System.nanoTime() - t0;
        long peakHeap = sampler.stop();

        if (!easyExcel) {
            // Documented degradation: read plus a plain BufferedWriter when the EasyExcel CSV path
            // is unusable.
            rowCount.set(0L);
            sampler = new HeapPeakSampler();
            sampler.start();
            long fallbackStart = System.nanoTime();
            try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                writer.write(String.join(",", DATA_COLUMNS));
                writer.newLine();
                executor.execute(connection, SELECT_ALL,
                        headers -> { },
                        row -> {
                            try {
                                writer.write(String.join(",", row));
                                writer.newLine();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            rowCount.incrementAndGet();
                        },
                        JDBCDataValue::getStringValue,
                        false,
                        null,
                        listener,
                        null);
            }
            nanos = System.nanoTime() - fallbackStart;
            peakHeap = sampler.stop();
            notes.add("EasyExcel CSV path failed and degraded to BufferedWriter: " + fallbackReason.get());
        }
        Map<String, Long> statusDelta = delta(before, sessionStatus());

        long bytes = Files.size(csv);
        assertEquals(available, rowCount.get(), "CSV export should write every row");
        assertTrue(bytes > 0, "CSV export should produce a non empty artifact");

        results.add(new BenchResult(
                easyExcel
                        ? "CSV export / EasyExcel (same shape as DbDmlExportServiceImpl.exportCsv, includes read)"
                        : "CSV export / degraded: read + BufferedWriter (includes read)",
                rowCount.get(), nanos, peakHeap, bytes,
                roundTripsOf(statusDelta),
                "artifact=" + csv.toAbsolutePath() + ", mode="
                        + (easyExcel ? "EasyExcel CSV" : "BufferedWriter"),
                statusDelta));
    }

    /** Mirrors DbDmlExportServiceImpl.exportCsv for the parts that do not need Chat2DBContext. */
    private void exportCsvWithEasyExcel(OutputStream outputStream, CapturingStatementListener listener,
            AtomicLong rowCount) {
        ExcelWriterBuilder excelWriterBuilder = EasyExcel.write(outputStream)
                .charset(StandardCharsets.UTF_8)
                .excelType(ExcelTypeEnum.CSV);
        AtomicReference<ExcelWriter> writerRef = new AtomicReference<>();
        AtomicReference<WriteSheet> sheetRef = new AtomicReference<>();
        try {
            executor.execute(connection, SELECT_ALL,
                    headerList -> {
                        List<List<String>> head = new ArrayList<>(headerList.size());
                        for (Header header : headerList) {
                            head.add(List.of(header.getName()));
                        }
                        excelWriterBuilder.head(head);
                        writerRef.set(excelWriterBuilder.build());
                        sheetRef.set(EasyExcel.writerSheet(0).build());
                    },
                    dataList -> {
                        writerRef.get().write(List.of(dataList), sheetRef.get());
                        rowCount.incrementAndGet();
                    },
                    JDBCDataValue::getStringValue,
                    false,
                    null,
                    listener,
                    null);
        } finally {
            if (writerRef.get() != null) {
                writerRef.get().finish();
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    private void generateFixtures() throws IOException {
        Path dir = Paths.get("target", "bench-fixtures");
        Files.createDirectories(dir);
        Path csv = dir.resolve("bench_wide_" + FIXTURE_ROWS + ".csv");
        Path jsonl = dir.resolve("bench_wide_" + FIXTURE_ROWS + ".jsonl");

        long start = System.nanoTime();
        try (BufferedWriter csvWriter = Files.newBufferedWriter(csv, StandardCharsets.UTF_8);
                BufferedWriter jsonWriter = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8)) {
            csvWriter.write(String.join(",", DATA_COLUMNS));
            csvWriter.newLine();
            for (int row = 0; row < FIXTURE_ROWS; row++) {
                List<String> values = plainValues(row);
                csvWriter.write(csvLine(values));
                csvWriter.newLine();
                jsonWriter.write(jsonObject(values));
                jsonWriter.newLine();
            }
        }
        long millis = toMillis(System.nanoTime() - start);
        fixtureBytes.put(csv.getFileName().toString(), Files.size(csv));
        fixtureBytes.put(jsonl.getFileName().toString(), Files.size(jsonl));
        notes.add("fixtures generated in " + millis + " ms under target/bench-fixtures");
    }

    /** 20 plain, unquoted values for one row, in DATA_COLUMNS order. */
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

    /** RFC4180 escaping so JSON columns containing commas or quotes survive a CSV reader. */
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

    private static String jsonObject(List<String> values) {
        StringBuilder sb = new StringBuilder(320).append('{');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(DATA_COLUMNS.get(i)).append("\":");
            if (isNumericColumn(i)) {
                sb.append(values.get(i));
            } else {
                sb.append('"').append(values.get(i)).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static boolean isNumericColumn(int index) {
        return (index >= 8 && index <= 13) || index == 19;
    }

    /** SQL text values are quoted; numeric and boolean columns are emitted raw. */
    private static String insertSql(String table, int row) {
        List<String> values = plainValues(row);
        StringBuilder sb = new StringBuilder(384);
        sb.append("INSERT INTO ").append(table).append(" (")
                .append(String.join(",", DATA_COLUMNS)).append(") VALUES (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (isNumericColumn(i)) {
                sb.append(values.get(i));
            } else {
                sb.append('\'').append(values.get(i)).append('\'');
            }
        }
        return sb.append(')').toString();
    }

    // ---------------------------------------------------------------- schema helpers

    /**
     * Fixture loading only, never measured: inserts every row through a connection whose URL turns
     * on rewriteBatchedStatements so the driver sends multi row INSERT statements.
     */
    private void seedWideTable() throws SQLException {
        String seedUrl = JDBC_URL.replace("rewriteBatchedStatements=false", "rewriteBatchedStatements=true");
        long start = System.nanoTime();
        try (Connection seedConnection = DriverManager.getConnection(seedUrl, DB_USER, DB_PASSWORD)) {
            for (int offset = 0; offset < ROWS; offset += IMPORT_CHUNK) {
                int end = Math.min(offset + IMPORT_CHUNK, ROWS);
                List<String> sqls = new ArrayList<>(end - offset);
                for (int row = offset; row < end; row++) {
                    sqls.add(insertSql(TABLE, row));
                }
                executor.executeJdbcBatchInsert(seedConnection, sqls, null, null);
            }
        }
        notes.add("seeded " + ROWS + " rows in " + toMillis(System.nanoTime() - start) + " ms (not measured)");
    }

    private void recreateTable(String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
            statement.execute(String.format(CREATE_TABLE_SQL, table));
        }
    }

    private long countRows(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ---------------------------------------------------------------- status instrumentation

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
                    // non numeric session variable, not needed here
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

    /**
     * Questions counts every statement the client sent on this session, including the trailing
     * SHOW SESSION STATUS that produced the "after" reading, so the round trips attributable to the
     * measured phase are Questions - 1.
     */
    private static long roundTripsOf(Map<String, Long> status) {
        Long questions = status.get("Questions");
        return questions == null ? -1L : Math.max(0L, questions - 1L);
    }

    // ---------------------------------------------------------------- heap sampling

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
                    // pool does not support peak tracking
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
            }, "bench-heap-sampler");
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

    /**
     * The executor notifies statement creation immediately after prepareStatement(), so the
     * fetch size is sampled again on close: that is the value the statement actually ran with.
     */
    private static final class CapturingStatementListener implements ISqlExecutionStatementListener {

        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final Set<Integer> fetchSizesAtCreate = ConcurrentHashMap.newKeySet();
        private final Set<Integer> fetchSizesAtClose = ConcurrentHashMap.newKeySet();
        private final Set<String> statementClasses = ConcurrentHashMap.newKeySet();

        @Override
        public void onStatementCreated(Statement statement) {
            created.incrementAndGet();
            statementClasses.add(statement.getClass().getName());
            try {
                fetchSizesAtCreate.add(statement.getFetchSize());
            } catch (SQLException ignored) {
                // some drivers reject getFetchSize on a closed statement
            }
        }

        @Override
        public void onStatementClosed(Statement statement) {
            closed.incrementAndGet();
            try {
                fetchSizesAtClose.add(statement.getFetchSize());
            } catch (SQLException ignored) {
                // ignore
            }
        }

        /** Fetch sizes observed while the statements lived; union of the create and close samples. */
        Set<Integer> fetchSizes() {
            Set<Integer> observed = ConcurrentHashMap.newKeySet();
            observed.addAll(fetchSizesAtCreate);
            observed.addAll(fetchSizesAtClose);
            return observed;
        }

        Set<Integer> fetchSizesAtCreate() {
            return fetchSizesAtCreate;
        }

        Set<Integer> fetchSizesAtClose() {
            return fetchSizesAtClose;
        }

        Set<String> statementClasses() {
            return statementClasses;
        }
    }

    // ---------------------------------------------------------------- reporting

    private record BenchResult(String scene, long rows, long elapsedNanos, long peakHeapBytes,
            long artifactBytes, long roundTrips, String detail, Map<String, Long> status) {
    }

    private Path resultDir() {
        return Paths.get(System.getProperty("bench.out.dir", "target/bench-results"));
    }

    private void writeReport() throws IOException {
        Path dir = resultDir();
        Files.createDirectories(dir);
        String markdown = renderMarkdown();
        Files.writeString(dir.resolve("baseline.md"), markdown, StandardCharsets.UTF_8);
        System.out.println(markdown);
        System.out.println("[bench] baseline written to " + dir.resolve("baseline.md").toAbsolutePath());
    }

    private String renderMarkdown() {
        StringBuilder md = new StringBuilder(4096);
        md.append("# P0 baseline - import / export hot paths\n\n");
        md.append("- generated: ").append(Instant.now()).append('\n');
        for (String note : notes) {
            md.append("- ").append(note).append('\n');
        }
        if (!fixtureBytes.isEmpty()) {
            md.append("- fixtures: ");
            fixtureBytes.forEach((name, bytes) -> md.append(name).append('=').append(bytes).append(" B, "));
            md.setLength(md.length() - 2);
            md.append('\n');
        }
        md.append("\n## Measurements\n\n");
        md.append("| 口径 | 行数 | 耗时 (ms) | rows per s | 峰值堆 (MiB) | 产物字节 | 往返次数 | 备注 |\n");
        md.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (BenchResult result : results) {
            md.append("| ").append(result.scene())
                    .append(" | ").append(result.rows())
                    .append(" | ").append(toMillis(result.elapsedNanos()))
                    .append(" | ").append(rowsPerSecond(result.rows(), result.elapsedNanos()))
                    .append(" | ").append(result.peakHeapBytes() >= 0 ? toMib(result.peakHeapBytes()) : "-")
                    .append(" | ").append(result.artifactBytes() >= 0 ? result.artifactBytes() : "-")
                    .append(" | ").append(result.roundTrips() >= 0 ? result.roundTrips() : "-")
                    .append(" | ").append(result.detail())
                    .append(" |\n");
        }
        md.append("\n## Session status deltas (round trip evidence)\n\n");
        md.append("| 口径 | ").append(String.join(" | ", STATUS_KEYS)).append(" |\n");
        md.append("| --- |").append(" ---: |".repeat(STATUS_KEYS.size())).append("\n");
        for (BenchResult result : results) {
            md.append("| ").append(result.scene()).append(" |");
            for (String key : STATUS_KEYS) {
                Long value = result.status().get(key);
                md.append(' ').append(value == null ? "-" : value).append(" |");
            }
            md.append('\n');
        }
        md.append("\n往返次数 = Questions 差值 - 1（减掉读取 after 值本身的那次 SHOW SESSION STATUS）。\n");
        return md.toString();
    }

    // ---------------------------------------------------------------- small helpers

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

    private static long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static long toMib(long bytes) {
        return bytes < 0 ? -1L : bytes / (1024L * 1024L);
    }

    private static long rowsPerSecond(long rows, long nanos) {
        if (nanos <= 0) {
            return -1L;
        }
        return Math.round(rows / (nanos / 1_000_000_000.0d));
    }
}
