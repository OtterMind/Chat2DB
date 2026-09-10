package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeatable task-level performance rehearsal for the multi-table staging path. The five-percent
 * gate is a source-data sampling contract, not a wall-clock regression assertion: every single
 * shard contributes {@code ceil(rows * 0.05)} rows and the whole transaction must roll back.
 */
class StagingManifestPerformanceIT {

    private static final String DB_TYPE = "H2";
    private static final String SCHEMA = "PUBLIC";
    private static final String PARENT_TABLE = "PERF_PARENT";
    private static final String CHILD_TABLE = "PERF_CHILD";
    private static final int SOURCE_ROWS_PER_TABLE = 100_000;
    private static final int SAMPLE_PERCENT = 5;
    private static final int EXPECTED_SAMPLE_ROWS_PER_TABLE = 5_000;
    private static final long TASK_ID = 805L;

    @TempDir
    Path tempDirectory;

    private IPlugin previousPlugin;
    private Connection keeper;
    private String jdbcUrl;
    private String database;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:h2:mem:staging_perf_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        keeper = DriverManager.getConnection(jdbcUrl, "sa", "");
        database = keeper.getCatalog();
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new H2Plugin());
        ConnectInfo parent = new ConnectInfo();
        parent.setDbType(DB_TYPE);
        parent.setDatabaseName(database);
        parent.setSchemaName(SCHEMA);
        Chat2DBContext.putContext(parent);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        if (keeper != null) {
            try (Statement statement = keeper.createStatement()) {
                statement.execute("DROP ALL OBJECTS");
            } finally {
                keeper.close();
            }
        }
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void rehearsesFivePercentOfTwoHundredThousandDagRowsAndRollsBack() throws Exception {
        createFixtureTables();
        Path parentCsv = writeCsv("parents.csv", "ID,PAYLOAD", (row, column) ->
                column == 0 ? Integer.toString(row) : "parent-" + row);
        Path childCsv = writeCsv("children.csv", "ID,PARENT_ID", (row, column) ->
                Integer.toString(row));

        ImportTableSource parent = tableSource(PARENT_TABLE, parentCsv);
        ImportTableSource child = tableSource(CHILD_TABLE, childCsv);
        List<ImportManifestShard> parentShards = singleShard(parent, 0, "parent-shards");
        List<ImportManifestShard> childShards = singleShard(child, 1, "child-shards");
        assertEquals(1, parentShards.size(), "the exact five-percent threshold requires one shard");
        assertEquals(1, childShards.size(), "the exact five-percent threshold requires one shard");
        assertEquals(SOURCE_ROWS_PER_TABLE, parentShards.get(0).getEstimatedRows());
        assertEquals(SOURCE_ROWS_PER_TABLE, childShards.get(0).getEstimatedRows());
        childShards.get(0).setDependencyShardIds(List.of(parentShards.get(0).getShardId()));

        ImportTableDependency dependency = ImportTableDependency.builder()
                .parentDatabaseName(database)
                .parentSchemaName(SCHEMA)
                .parentTable(PARENT_TABLE)
                .parentColumn("ID")
                .parentTableKey(key(PARENT_TABLE))
                .childDatabaseName(database)
                .childSchemaName(SCHEMA)
                .childTable(CHILD_TABLE)
                .childColumn("PARENT_ID")
                .childTableKey(key(CHILD_TABLE))
                .constraintName("FK_PERF_CHILD_PARENT")
                .keySequence((short) 1)
                .deferrability((short) DatabaseMetaData.importedKeyNotDeferrable)
                .logical(false)
                .build();
        ImportDependencyPlan plan = ImportDependencyPlan.builder()
                .mode(ImportPlanMode.STAGING_FIRST)
                .layers(List.of(List.of(key(PARENT_TABLE)), List.of(key(CHILD_TABLE))))
                .cyclicComponents(List.of())
                .selfReferencingTables(List.of())
                .shardKeys(Map.of())
                .stagingRequired(true)
                .cycleResolutionRequired(false)
                .build();
        List<ImportManifestShard> allShards = new ArrayList<>(parentShards);
        allShards.addAll(childShards);
        ImportManifest manifest = ImportManifestBuilder.build(TASK_ID, "STAGING_REQUIRED",
                "SHA-256:staging-performance-fixture", plan, List.of(dependency), allShards);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .scope(ImportScope.SCHEMA)
                .format("CSV")
                .sourceKind("THIRD_PARTY")
                .cycleStrategy("STAGING_TWO_PHASE")
                .target(TaskTargetSnapshot.builder().databaseName(database).schemaName(SCHEMA).build())
                .tableSources(List.of(parent, child))
                .stagingPolicy(ImportStagingPolicy.builder()
                        .enabled(true).allVarchar(true).twoPhase(true).build())
                .validationOptions(ImportValidationOptions.builder()
                        .sourceProfiling(true).rowCount(true).checksum(true).orphanCheck(true).build())
                .rollbackOptions(ImportRollbackOptions.builder()
                        .fullRollback(true).rehearsal(true).build())
                .performanceSamplePercent(SAMPLE_PERCENT)
                .build();
        ReportContext context = new ReportContext(TASK_ID, tempDirectory.resolve("artifacts"));

        long wallStarted = System.nanoTime();
        new StagingManifestImporter(
                ignored -> DriverManager.getConnection(jdbcUrl, "sa", ""))
                .execute(spec, context, manifest);
        long wallElapsedMillis = (System.nanoTime() - wallStarted) / 1_000_000L;

        assertEquals(1L, count(PARENT_TABLE), "rehearsal must restore the original parent row");
        assertEquals(1L, count(CHILD_TABLE), "rehearsal must restore the original child row");
        assertEquals(0L, scalar("SELECT COUNT(*) FROM " + PARENT_TABLE + " WHERE ID <= "
                + SOURCE_ROWS_PER_TABLE));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM " + CHILD_TABLE + " WHERE ID <= "
                + SOURCE_ROWS_PER_TABLE));

        JSONObject report = context.report();
        assertEquals(SAMPLE_PERCENT, report.getIntValue("samplePercent"));
        assertEquals("REHEARSAL_ROLLED_BACK",
                report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
        assertEquals(EXPECTED_SAMPLE_ROWS_PER_TABLE, profileRows(report, key(PARENT_TABLE)));
        assertEquals(EXPECTED_SAMPLE_ROWS_PER_TABLE, profileRows(report, key(CHILD_TABLE)));
        assertEquals(EXPECTED_SAMPLE_ROWS_PER_TABLE * 2L,
                report.getJSONArray("sourceProfiles").stream()
                        .map(JSONObject.class::cast)
                        .mapToLong(profile -> profile.getLongValue("sourceRows"))
                        .sum());
        assertEquals(SAMPLE_PERCENT,
                EXPECTED_SAMPLE_ROWS_PER_TABLE * 100 / SOURCE_ROWS_PER_TABLE);
        assertEquals(1, report.getJSONArray("orphanChecks").size());
        assertEquals(0L, report.getJSONArray("orphanChecks").getJSONObject(0)
                .getLongValue("orphanRows"));
        assertEquals(1, report.getJSONArray("postImportOrphanChecks").size());
        assertEquals(0L, report.getJSONArray("postImportOrphanChecks").getJSONObject(0)
                .getLongValue("orphanRows"));
        assertEquals(2, report.getJSONArray("rowCounts").size());
        assertTrue(report.getJSONArray("rowCounts").stream()
                .map(JSONObject.class::cast)
                .allMatch(row -> row.getBooleanValue("matched")
                        && row.getLongValue("expectedDelta") == EXPECTED_SAMPLE_ROWS_PER_TABLE));
        assertTrue(report.getLongValue("elapsedMillis") > 0L);
        assertTrue(report.getLongValue("throughputRowsPerSecond") > 0L);

        System.out.printf("[staging-perf] sources=%,d rows, sample=%d%% (%,d rows), "
                        + "reportElapsed=%,d ms, wallElapsed=%,d ms, throughput=%,d rows/s%n",
                SOURCE_ROWS_PER_TABLE * 2L, SAMPLE_PERCENT,
                EXPECTED_SAMPLE_ROWS_PER_TABLE * 2L, report.getLongValue("elapsedMillis"),
                wallElapsedMillis, report.getLongValue("throughputRowsPerSecond"));
    }

    private void createFixtureTables() throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE " + PARENT_TABLE
                    + " (ID BIGINT PRIMARY KEY, PAYLOAD VARCHAR(64) NOT NULL)");
            statement.execute("CREATE TABLE " + CHILD_TABLE
                    + " (ID BIGINT PRIMARY KEY, PARENT_ID BIGINT NOT NULL, "
                    + "CONSTRAINT FK_PERF_CHILD_PARENT FOREIGN KEY (PARENT_ID) REFERENCES "
                    + PARENT_TABLE + "(ID))");
            statement.execute("INSERT INTO " + PARENT_TABLE
                    + " (ID,PAYLOAD) VALUES (200001,'existing')");
            statement.execute("INSERT INTO " + CHILD_TABLE
                    + " (ID,PARENT_ID) VALUES (200001,200001)");
        }
    }

    private Path writeCsv(String fileName, String header, BiFunction<Integer, Integer, String> value)
            throws IOException {
        Path path = tempDirectory.resolve(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(header);
            writer.newLine();
            for (int row = 1; row <= SOURCE_ROWS_PER_TABLE; row++) {
                writer.write(value.apply(row, 0));
                writer.write(',');
                writer.write(value.apply(row, 1));
                writer.newLine();
            }
        }
        return path;
    }

    private ImportTableSource tableSource(String table, Path source) {
        return ImportTableSource.builder()
                .databaseName(database)
                .schemaName(SCHEMA)
                .tableName(table)
                .sourceFile(source.toString())
                .format("CSV")
                .options(ImportOptions.builder().onError("ABORT").build())
                .build();
    }

    private List<ImportManifestShard> singleShard(ImportTableSource source, int layer,
            String outputDirectory) throws IOException {
        return CsvShardPreprocessor.preprocess(Path.of(source.getSourceFile()).toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, tempDirectory.resolve(outputDirectory),
                source.getDatabaseName(), source.getSchemaName(), source.getTableName(), key(source.getTableName()),
                layer, Long.MAX_VALUE);
    }

    private String key(String table) {
        return database + "." + SCHEMA + "." + table;
    }

    private long count(String table) throws SQLException {
        return scalar("SELECT COUNT(*) FROM " + table);
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = keeper.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static long profileRows(JSONObject report, String tableKey) {
        return report.getJSONArray("sourceProfiles").stream()
                .map(JSONObject.class::cast)
                .filter(profile -> tableKey.equals(profile.getString("table")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing source profile for " + tableKey))
                .getLongValue("sourceRows");
    }

    private static final class ReportContext implements TaskExecutionContext {

        private final Long taskId;
        private final Path artifactDirectory;
        private final AtomicInteger artifactSequence = new AtomicInteger();
        private final Map<String, List<Path>> artifacts = new LinkedHashMap<>();

        private ReportContext(Long taskId, Path artifactDirectory) throws IOException {
            this.taskId = taskId;
            this.artifactDirectory = artifactDirectory;
            Files.createDirectories(artifactDirectory);
        }

        @Override
        public Long taskId() {
            return taskId;
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
            return createArtifact(TaskArtifactRole.OUTPUT, outputDirectory, fileName, mediaType);
        }

        @Override
        public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            String safeRole = role.replaceAll("[^A-Za-z0-9._-]", "_");
            Path path = artifactDirectory.resolve(String.format("%02d-%s-%s",
                    artifactSequence.incrementAndGet(), safeRole, fileName));
            artifacts.computeIfAbsent(role, ignored -> new ArrayList<>()).add(path);
            return ArtifactDraft.builder().role(role).temporaryFile(path.toFile())
                    .targetFile(path.toFile()).mediaType(mediaType).build();
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }

        private JSONObject report() throws IOException {
            List<Path> paths = artifacts.get(TaskArtifactRole.IMPORT_REPORT);
            assertFalse(paths == null || paths.isEmpty(), "missing import report");
            return JSON.parseObject(Files.readString(paths.get(paths.size() - 1), StandardCharsets.UTF_8));
        }
    }
}
