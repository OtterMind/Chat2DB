package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
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
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingManifestImporterTest {

    private static final String DB_TYPE = "H2";
    private static final String SCHEMA = "PUBLIC";

    @TempDir
    Path tempDirectory;

    private IPlugin previousPlugin;
    private Connection keeper;
    private String jdbcUrl;
    private String database;
    private int sourceSequence;

    @BeforeEach
    void setUp() throws Exception {
        jdbcUrl = "jdbc:h2:mem:staging_" + UUID.randomUUID().toString().replace("-", "")
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
    void requiresStagingForACycleSoRejectStrategyCannotFallBackToLegacyImport() {
        ImportDependencyPlan cycle = ImportDependencyPlan.builder()
                .mode(ImportPlanMode.SERIAL_SAFE)
                .layers(List.of(List.of(key("LEFT_NODE"), key("RIGHT_NODE"))))
                .cyclicComponents(List.of(List.of(key("LEFT_NODE"), key("RIGHT_NODE"))))
                .selfReferencingTables(List.of())
                .shardKeys(Map.of())
                .cycleResolutionRequired(true)
                .build();
        ImportManifest manifest = ImportManifest.builder().dependencyPlan(cycle).build();
        ImportTaskSpec spec = ImportTaskSpec.builder().cycleStrategy("REJECT").build();

        assertTrue(StagingManifestImporter.required(spec, manifest));
    }

    @Test
    void schemaV2RejectsMissingAndMismatchedTableKeysBeforeOpeningJdbc() throws Exception {
        String table = "V2_IDENTITY_GUARD";
        ImportTableSource source = tableSource(table,
                source("v2-identity-guard", "ID\n1\n"), null);
        ImportTaskSpec taskSpec = spec(List.of(source));
        AtomicInteger opens = new AtomicInteger();
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> {
            opens.incrementAndGet();
            return DriverManager.getConnection(jdbcUrl, "sa", "");
        });
        String[] invalidKeys = {null, key(table.toLowerCase(java.util.Locale.ROOT))};

        for (int index = 0; index < invalidKeys.length; index++) {
            ImportManifest manifest = ImportManifest.builder()
                    .schemaVersion(2)
                    .taskId(130L + index)
                    .mode(ImportPlanMode.STAGING_FIRST)
                    .shards(List.of(ImportManifestShard.builder()
                            .shardId("invalid-v2-" + index)
                            .databaseName(database)
                            .schemaName(SCHEMA)
                            .tableName(table)
                            .tableKey(invalidKeys[index])
                            .sourcePath(source.getSourceFile())
                            .estimatedRows(1L)
                            .expectedChecksum("CRC32:0")
                            .dependencyShardIds(List.of())
                            .build()))
                    .build();
            manifest.setManifestFingerprint(ImportManifestIntegrity.calculate(manifest));
            RecordingContext context = new RecordingContext(manifest.getTaskId(),
                    tempDirectory.resolve("artifacts-invalid-v2-" + index));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> importer.execute(taskSpec, context, manifest));

            assertTrue(failure.getMessage().startsWith("Schema v2 manifest shard"));
        }

        assertEquals(0, opens.get());
    }

    @Test
    void rejectsAPureLogicalCycleBeforePublishingEitherTable() throws Exception {
        executeSql("CREATE TABLE LOGICAL_A (ID BIGINT PRIMARY KEY, B_ID BIGINT NOT NULL)");
        executeSql("CREATE TABLE LOGICAL_B (ID BIGINT PRIMARY KEY, A_ID BIGINT NOT NULL)");
        ImportTableSource sourceA = tableSource("LOGICAL_A", source("logical-a", "ID,B_ID\n1,1\n"), null);
        ImportTableSource sourceB = tableSource("LOGICAL_B", source("logical-b", "ID,A_ID\n1,1\n"), null);
        List<ImportManifestShard> shardsA = shards(sourceA, 0, Long.MAX_VALUE);
        List<ImportManifestShard> shardsB = shards(sourceB, 0, Long.MAX_VALUE);
        ImportTableDependency aToB = dependency("LOGICAL_A", "ID", "LOGICAL_B", "A_ID",
                "LOGICAL_A_TO_B");
        ImportTableDependency bToA = dependency("LOGICAL_B", "ID", "LOGICAL_A", "B_ID",
                "LOGICAL_B_TO_A");
        aToB.setLogical(true);
        bToA.setLogical(true);
        ImportDependencyPlan cycle = ImportDependencyPlan.builder()
                .mode(ImportPlanMode.STAGING_FIRST)
                .layers(List.of(List.of(key("LOGICAL_A"), key("LOGICAL_B"))))
                .cyclicComponents(List.of(List.of(key("LOGICAL_A"), key("LOGICAL_B"))))
                .selfReferencingTables(List.of())
                .shardKeys(Map.of())
                .stagingRequired(true)
                .cycleResolutionRequired(true)
                .build();
        ImportManifest manifest = manifest(112L, cycle, List.of(aToB, bToA), shardsA, shardsB);
        RecordingContext context = new RecordingContext(112L,
                tempDirectory.resolve("artifacts-logical-cycle"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(sourceA, sourceB)), context, manifest));

        assertTrue(failure.getMessage().contains("Cyclic dependencies require"));
        assertEquals(0L, count("LOGICAL_A"));
        assertEquals(0L, count("LOGICAL_B"));
    }

    @Test
    void physicallyClosesDedicatedConnectionAfterSuccessfulImport() throws Exception {
        executeSql("CREATE TABLE CLOSE_TEST (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("close", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("CLOSE_TEST", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(106L, dagPlan(List.of(List.of(key("CLOSE_TEST")))),
                List.of(), rowShards);
        RecordingContext context = new RecordingContext(106L, tempDirectory.resolve("artifacts-close"));
        AtomicReference<Connection> opened = new AtomicReference<>();
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> {
            Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            opened.set(connection);
            return connection;
        });

        importer.execute(spec(List.of(rows)), context, manifest);

        assertEquals(1L, count("CLOSE_TEST"));
        assertTrue(opened.get().isClosed(), "dedicated staging connections must never return to a pool");
    }

    @Test
    void targetCommitSQLExceptionIsUnknownAndNeverRolledBackOrRestored() throws Exception {
        executeSql("CREATE TABLE UNKNOWN_TARGET_COMMIT (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("unknown-target-commit", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("UNKNOWN_TARGET_COMMIT", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(113L,
                dagPlan(List.of(List.of(key("UNKNOWN_TARGET_COMMIT")))), List.of(), rowShards);
        RecordingContext context = new RecordingContext(113L,
                tempDirectory.resolve("artifacts-unknown-target-commit"));
        CommitProbe probe = new CommitProbe();
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> commitFailsAfterSuccess(
                DriverManager.getConnection(jdbcUrl, "sa", ""), 1, probe,
                new SQLException("simulated lost commit acknowledgement")));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer.execute(spec(List.of(rows)), context, manifest));

        assertTrue(failure.getMessage().contains("commit outcome is unknown"));
        assertTrue(failure.getMessage().contains("Manually verify target data before retrying"));
        assertEquals(1L, count("UNKNOWN_TARGET_COMMIT"));
        assertCommitUnknown(context, "TARGET", false);
        assertEquals(1, probe.commitCalls.get());
        assertEquals(0, probe.rollbackCalls.get());
        assertEquals(0, probe.autoCommitRestoreCalls.get());
        assertTrue(probe.closed.get());
    }

    @Test
    void finalizationCommitRuntimeFailureIsAlsoUnknownAndRequiresManualReconciliation() throws Exception {
        executeSql("CREATE TABLE UNKNOWN_FINAL_COMMIT (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("unknown-final-commit", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("UNKNOWN_FINAL_COMMIT", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(114L,
                dagPlan(List.of(List.of(key("UNKNOWN_FINAL_COMMIT")))), List.of(), rowShards);
        RecordingContext context = new RecordingContext(114L,
                tempDirectory.resolve("artifacts-unknown-final-commit"));
        CommitProbe probe = new CommitProbe();
        ImportTaskSpec taskSpec = spec(List.of(rows));
        taskSpec.setFinalizationOptions(ImportFinalizationOptions.builder().resetSequences(true).build());
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> commitFailsAfterSuccess(
                DriverManager.getConnection(jdbcUrl, "sa", ""), 2, probe,
                new IllegalStateException("simulated unchecked commit acknowledgement loss")));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer.execute(taskSpec, context, manifest));

        assertTrue(failure.getMessage().contains("commit outcome is unknown"));
        assertEquals(1L, count("UNKNOWN_FINAL_COMMIT"));
        assertCommitUnknown(context, "FINALIZATION", true);
        assertEquals(2, probe.commitCalls.get());
        assertEquals(0, probe.rollbackCalls.get());
        assertEquals(0, probe.autoCommitRestoreCalls.get());
        assertTrue(probe.closed.get());
    }

    @Test
    void skipsAnEmptyFinalizationTransactionAndCommitsOnlyOnce() throws Exception {
        executeSql("CREATE TABLE SINGLE_COMMIT (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        ImportTableSource rows = tableSource("SINGLE_COMMIT",
                source("single-commit", "ID,NAME\n1,Alice\n"), null);
        ImportManifest manifest = manifest(116L,
                dagPlan(List.of(List.of(key("SINGLE_COMMIT")))), List.of(),
                shards(rows, 0, Long.MAX_VALUE));
        RecordingContext context = new RecordingContext(116L,
                tempDirectory.resolve("artifacts-single-commit"));
        CommitProbe probe = new CommitProbe();
        StagingManifestImporter importer = new StagingManifestImporter(ignored -> commitFailsAfterSuccess(
                DriverManager.getConnection(jdbcUrl, "sa", ""), 2, probe,
                new SQLException("a second commit must not be attempted")));

        importer.execute(spec(List.of(rows)), context, manifest);

        assertEquals(1L, count("SINGLE_COMMIT"));
        assertEquals(1, probe.commitCalls.get());
        assertEquals(0, probe.rollbackCalls.get());
        assertEquals(1, probe.autoCommitRestoreCalls.get());
        assertFalse(context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT).containsKey("finalization"));
    }

    @Test
    void discardsDedicatedConnectionWhenSessionStateRestoreFails() throws Exception {
        executeSql("CREATE TABLE RESTORE_TEST (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("restore", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("RESTORE_TEST", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(108L, dagPlan(List.of(List.of(key("RESTORE_TEST")))),
                List.of(), rowShards);
        RecordingContext context = new RecordingContext(108L,
                tempDirectory.resolve("artifacts-restore"));
        AtomicBoolean closed = new AtomicBoolean();
        StagingManifestImporter importer = new StagingManifestImporter(ignored ->
                failingAutoCommitRestore(DriverManager.getConnection(jdbcUrl, "sa", ""), closed));

        importer.execute(spec(List.of(rows)), context, manifest);

        assertEquals(1L, count("RESTORE_TEST"));
        assertTrue(closed.get(), "session restore failures must discard the dedicated connection");
    }

    @Test
    void rejectsStagingWhenJdbcTransactionsAreUnsupportedAndClosesConnection() throws Exception {
        executeSql("CREATE TABLE NO_TX (ID BIGINT PRIMARY KEY)");
        Path csv = source("no-tx", "ID\n1\n");
        ImportTableSource rows = tableSource("NO_TX", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(107L, dagPlan(List.of(List.of(key("NO_TX")))),
                List.of(), rowShards);
        ImportTaskSpec taskSpec = spec(List.of(rows));
        RecordingContext context = new RecordingContext(107L, tempDirectory.resolve("artifacts-no-tx"));
        AtomicBoolean closed = new AtomicBoolean();
        StagingManifestImporter importer = new StagingManifestImporter(ignored ->
                withoutTransactionSupport(DriverManager.getConnection(jdbcUrl, "sa", ""), closed));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer.execute(taskSpec, context, manifest));

        assertTrue(failure.getMessage().contains("Staging import requires JDBC transaction support"));
        assertEquals(0L, count("NO_TX"), "transaction preflight must run before target writes");
        assertTrue(closed.get(), "a failed preflight must still discard its dedicated connection");
    }

    @Test
    void rejectsFullRollbackWithPostCommitMaintenanceBeforeTargetWrites() throws Exception {
        executeSql("CREATE TABLE FINALIZATION_GUARD (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("finalization-guard", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("FINALIZATION_GUARD", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(109L,
                dagPlan(List.of(List.of(key("FINALIZATION_GUARD")))), List.of(), rowShards);
        ImportTaskSpec taskSpec = spec(List.of(rows));
        taskSpec.setRollbackOptions(ImportRollbackOptions.builder().fullRollback(true).build());
        taskSpec.setFinalizationOptions(ImportFinalizationOptions.builder()
                .resetSequences(true).rebuildIndexes(true).refreshStatistics(true).build());
        RecordingContext context = new RecordingContext(109L,
                tempDirectory.resolve("artifacts-finalization-guard"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(taskSpec, context, manifest));

        assertTrue(failure.getMessage().contains("Full rollback cannot be combined"));
        assertEquals(0L, count("FINALIZATION_GUARD"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_BEFORE_TRANSACTION",
                report.getJSONObject("transaction").getString("outcome"));
    }

    @Test
    void rejectsNonTransactionalH2FinalizationBeforeTargetWrites() throws Exception {
        executeSql("CREATE TABLE FINALIZATION_FAILURE (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        ImportTableSource rows = tableSource("FINALIZATION_FAILURE",
                source("finalization-failure", "ID,NAME\n1,Alice\n"), null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(115L,
                dagPlan(List.of(List.of(key("FINALIZATION_FAILURE")))), List.of(), rowShards);
        ImportTaskSpec taskSpec = spec(List.of(rows));
        taskSpec.setFinalizationOptions(ImportFinalizationOptions.builder().rebuildIndexes(true).build());
        RecordingContext context = new RecordingContext(115L,
                tempDirectory.resolve("artifacts-finalization-failure"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(taskSpec, context, manifest));

        assertEquals(TaskErrorCode.IMPORT_FAILED.name(), failure.getCode());
        assertTrue(failure.getMessage().contains("not supported for H2"));
        assertEquals(0L, count("FINALIZATION_FAILURE"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_BEFORE_TRANSACTION", report.getJSONObject("transaction").getString("outcome"));
        assertFalse(report.getJSONObject("failure").getBooleanValue("targetCommitted"));
    }

    @Test
    void rejectsUnverifiedKingbaseFinalizationBeforeTargetWrites() throws Exception {
        executeSql("CREATE TABLE KINGBASE_FINALIZATION_GUARD (ID BIGINT PRIMARY KEY, NAME VARCHAR(64))");
        ImportTableSource rows = tableSource("KINGBASE_FINALIZATION_GUARD",
                source("kingbase-finalization-guard", "ID,NAME\n1,Alice\n"), null);
        ImportManifest manifest = manifest(119L,
                dagPlan(List.of(List.of(key("KINGBASE_FINALIZATION_GUARD")))), List.of(),
                shards(rows, 0, Long.MAX_VALUE));
        ImportTaskSpec taskSpec = spec(List.of(rows));
        taskSpec.setFinalizationOptions(ImportFinalizationOptions.builder()
                .resetSequences(true).rebuildIndexes(true).refreshStatistics(true).build());
        RecordingContext context = new RecordingContext(119L,
                tempDirectory.resolve("artifacts-kingbase-finalization-guard"));
        ConnectInfo kingbase = Chat2DBContext.getConnectInfo().copy();
        kingbase.setDbType("KINGBASE");
        Chat2DBContext.putContext(kingbase);

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(taskSpec, context, manifest));

        assertEquals(TaskErrorCode.IMPORT_FAILED.name(), failure.getCode());
        assertTrue(failure.getMessage().contains("not supported for Kingbase"));
        assertEquals(0L, count("KINGBASE_FINALIZATION_GUARD"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_BEFORE_TRANSACTION", report.getJSONObject("transaction").getString("outcome"));
        assertFalse(report.getJSONObject("failure").getBooleanValue("targetCommitted"));
    }

    @Test
    void rejectsRollbackRehearsalForGeneratedSequencesBeforeLoadingStagingRows() throws Exception {
        executeSql("CREATE TABLE PRELOAD_GUARD (ID BIGINT PRIMARY KEY)");
        executeSql("CREATE TABLE GENERATED_ROLLBACK (ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "NAME VARCHAR(64) NOT NULL)");
        ImportTableSource preload = tableSource("PRELOAD_GUARD",
                source("preload-guard", "ID\nnot-a-number\n"),
                ImportOptions.builder().onError("ABORT").build());
        ImportTableSource generated = tableSource("GENERATED_ROLLBACK",
                source("generated-rollback", "NAME\nAlice\n"),
                ImportOptions.builder().onError("ABORT").build());
        List<ImportManifestShard> preloadShards = shards(preload, 0, 1024L);
        List<ImportManifestShard> generatedShards = shards(generated, 0, 1024L);
        ImportManifest manifest = manifest(109L,
                dagPlan(List.of(List.of(key("PRELOAD_GUARD"), key("GENERATED_ROLLBACK")))),
                List.of(), preloadShards, generatedShards);
        ImportTaskSpec taskSpec = spec(List.of(preload, generated));
        taskSpec.setRollbackOptions(ImportRollbackOptions.builder()
                .fullRollback(true).rehearsal(true).build());
        RecordingContext context = new RecordingContext(109L,
                tempDirectory.resolve("artifacts-generated-rollback"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(taskSpec, context, manifest));

        assertTrue(failure.getMessage().contains("cannot prove generated sequence restoration"));
        assertEquals(0L, count("PRELOAD_GUARD"));
        assertEquals(0L, count("GENERATED_ROLLBACK"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_ROLLED_BACK",
                report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("failure").getString("reason")
                .contains("generated sequence restoration"));
    }

    @Test
    void mysqlRollbackSafetyAcceptsOnlyExplicitTransactionalEngines() {
        StagingManifestImporter.requireTransactionalMysqlEngine("app.orders", "InnoDB");

        TaskExecutionException myisam = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.requireTransactionalMysqlEngine("app.orders", "MyISAM"));
        TaskExecutionException unknown = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.requireTransactionalMysqlEngine("app.orders", null));

        assertTrue(myisam.getMessage().contains("MyISAM"));
        assertTrue(unknown.getMessage().contains("UNKNOWN"));
    }

    @Test
    void mysqlEngineValidationLocksEveryTargetBeforeInspectingAnyEngine() throws Exception {
        ImportTableSource tableB = ImportTableSource.builder()
                .databaseName("app").tableName("B").build();
        ImportTableSource tableA = ImportTableSource.builder()
                .databaseName("app").tableName("A").build();
        ImportTaskSpec taskSpec = ImportTaskSpec.builder()
                .scope(ImportScope.DATABASE)
                .target(TaskTargetSnapshot.builder().databaseName("app").build())
                .tableSources(List.of(tableB, tableA))
                .build();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("app");
        List<String> events = new ArrayList<>();
        Connection connection = mysqlSafetyConnection(events,
                Map.of("app.A", "InnoDB", "app.B", "InnoDB"), null, false);

        connection.setAutoCommit(false);
        StagingManifestImporter.lockAndValidateMysqlTargets(connection,
                new RecordingContext(120L, tempDirectory.resolve("mysql-lock-order")),
                mysqlMetadata(), connectInfo, taskSpec);

        assertEquals(List.of(
                "AUTOCOMMIT:false",
                "QUERY:SELECT 1 FROM `app`.`A` LIMIT 0",
                "QUERY:SELECT 1 FROM `app`.`B` LIMIT 0",
                "ENGINE:app.A",
                "ENGINE:app.B"), events);
    }

    @Test
    void mysqlSchemaFallbackUsesTheResolvedDatabaseForLockAndEngineValidation() throws Exception {
        ImportTableSource table = ImportTableSource.builder()
                .schemaName("tenant").tableName("ORDERS").build();
        ImportTaskSpec taskSpec = ImportTaskSpec.builder()
                .scope(ImportScope.TABLE)
                .target(TaskTargetSnapshot.builder().schemaName("tenant").tableName("ORDERS").build())
                .tableSources(List.of(table))
                .build();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setSchemaName("tenant");
        List<String> events = new ArrayList<>();
        Connection connection = mysqlSafetyConnection(events,
                Map.of("tenant.ORDERS", "InnoDB"), null, false);

        connection.setAutoCommit(false);
        StagingManifestImporter.lockAndValidateMysqlTargets(connection,
                new RecordingContext(127L, tempDirectory.resolve("mysql-schema-fallback")),
                mysqlMetadata(), connectInfo, taskSpec);

        assertEquals(List.of(
                "AUTOCOMMIT:false",
                "QUERY:SELECT 1 FROM `tenant`.`ORDERS` LIMIT 0",
                "ENGINE:tenant.ORDERS"), events);
    }

    @Test
    void mysqlLockFailureStopsBeforeAnyEngineInspection() throws Exception {
        ImportTableSource tableB = ImportTableSource.builder()
                .databaseName("app").tableName("B").build();
        ImportTableSource tableA = ImportTableSource.builder()
                .databaseName("app").tableName("A").build();
        ImportTaskSpec taskSpec = ImportTaskSpec.builder()
                .scope(ImportScope.DATABASE)
                .target(TaskTargetSnapshot.builder().databaseName("app").build())
                .tableSources(List.of(tableB, tableA))
                .build();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("app");
        List<String> events = new ArrayList<>();
        Connection connection = mysqlSafetyConnection(events,
                Map.of("app.A", "InnoDB", "app.B", "InnoDB"),
                "SELECT 1 FROM `app`.`B` LIMIT 0", false);

        connection.setAutoCommit(false);
        SQLException failure = assertThrows(SQLException.class,
                () -> StagingManifestImporter.lockAndValidateMysqlTargets(connection,
                        new RecordingContext(121L, tempDirectory.resolve("mysql-lock-failure")),
                        mysqlMetadata(), connectInfo, taskSpec));

        assertTrue(failure.getMessage().contains("simulated metadata lock failure"));
        assertEquals(List.of(
                "AUTOCOMMIT:false",
                "QUERY:SELECT 1 FROM `app`.`A` LIMIT 0",
                "QUERY:SELECT 1 FROM `app`.`B` LIMIT 0"), events);
        assertTrue(events.stream().noneMatch(event -> event.startsWith("ENGINE:")));
    }

    @Test
    void mysqlNonTransactionalEngineFailsOnlyAfterAllTargetsAreLocked() throws Exception {
        ImportTableSource tableB = ImportTableSource.builder()
                .databaseName("app").tableName("B").build();
        ImportTableSource tableA = ImportTableSource.builder()
                .databaseName("app").tableName("A").build();
        ImportTaskSpec taskSpec = ImportTaskSpec.builder()
                .scope(ImportScope.DATABASE)
                .target(TaskTargetSnapshot.builder().databaseName("app").build())
                .tableSources(List.of(tableB, tableA))
                .build();
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("app");
        List<String> events = new ArrayList<>();
        Connection connection = mysqlSafetyConnection(events,
                Map.of("app.A", "InnoDB", "app.B", "MyISAM"), null, false);

        connection.setAutoCommit(false);
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.lockAndValidateMysqlTargets(connection,
                        new RecordingContext(122L, tempDirectory.resolve("mysql-engine-failure")),
                        mysqlMetadata(), connectInfo, taskSpec));

        assertTrue(failure.getMessage().contains("MyISAM"));
        assertEquals(List.of(
                "AUTOCOMMIT:false",
                "QUERY:SELECT 1 FROM `app`.`A` LIMIT 0",
                "QUERY:SELECT 1 FROM `app`.`B` LIMIT 0",
                "ENGINE:app.A",
                "ENGINE:app.B"), events);
    }

    @Test
    void finalizationActionFailureRollsBackBeforeAutocommitRestore() throws Exception {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("sequenceReset", "COMPLETED");
        first.put("indexesRebuilt", "FAILED: simulated index failure");
        first.put("statisticsRefreshed", "PENDING");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("sequenceReset", "PENDING");
        second.put("indexesRebuilt", "PENDING");
        second.put("statisticsRefreshed", "PENDING");
        List<Map<String, Object>> finalization = new ArrayList<>(List.of(first, second));
        List<String> events = new ArrayList<>();
        Connection connection = finalizationConnection(events, false);
        SQLException failure = new SQLException("simulated index failure");

        assertTrue(StagingManifestImporter.rollbackFinalization(connection, finalization, failure));
        connection.setAutoCommit(true);

        assertEquals(List.of("ROLLBACK", "AUTOCOMMIT:true"), events);
        assertEquals("ROLLED_BACK", first.get("sequenceReset"));
        assertEquals("FAILED: simulated index failure", first.get("indexesRebuilt"));
        assertEquals("NOT_ATTEMPTED", first.get("statisticsRefreshed"));
        assertEquals("NOT_ATTEMPTED", second.get("sequenceReset"));
        assertEquals("NOT_ATTEMPTED", second.get("indexesRebuilt"));
        assertEquals("NOT_ATTEMPTED", second.get("statisticsRefreshed"));
        assertTrue(events.stream().noneMatch("COMMIT"::equals));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void finalizationRollbackFailureIsSuppressedAndDoesNotRestoreAutocommit() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sequenceReset", "COMPLETED");
        item.put("indexesRebuilt", "FAILED: simulated index failure");
        item.put("statisticsRefreshed", "PENDING");
        List<Map<String, Object>> finalization = new ArrayList<>(List.of(item));
        List<String> events = new ArrayList<>();
        Connection connection = finalizationConnection(events, true);
        SQLException failure = new SQLException("simulated index failure");

        assertFalse(StagingManifestImporter.rollbackFinalization(connection, finalization, failure));

        assertEquals(List.of("ROLLBACK"), events);
        assertEquals("COMPLETED", item.get("sequenceReset"));
        assertEquals("PENDING", item.get("statisticsRefreshed"));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("rollback failed"));
    }

    @Test
    void mysqlStrictModePreservesExistingModesAndAddsStrictAllTables() {
        assertEquals("STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE",
                StagingManifestImporter.strictSqlMode(null));
        assertEquals("NO_ZERO_DATE,ANSI,STRICT_ALL_TABLES,NO_ZERO_IN_DATE",
                StagingManifestImporter.strictSqlMode("NO_ZERO_DATE,ANSI"));
        assertEquals("STRICT_ALL_TABLES,ANSI,NO_ZERO_DATE,NO_ZERO_IN_DATE",
                StagingManifestImporter.strictSqlMode("STRICT_ALL_TABLES,ANSI"));
    }

    @Test
    void mysqlConversionWarningIsAnImportFailure() {
        SQLWarning warning = new SQLWarning("Data truncated for column ID", "01000", 1265);
        AtomicBoolean statementCleared = new AtomicBoolean();
        AtomicBoolean connectionCleared = new AtomicBoolean();
        Statement statement = warningSource(Statement.class, warning, statementCleared);
        Connection connection = warningSource(Connection.class, null, connectionCleared);

        SQLException failure = assertThrows(SQLException.class,
                () -> StagingManifestImporter.requireNoSqlWarnings(connection, statement));

        assertTrue(failure.getMessage().contains("Data truncated"));
        assertEquals(1265, failure.getErrorCode());
        assertTrue(statementCleared.get());
        assertTrue(connectionCleared.get());
    }

    @Test
    void commitsTwoTableDagInDependencyOrder() throws Exception {
        executeSql("CREATE TABLE USERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        executeSql("CREATE TABLE ORDERS (ID BIGINT PRIMARY KEY, USER_ID BIGINT NOT NULL, "
                + "CONSTRAINT FK_ORDERS_USERS FOREIGN KEY (USER_ID) REFERENCES USERS(ID))");
        Path usersCsv = source("users", "ID,NAME\n1,Alice\n");
        Path ordersCsv = source("orders", "ID,USER_ID\n10,1\n");
        ImportTableSource users = tableSource("USERS", usersCsv, null);
        ImportTableSource orders = tableSource("ORDERS", ordersCsv, null);
        List<ImportManifestShard> userShards = shards(users, 0, Long.MAX_VALUE);
        List<ImportManifestShard> orderShards = shards(orders, 1, Long.MAX_VALUE);
        List<String> parentShardIds = userShards.stream().map(ImportManifestShard::getShardId).toList();
        orderShards.forEach(shard -> shard.setDependencyShardIds(parentShardIds));
        ImportTableDependency dependency = dependency("USERS", "ID", "ORDERS", "USER_ID",
                "FK_ORDERS_USERS");
        ImportDependencyPlan plan = dagPlan(List.of(List.of(key("USERS")), List.of(key("ORDERS"))));
        ImportManifest manifest = manifest(101L, plan, List.of(dependency), userShards, orderShards);
        ImportTaskSpec spec = spec(List.of(users, orders));
        RecordingContext context = new RecordingContext(101L, tempDirectory.resolve("artifacts-dag"));
        List<String> queries = new ArrayList<>();
        StagingManifestImporter importer = new StagingManifestImporter(ignored ->
                recordingQueries(DriverManager.getConnection(jdbcUrl, "sa", ""), queries));

        importer.execute(spec, context, manifest);

        assertEquals(1L, count("USERS"));
        assertEquals(1L, count("ORDERS"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM ORDERS o JOIN USERS u ON u.ID=o.USER_ID"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("COMMITTED", report.getJSONObject("transaction").getString("outcome"));
        assertEquals(2, report.getJSONArray("rowCounts").size());
        assertEquals("TARGET", report.getJSONArray("postImportOrphanChecks")
                .getJSONObject(0).getString("source"));
        assertTrue(queries.stream().anyMatch(sql -> sql.contains(".\"ORDERS\" c WHERE EXISTS")
                && sql.contains("FROM \"c2d_typed_") && sql.contains(".\"USERS\" p")),
                "post-import orphan validation must query actual child and parent targets");
    }

    @Test
    void importsQuotedTableNamesThatDifferOnlyByCaseWithoutCollapsingTheirStages() throws Exception {
        executeSql("CREATE TABLE \"Users\" (\"ID\" BIGINT PRIMARY KEY, \"NAME\" VARCHAR(64) NOT NULL)");
        executeSql("CREATE TABLE \"users\" (\"ID\" BIGINT PRIMARY KEY, \"PARENT_ID\" BIGINT NOT NULL, "
                + "CONSTRAINT \"fk_users_Users\" FOREIGN KEY (\"PARENT_ID\") REFERENCES \"Users\"(\"ID\"))");
        ImportTableSource parent = tableSource("Users",
                source("quoted-parent", "ID,NAME\n1,Alice\n"), null);
        ImportTableSource child = tableSource("users",
                source("quoted-child", "ID,PARENT_ID\n10,1\n"), null);
        List<ImportManifestShard> parentShards = shards(parent, 0, Long.MAX_VALUE);
        List<ImportManifestShard> childShards = shards(child, 1, Long.MAX_VALUE);
        List<String> parentShardIds = parentShards.stream()
                .map(ImportManifestShard::getShardId).toList();
        childShards.forEach(shard -> shard.setDependencyShardIds(parentShardIds));
        ImportTableDependency dependency = dependency("Users", "ID", "users", "PARENT_ID",
                "fk_users_Users");
        ImportDependencyPlan plan = dagPlan(List.of(List.of(key("Users")), List.of(key("users"))));
        ImportManifest manifest = manifest(123L, plan, List.of(dependency), parentShards, childShards);
        RecordingContext context = new RecordingContext(123L,
                tempDirectory.resolve("artifacts-quoted-case"));

        importer().execute(spec(List.of(parent, child)), context, manifest);

        assertEquals(1L, scalar("SELECT COUNT(*) FROM \"Users\" WHERE \"ID\"=1"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM \"users\" WHERE \"ID\"=10"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM \"users\" c JOIN \"Users\" p "
                + "ON p.\"ID\"=c.\"PARENT_ID\""));
        JSONArray rowCounts = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT)
                .getJSONArray("rowCounts");
        assertEquals(2, rowCounts.size());
        List<String> reportedTables = rowCounts.stream()
                .map(value -> ((JSONObject) value).getString("table"))
                .toList();
        assertTrue(reportedTables.contains(key("Users")));
        assertTrue(reportedTables.contains(key("users")));
    }

    @Test
    void importsCaseDistinctQuotedColumnsAndUsesTheExactLogicalDependencyColumn() throws Exception {
        executeSql("CREATE TABLE QUOTED_COLUMN_PARENT (\"ID\" BIGINT PRIMARY KEY)");
        executeSql("CREATE TABLE QUOTED_COLUMN_CHILD (\"ID\" BIGINT PRIMARY KEY, \"id\" BIGINT NOT NULL)");
        ImportTableSource parent = tableSource("QUOTED_COLUMN_PARENT",
                source("quoted-column-parent", "ID\n2\n"), null);
        ImportTableSource child = tableSource("QUOTED_COLUMN_CHILD",
                source("quoted-column-child", "ID,id\n1,2\n"), null);
        List<ImportManifestShard> parentShards = shards(parent, 0, Long.MAX_VALUE);
        List<ImportManifestShard> childShards = shards(child, 1, Long.MAX_VALUE);
        List<String> parentShardIds = parentShards.stream()
                .map(ImportManifestShard::getShardId).toList();
        childShards.forEach(shard -> shard.setDependencyShardIds(parentShardIds));
        ImportTableDependency dependency = dependency("QUOTED_COLUMN_PARENT", "ID",
                "QUOTED_COLUMN_CHILD", "id", "logical_quoted_column");
        dependency.setLogical(true);
        ImportManifest manifest = manifest(132L,
                dagPlan(List.of(List.of(key("QUOTED_COLUMN_PARENT")),
                        List.of(key("QUOTED_COLUMN_CHILD")))),
                List.of(dependency), parentShards, childShards);
        RecordingContext context = new RecordingContext(132L,
                tempDirectory.resolve("artifacts-quoted-columns"));

        importer().execute(spec(List.of(parent, child)), context, manifest);

        assertEquals(1L, scalar("SELECT COUNT(*) FROM QUOTED_COLUMN_CHILD "
                + "WHERE \"ID\"=1 AND \"id\"=2"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM QUOTED_COLUMN_CHILD c "
                + "JOIN QUOTED_COLUMN_PARENT p ON p.\"ID\"=c.\"id\""));
    }

    @Test
    void rejectsAmbiguousLogicalDependencyColumnWithoutPublishingRows() throws Exception {
        executeSql("CREATE TABLE AMBIGUOUS_COLUMN_PARENT (\"ID\" BIGINT PRIMARY KEY)");
        executeSql("CREATE TABLE AMBIGUOUS_COLUMN_CHILD (\"ID\" BIGINT PRIMARY KEY, \"id\" BIGINT NOT NULL)");
        ImportTableSource parent = tableSource("AMBIGUOUS_COLUMN_PARENT",
                source("ambiguous-column-parent", "ID\n2\n"), null);
        ImportTableSource child = tableSource("AMBIGUOUS_COLUMN_CHILD",
                source("ambiguous-column-child", "ID,id\n1,2\n"), null);
        List<ImportManifestShard> parentShards = shards(parent, 0, Long.MAX_VALUE);
        List<ImportManifestShard> childShards = shards(child, 1, Long.MAX_VALUE);
        ImportTableDependency dependency = dependency("AMBIGUOUS_COLUMN_PARENT", "ID",
                "AMBIGUOUS_COLUMN_CHILD", "Id", "logical_ambiguous_column");
        dependency.setLogical(true);
        ImportManifest manifest = manifest(133L,
                dagPlan(List.of(List.of(key("AMBIGUOUS_COLUMN_PARENT")),
                        List.of(key("AMBIGUOUS_COLUMN_CHILD")))),
                List.of(dependency), parentShards, childShards);
        RecordingContext context = new RecordingContext(133L,
                tempDirectory.resolve("artifacts-ambiguous-column"));
        List<String> statements = new ArrayList<>();
        StagingManifestImporter importer = new StagingManifestImporter(ignored ->
                recordingStatements(DriverManager.getConnection(jdbcUrl, "sa", ""), statements));

        ParamBusinessException failure = assertThrows(ParamBusinessException.class,
                () -> importer.execute(spec(List.of(parent, child)), context, manifest));

        assertTrue(failure.getArgs()[0].toString()
                .contains("Ambiguous logical dependency child column: Id"));
        assertTrue(statements.stream().noneMatch(sql -> sql.startsWith("CREATE TABLE")),
                "dependency ambiguity must fail before staging DDL");
        assertEquals(0L, count("AMBIGUOUS_COLUMN_PARENT"));
        assertEquals(0L, count("AMBIGUOUS_COLUMN_CHILD"));
    }

    @Test
    void h2SequenceResetNeverLowersAnExistingIdentityHighWaterMark() throws Exception {
        executeSql("CREATE TABLE IDENTITY_SAFE (ID BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                + "NAME VARCHAR(64) NOT NULL)");
        executeSql("INSERT INTO IDENTITY_SAFE (NAME) VALUES ('existing')");
        executeSql("ALTER TABLE IDENTITY_SAFE ALTER COLUMN ID RESTART WITH 101");
        ImportTableSource rows = tableSource("IDENTITY_SAFE",
                source("identity-safe", "ID,NAME\n50,imported\n"), null);
        ImportManifest manifest = manifest(124L,
                dagPlan(List.of(List.of(key("IDENTITY_SAFE")))), List.of(),
                shards(rows, 0, Long.MAX_VALUE));
        ImportTaskSpec taskSpec = spec(List.of(rows));
        taskSpec.setFinalizationOptions(ImportFinalizationOptions.builder()
                .resetSequences(true).build());
        RecordingContext context = new RecordingContext(124L,
                tempDirectory.resolve("artifacts-identity-safe"));
        List<String> statements = new ArrayList<>();
        StagingManifestImporter importer = new StagingManifestImporter(ignored ->
                recordingStatements(DriverManager.getConnection(jdbcUrl, "sa", ""), statements));

        importer.execute(taskSpec, context, manifest);
        executeSql("INSERT INTO IDENTITY_SAFE (NAME) VALUES ('after-import')");

        assertEquals(1L, scalar("SELECT COUNT(*) FROM IDENTITY_SAFE WHERE ID=101"));
        assertTrue(statements.stream().noneMatch(sql -> sql.toUpperCase().contains("RESTART WITH")));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("ALREADY_SAFE", report.getJSONArray("finalization")
                .getJSONObject(0).getString("sequenceReset"));
    }

    @Test
    void postgresqlSequenceResetLocksBeforeReadingStateAndKeepsTheHigherHighWaterMark()
            throws Exception {
        List<String> events = new ArrayList<>();
        Connection connection = postgresqlSequenceConnection(events, 150L, true, 99L, false);

        StagingManifestImporter.resetPostgresqlSequence(connection,
                new RecordingContext(125L, tempDirectory.resolve("postgres-sequence-order")),
                "\"app\".\"users\"", "app.users", "id", "\"id\"");

        int identityBeforeLock = indexOfContaining(events, "SELECT c.oid::text");
        int sequenceLock = events.indexOf(
                "UPDATE:ALTER SEQUENCE \"app\".\"users_id_seq\" OWNER TO \"app_owner\"");
        int identityAfterLock = lastIndexOfContaining(events, "SELECT c.oid::text");
        int ownershipRecheck = indexOfContaining(events,
                "SELECT CAST(pg_get_serial_sequence(?,?) AS regclass)::oid::text");
        int sequenceMetadata = indexOfContaining(events, "SELECT seqincrement,seqmax,seqcycle");
        int sequenceState = events.indexOf(
                "QUERY:SELECT last_value,is_called FROM \"app\".\"users_id_seq\"");
        int tableMaximum = events.indexOf(
                "QUERY:SELECT COALESCE(MAX(\"id\"),0) FROM \"app\".\"users\"");
        int restart = events.indexOf(
                "UPDATE:ALTER SEQUENCE \"app\".\"users_id_seq\" RESTART WITH 151");
        assertTrue(identityBeforeLock >= 0);
        assertTrue(identityBeforeLock < sequenceLock);
        assertTrue(sequenceLock < ownershipRecheck);
        assertTrue(sequenceLock < identityAfterLock);
        assertTrue(identityAfterLock < ownershipRecheck);
        assertTrue(ownershipRecheck < sequenceMetadata);
        assertTrue(sequenceMetadata < sequenceState);
        assertTrue(sequenceState < tableMaximum);
        assertTrue(tableMaximum < restart);
        assertTrue(events.stream().noneMatch(sql -> sql.toLowerCase().contains("setval")));
    }

    @Test
    void postgresqlSequenceResetRejectsCyclingSequenceWithoutRestartingIt() {
        List<String> events = new ArrayList<>();
        Connection connection = postgresqlSequenceConnection(events, 150L, true, 99L, true);

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.resetPostgresqlSequence(connection,
                        new RecordingContext(126L, tempDirectory.resolve("postgres-sequence-cycle")),
                        "\"app\".\"users\"", "app.users", "id", "\"id\""));

        assertTrue(failure.getMessage().contains("non-cycling PostgreSQL sequences"));
        assertTrue(events.contains(
                "UPDATE:ALTER SEQUENCE \"app\".\"users_id_seq\" OWNER TO \"app_owner\""));
        assertTrue(events.stream().noneMatch(sql -> sql.contains("RESTART WITH")));
    }

    @Test
    void postgresqlSequenceResetRejectsConcurrentCatalogChangeBeforeReadingState() {
        List<String> events = new ArrayList<>();
        Connection connection = postgresqlSequenceConnection(
                events, 150L, true, 99L, false, "100", "101");

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.resetPostgresqlSequence(connection,
                        new RecordingContext(128L, tempDirectory.resolve("postgres-sequence-catalog-race")),
                        "\"app\".\"users\"", "app.users", "id", "\"id\""));

        assertTrue(failure.getMessage().contains("metadata changed"));
        assertTrue(events.contains(
                "UPDATE:ALTER SEQUENCE \"app\".\"users_id_seq\" OWNER TO \"app_owner\""));
        assertTrue(events.stream().noneMatch(sql -> sql.contains("seqincrement")));
        assertTrue(events.stream().noneMatch(sql -> sql.contains("last_value")));
        assertTrue(events.stream().noneMatch(sql -> sql.contains("RESTART WITH")));
    }

    @Test
    void rehearsalImportsFivePercentThenRollsBackAndVerifiesOriginalCount() throws Exception {
        executeSql("CREATE TABLE AUDIT_LOG (ID BIGINT PRIMARY KEY, PAYLOAD VARCHAR(64) NOT NULL)");
        executeSql("INSERT INTO AUDIT_LOG (ID,PAYLOAD) VALUES (1000,'existing')");
        StringBuilder csv = new StringBuilder("ID,PAYLOAD\n");
        for (int row = 1; row <= 20; row++) {
            csv.append(row).append(",value-").append(row).append('\n');
        }
        Path auditCsv = source("audit", csv.toString());
        ImportTableSource audit = tableSource("AUDIT_LOG", auditCsv, null);
        List<ImportManifestShard> auditShards = shards(audit, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(102L, dagPlan(List.of(List.of(key("AUDIT_LOG")))),
                List.of(), auditShards);
        ImportTaskSpec spec = spec(List.of(audit));
        spec.setRollbackOptions(ImportRollbackOptions.builder().rehearsal(true).build());
        spec.setFinalizationOptions(ImportFinalizationOptions.builder()
                .resetSequences(true).rebuildIndexes(true).refreshStatistics(true).build());
        spec.setPerformanceSamplePercent(5);
        RecordingContext context = new RecordingContext(102L,
                tempDirectory.resolve("artifacts-rehearsal"));

        importer().execute(spec, context, manifest);

        assertEquals(1L, count("AUDIT_LOG"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM AUDIT_LOG WHERE ID=1000"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals(5, report.getIntValue("samplePercent"));
        assertEquals("REHEARSAL_ROLLED_BACK",
                report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
        assertFalse(report.containsKey("finalization"), "rehearsal must not execute post-commit maintenance");
        assertEquals(1L, report.getJSONArray("sourceProfiles").getJSONObject(0)
                .getLongValue("sourceRows"));
    }

    @Test
    void requestedChecksumWithoutPrimaryKeyFailsClosedAndRollsBack() throws Exception {
        executeSql("CREATE TABLE CHECKSUM_GUARD (ID BIGINT NOT NULL, NAME VARCHAR(64) NOT NULL)");
        Path csv = source("checksum-guard", "ID,NAME\n1,Alice\n");
        ImportTableSource rows = tableSource("CHECKSUM_GUARD", csv, null);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(110L,
                dagPlan(List.of(List.of(key("CHECKSUM_GUARD")))), List.of(), rowShards);
        RecordingContext context = new RecordingContext(110L,
                tempDirectory.resolve("artifacts-checksum-guard"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(rows)), context, manifest));

        assertTrue(failure.getMessage().contains("Requested content checksum requires mapped primary keys"));
        assertEquals(0L, count("CHECKSUM_GUARD"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_ROLLED_BACK", report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
    }

    @Test
    void skipWritesAnIndependentShardRejectAndTaskSummary() throws Exception {
        executeSql("CREATE TABLE IMPORT_ROWS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        ImportOptions options = ImportOptions.builder().onError("SKIP").maxErrors(1).build();
        Path rowsCsv = source("skip", "ID,NAME\nbad,Alice\n2,Bob\n");
        ImportTableSource rows = tableSource("IMPORT_ROWS", rowsCsv, options);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(103L, dagPlan(List.of(List.of(key("IMPORT_ROWS")))),
                List.of(), rowShards);
        RecordingContext context = new RecordingContext(103L, tempDirectory.resolve("artifacts-skip"));

        importer().execute(spec(List.of(rows)), context, manifest);

        assertEquals(1L, count("IMPORT_ROWS"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM IMPORT_ROWS WHERE ID=2"));
        List<Path> rejects = context.artifactsStartingWith(TaskArtifactRole.REJECT + ":");
        assertEquals(1, rejects.size());
        String rejectedRow = Files.readString(rejects.get(0), StandardCharsets.UTF_8);
        assertTrue(rejectedRow.contains("\"row\":1"));
        assertTrue(rejectedRow.contains("bad"));
        JSONObject summary = context.jsonArtifact(TaskArtifactRole.REJECT_SUMMARY);
        assertEquals(1L, summary.getLongValue("rejectedRows"));
        JSONArray shardOutcomes = summary.getJSONArray("shards");
        assertEquals(1, shardOutcomes.size());
        assertEquals(1L, shardOutcomes.getJSONObject(0).getLongValue("rejectedRows"));
    }

    @Test
    void skipRejectsTargetUniqueConstraintFailureAndPublishesRemainingRows() throws Exception {
        executeSql("CREATE TABLE PUBLISH_SKIP (ID BIGINT PRIMARY KEY, EMAIL VARCHAR(64) UNIQUE NOT NULL)");
        executeSql("INSERT INTO PUBLISH_SKIP (ID,EMAIL) VALUES (99,'existing@example.test')");
        ImportOptions options = ImportOptions.builder().onError("SKIP").maxErrors(1).build();
        ImportTableSource rows = tableSource("PUBLISH_SKIP", source("publish-skip",
                "ID,EMAIL\n1,existing@example.test\n2,new@example.test\n"), options);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(116L,
                dagPlan(List.of(List.of(key("PUBLISH_SKIP")))), List.of(), rowShards);
        RecordingContext context = new RecordingContext(116L,
                tempDirectory.resolve("artifacts-publish-skip"));

        importer().execute(spec(List.of(rows)), context, manifest);

        assertEquals(2L, count("PUBLISH_SKIP"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM PUBLISH_SKIP WHERE ID=1"));
        assertEquals(1L, scalar("SELECT COUNT(*) FROM PUBLISH_SKIP WHERE ID=2"));
        List<Path> rejects = context.artifactsStartingWith(TaskArtifactRole.REJECT + ":");
        assertEquals(1, rejects.size());
        String rejectedRow = Files.readString(rejects.get(0), StandardCharsets.UTF_8);
        assertTrue(rejectedRow.contains("existing@example.test"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals(1L, report.getLongValue("rejectedRows"));
        assertEquals(1L, report.getJSONArray("rowCounts").getJSONObject(0)
                .getLongValue("expectedDelta"));
        JSONObject summary = context.jsonArtifact(TaskArtifactRole.REJECT_SUMMARY);
        assertEquals(1L, summary.getLongValue("rejectedRows"));
        assertEquals(1L, summary.getJSONArray("shards").getJSONObject(0)
                .getLongValue("rejectedRows"));
    }

    @Test
    void targetPublishRejectsPastMaxErrorsRollBackAllPublishedRows() throws Exception {
        executeSql("CREATE TABLE PUBLISH_LIMIT (ID BIGINT PRIMARY KEY, EMAIL VARCHAR(64) UNIQUE NOT NULL)");
        executeSql("INSERT INTO PUBLISH_LIMIT (ID,EMAIL) VALUES "
                + "(98,'first@example.test'),(99,'second@example.test')");
        ImportOptions options = ImportOptions.builder().onError("SKIP").maxErrors(1).build();
        ImportTableSource rows = tableSource("PUBLISH_LIMIT", source("publish-limit",
                "ID,EMAIL\n1,first@example.test\n2,new@example.test\n3,second@example.test\n"), options);
        List<ImportManifestShard> rowShards = shards(rows, 0, Long.MAX_VALUE);
        ImportManifest manifest = manifest(117L,
                dagPlan(List.of(List.of(key("PUBLISH_LIMIT")))), List.of(), rowShards);
        RecordingContext context = new RecordingContext(117L,
                tempDirectory.resolve("artifacts-publish-limit"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(rows)), context, manifest));

        assertTrue(failure.getMessage().contains("task-wide rejected row limit of 1"));
        assertEquals(2L, count("PUBLISH_LIMIT"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM PUBLISH_LIMIT WHERE ID IN (1,2,3)"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_ROLLED_BACK", report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
        JSONObject summary = context.jsonArtifact(TaskArtifactRole.REJECT_SUMMARY);
        assertEquals(2L, summary.getLongValue("rejectedRows"));
        assertEquals(2L, summary.getJSONArray("shards").getJSONObject(0)
                .getLongValue("rejectedRows"));
    }

    @Test
    void rejectsAmbiguousUnnamedCompositeDependencyBeforeOpeningConstraintControls() {
        ImportTableDependency first = dependency("PARENT", "TENANT_ID", "CHILD", "TENANT_ID", null);
        ImportTableDependency second = dependency("PARENT", "ID", "CHILD", "PARENT_ID", null);
        second.setKeySequence((short) 2);
        ImportManifest manifest = ImportManifest.builder()
                .dependencies(List.of(first, second))
                .build();

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> StagingManifestImporter.validatePhysicalDependencyIdentity(manifest));

        assertTrue(failure.getMessage().contains("unnamed composite foreign key"));
        assertTrue(failure.getMessage().contains("refusing constraint changes"));
    }

    @Test
    void cyclicPhysicalDependenciesExcludeDagEdgesBetweenDifferentComponents() {
        ImportTableDependency aToB = dependency("SCC_A", "ID", "SCC_B", "A_ID", "A_TO_B");
        ImportTableDependency bToA = dependency("SCC_B", "ID", "SCC_A", "B_ID", "B_TO_A");
        ImportTableDependency cToD = dependency("SCC_C", "ID", "SCC_D", "C_ID", "C_TO_D");
        ImportTableDependency dToC = dependency("SCC_D", "ID", "SCC_C", "D_ID", "D_TO_C");
        ImportTableDependency bToC = dependency("SCC_B", "ID", "SCC_C", "B_ID", "B_TO_C");
        ImportTableDependency self = dependency("SCC_SELF", "ID", "SCC_SELF", "PARENT_ID", "SELF");
        ImportTableDependency unlistedSelf = dependency(
                "SCC_UNLISTED", "ID", "SCC_UNLISTED", "PARENT_ID", "UNLISTED_SELF");
        ImportDependencyPlan plan = ImportDependencyPlan.builder()
                .mode(ImportPlanMode.STAGING_FIRST)
                .layers(List.of(List.of(key("SCC_A"), key("SCC_B"), key("SCC_C"), key("SCC_D"))))
                .cyclicComponents(List.of(
                        List.of(key("SCC_A"), key("SCC_B")),
                        List.of(key("SCC_C"), key("SCC_D"))))
                .selfReferencingTables(List.of(key("SCC_SELF")))
                .shardKeys(Map.of())
                .stagingRequired(true)
                .cycleResolutionRequired(true)
                .build();
        ImportManifest manifest = ImportManifest.builder()
                .dependencyPlan(plan)
                .dependencies(List.of(aToB, bToA, cToD, dToC, bToC, self, unlistedSelf))
                .build();

        assertEquals(List.of(aToB, bToA, cToD, dToC, self),
                StagingManifestImporter.cyclicPhysicalDependencies(manifest));
    }

    @Test
    void enforcesMaxErrorsAcrossDifferentShardsAndRollsBack() throws Exception {
        executeSql("CREATE TABLE LIMITED_ROWS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        ImportOptions options = ImportOptions.builder().onError("SKIP").maxErrors(1).build();
        Path rowsCsv = source("limit", "ID,NAME\nbad-one,Alice\nbad-two,Bob\n3,Carol\n");
        ImportTableSource rows = tableSource("LIMITED_ROWS", rowsCsv, options);
        List<ImportManifestShard> rowShards = shards(rows, 0, 1L);
        assertEquals(3, rowShards.size());
        ImportManifest manifest = manifest(104L, dagPlan(List.of(List.of(key("LIMITED_ROWS")))),
                List.of(), rowShards);
        RecordingContext context = new RecordingContext(104L, tempDirectory.resolve("artifacts-limit"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(rows)), context, manifest));

        assertTrue(failure.getMessage().contains("task-wide rejected row limit of 1"));
        assertEquals(0L, count("LIMITED_ROWS"));
        assertEquals(2, context.artifactsStartingWith(TaskArtifactRole.REJECT + ":").size());
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rolledBack"));
        assertTrue(report.getJSONObject("failure").getString("reason")
                .contains("task-wide rejected row limit of 1"));
        JSONObject summary = context.jsonArtifact(TaskArtifactRole.REJECT_SUMMARY);
        assertEquals(2L, summary.getLongValue("rejectedRows"));
        JSONArray shardOutcomes = summary.getJSONArray("shards");
        assertEquals(3, shardOutcomes.size());
        JSONObject completed = shardOutcomes.getJSONObject(0);
        JSONObject failed = shardOutcomes.getJSONObject(1);
        JSONObject notAttempted = shardOutcomes.getJSONObject(2);
        assertEquals(rowShards.get(0).getShardId(), completed.getString("shardId"));
        assertEquals("COMPLETED", completed.getString("status"));
        assertEquals(1L, completed.getLongValue("rejectedRows"));
        assertEquals(TaskArtifactRole.rejectForShard(rowShards.get(0).getShardId()),
                completed.getString("rejectArtifactRole"));
        assertEquals(rowShards.get(1).getShardId(), failed.getString("shardId"));
        assertEquals("FAILED", failed.getString("status"));
        assertEquals(1L, failed.getLongValue("rejectedRows"));
        assertTrue(failed.getString("failure").contains("task-wide rejected row limit of 1"));
        assertEquals(TaskArtifactRole.rejectForShard(rowShards.get(1).getShardId()),
                failed.getString("rejectArtifactRole"));
        assertEquals(rowShards.get(2).getShardId(), notAttempted.getString("shardId"));
        assertEquals("NOT_ATTEMPTED", notAttempted.getString("status"));
        assertEquals(0L, notAttempted.getLongValue("sourceRows"));
        assertEquals(TaskArtifactRole.rejectForShard(rowShards.get(2).getShardId()),
                notAttempted.getString("rejectArtifactRole"));
    }

    @Test
    void orphanCheckFailsBeforePublishAndPreservesAllTargetCounts() throws Exception {
        executeSql("CREATE TABLE PARENTS (ID BIGINT PRIMARY KEY, NAME VARCHAR(64) NOT NULL)");
        executeSql("CREATE TABLE CHILDREN (ID BIGINT PRIMARY KEY, PARENT_ID BIGINT NOT NULL, "
                + "CONSTRAINT FK_CHILD_PARENT FOREIGN KEY (PARENT_ID) REFERENCES PARENTS(ID))");
        executeSql("INSERT INTO PARENTS (ID,NAME) VALUES (99,'existing')");
        executeSql("INSERT INTO CHILDREN (ID,PARENT_ID) VALUES (900,99)");
        Path parentsCsv = source("parents", "ID,NAME\n1,new-parent\n");
        Path childrenCsv = source("children", "ID,PARENT_ID\n10,999\n");
        ImportTableSource parents = tableSource("PARENTS", parentsCsv, null);
        ImportTableSource children = tableSource("CHILDREN", childrenCsv, null);
        List<ImportManifestShard> parentShards = shards(parents, 0, Long.MAX_VALUE);
        List<ImportManifestShard> childShards = shards(children, 1, Long.MAX_VALUE);
        List<String> parentShardIds = parentShards.stream().map(ImportManifestShard::getShardId).toList();
        childShards.forEach(shard -> shard.setDependencyShardIds(parentShardIds));
        ImportTableDependency dependency = dependency("PARENTS", "ID", "CHILDREN", "PARENT_ID",
                "FK_CHILD_PARENT");
        ImportManifest manifest = manifest(105L,
                dagPlan(List.of(List.of(key("PARENTS")), List.of(key("CHILDREN")))),
                List.of(dependency), parentShards, childShards);
        RecordingContext context = new RecordingContext(105L, tempDirectory.resolve("artifacts-orphan"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(parents, children)), context, manifest));

        assertTrue(failure.getMessage().contains("Staging orphan check failed"));
        assertEquals(1L, count("PARENTS"));
        assertEquals(1L, count("CHILDREN"));
        assertEquals(0L, scalar("SELECT COUNT(*) FROM PARENTS WHERE ID=1"));
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        assertEquals("FAILED_ROLLED_BACK",
                report.getJSONObject("transaction").getString("outcome"));
        assertTrue(report.getJSONObject("transaction").getBooleanValue("rollbackVerified"));
    }

    @Test
    void validatesPhysicalForeignKeysWhoseParentTableIsOutsideTheImportSet() throws Exception {
        executeSql("CREATE TABLE EXTERNAL_PARENT (ID BIGINT PRIMARY KEY)");
        executeSql("CREATE TABLE EXTERNAL_CHILD (ID BIGINT PRIMARY KEY, PARENT_ID BIGINT NOT NULL)");
        executeSql("INSERT INTO EXTERNAL_PARENT (ID) VALUES (1)");
        Path csv = source("external-child", "ID,PARENT_ID\n10,999\n");
        ImportTableSource child = tableSource("EXTERNAL_CHILD", csv,
                ImportOptions.builder().onError("ABORT").build());
        List<ImportManifestShard> childShards = shards(child, 0, 1024L);
        ImportTableDependency dependency = dependency("EXTERNAL_PARENT", "ID",
                "EXTERNAL_CHILD", "PARENT_ID", "FK_EXTERNAL_CHILD_PARENT");
        ImportManifest manifest = manifest(110L,
                dagPlan(List.of(List.of(key("EXTERNAL_CHILD")))), List.of(dependency), childShards);
        RecordingContext context = new RecordingContext(110L,
                tempDirectory.resolve("artifacts-external-parent"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> importer().execute(spec(List.of(child)), context, manifest));

        assertTrue(failure.getMessage().contains("Staging orphan check failed"));
        assertEquals(0L, count("EXTERNAL_CHILD"));
    }

    private StagingManifestImporter importer() {
        return new StagingManifestImporter(
                ignored -> DriverManager.getConnection(jdbcUrl, "sa", ""));
    }

    private ImportTaskSpec spec(List<ImportTableSource> sources) {
        return ImportTaskSpec.builder()
                .scope(ImportScope.SCHEMA)
                .format("CSV")
                .sourceKind("TRUSTED")
                .cycleStrategy("REJECT")
                .target(TaskTargetSnapshot.builder().databaseName(database).schemaName(SCHEMA).build())
                .tableSources(sources)
                .stagingPolicy(ImportStagingPolicy.builder()
                        .enabled(true).allVarchar(true).twoPhase(true).build())
                .validationOptions(ImportValidationOptions.builder()
                        .sourceProfiling(true).rowCount(true).checksum(true).orphanCheck(true).build())
                .build();
    }

    private ImportTableSource tableSource(String table, Path source, ImportOptions options) {
        return ImportTableSource.builder()
                .databaseName(database)
                .schemaName(SCHEMA)
                .tableName(table)
                .sourceFile(source.toString())
                .format("CSV")
                .options(options)
                .build();
    }

    private List<ImportManifestShard> shards(ImportTableSource source, int layer, long targetBytes)
            throws IOException {
        Path output = tempDirectory.resolve("shards-" + (++sourceSequence));
        return CsvShardPreprocessor.preprocess(Path.of(source.getSourceFile()).toFile(),
                StandardCharsets.UTF_8, CSVFormat.DEFAULT, output, source.getDatabaseName(), source.getSchemaName(),
                source.getTableName(), key(source.getTableName()), layer, targetBytes);
    }

    @SafeVarargs
    private final ImportManifest manifest(long taskId, ImportDependencyPlan plan,
            List<ImportTableDependency> dependencies, List<ImportManifestShard>... shardGroups) {
        List<ImportManifestShard> shards = new ArrayList<>();
        for (List<ImportManifestShard> group : shardGroups) {
            shards.addAll(group);
        }
        return ImportManifestBuilder.build(taskId, "STAGING_REQUIRED", "SHA-256:fixture-" + taskId,
                plan, dependencies, shards);
    }

    private ImportDependencyPlan dagPlan(List<List<String>> layers) {
        return ImportDependencyPlan.builder()
                .mode(ImportPlanMode.STAGING_FIRST)
                .layers(layers)
                .cyclicComponents(List.of())
                .selfReferencingTables(List.of())
                .shardKeys(Map.of())
                .stagingRequired(true)
                .cycleResolutionRequired(false)
                .build();
    }

    private ImportTableDependency dependency(String parentTable, String parentColumn,
            String childTable, String childColumn, String constraint) {
        return ImportTableDependency.builder()
                .parentSchemaName(SCHEMA)
                .parentTable(parentTable)
                .parentColumn(parentColumn)
                .parentTableKey(key(parentTable))
                .childSchemaName(SCHEMA)
                .childTable(childTable)
                .childColumn(childColumn)
                .childTableKey(key(childTable))
                .constraintName(constraint)
                .keySequence((short) 1)
                .deferrability((short) DatabaseMetaData.importedKeyNotDeferrable)
                .logical(false)
                .build();
    }

    private Path source(String name, String contents) throws IOException {
        return Files.writeString(tempDirectory.resolve(name + "-" + sourceSequence + ".csv"), contents,
                StandardCharsets.UTF_8);
    }

    private String key(String table) {
        return database + "." + SCHEMA + "." + table;
    }

    private static IDbMetaData mysqlMetadata() {
        return (IDbMetaData) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{IDbMetaData.class}, (proxy, method, arguments) -> {
                    if ("getQualifiedTableName".equals(method.getName())) {
                        assertEquals(null, arguments[1],
                                "MySQL schemas must be resolved as the database, not emitted as a third qualifier");
                        return "`" + arguments[0] + "`.`" + arguments[2] + "`";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Connection mysqlSafetyConnection(List<String> events,
            Map<String, String> engines, String failingLock, boolean failEngineQuery) {
        return (Connection) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        events.add("AUTOCOMMIT:" + arguments[0]);
                        return null;
                    }
                    if ("createStatement".equals(method.getName())) {
                        return mysqlLockStatement(events, failingLock);
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        assertEquals("SELECT ENGINE FROM information_schema.TABLES "
                                + "WHERE CAST(TABLE_SCHEMA AS BINARY)=CAST(? AS BINARY) "
                                + "AND CAST(TABLE_NAME AS BINARY)=CAST(? AS BINARY)", arguments[0]);
                        return mysqlEngineStatement(events, engines, failEngineQuery);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Statement mysqlLockStatement(List<String> events, String failingLock) {
        return (Statement) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, arguments) -> {
                    if ("executeQuery".equals(method.getName())) {
                        String sql = (String) arguments[0];
                        events.add("QUERY:" + sql);
                        if (sql.equals(failingLock)) {
                            throw new SQLException("simulated metadata lock failure");
                        }
                        return resultRows();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement mysqlEngineStatement(List<String> events,
            Map<String, String> engines, boolean failEngineQuery) {
        Map<Integer, String> parameters = new LinkedHashMap<>();
        return (PreparedStatement) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if ("setString".equals(method.getName())) {
                        parameters.put((Integer) arguments[0], (String) arguments[1]);
                        return null;
                    }
                    if ("executeQuery".equals(method.getName())) {
                        String target = parameters.get(1) + "." + parameters.get(2);
                        events.add("ENGINE:" + target);
                        if (failEngineQuery) {
                            throw new SQLException("simulated engine query failure");
                        }
                        return engines.containsKey(target)
                                ? resultRows(new Object[]{engines.get(target)}) : resultRows();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Connection finalizationConnection(List<String> events, boolean failRollback) {
        return (Connection) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("rollback".equals(method.getName())) {
                        events.add("ROLLBACK");
                        if (failRollback) {
                            throw new SQLException("simulated finalization rollback failed");
                        }
                        return null;
                    }
                    if ("commit".equals(method.getName())) {
                        events.add("COMMIT");
                        return null;
                    }
                    if ("setAutoCommit".equals(method.getName())) {
                        events.add("AUTOCOMMIT:" + arguments[0]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Connection postgresqlSequenceConnection(List<String> events,
            long lastValue, boolean called, long tableMaximum, boolean cycle) {
        return postgresqlSequenceConnection(events, lastValue, called, tableMaximum, cycle,
                "100", "100");
    }

    private static Connection postgresqlSequenceConnection(List<String> events,
            long lastValue, boolean called, long tableMaximum, boolean cycle,
            String catalogVersionBeforeLock, String catalogVersionAfterLock) {
        AtomicInteger identityReads = new AtomicInteger();
        return (Connection) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("createStatement".equals(method.getName())) {
                        return postgresqlSequenceStatement(events, lastValue, called, tableMaximum);
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return postgresqlSequencePreparedStatement(events, (String) arguments[0], cycle,
                                identityReads, catalogVersionBeforeLock, catalogVersionAfterLock);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Statement postgresqlSequenceStatement(List<String> events,
            long lastValue, boolean called, long tableMaximum) {
        return (Statement) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, arguments) -> {
                    if ("executeUpdate".equals(method.getName())) {
                        events.add("UPDATE:" + arguments[0]);
                        return 1;
                    }
                    if ("executeQuery".equals(method.getName())) {
                        String sql = (String) arguments[0];
                        events.add("QUERY:" + sql);
                        if (sql.startsWith("SELECT last_value,is_called")) {
                            return resultRows(new Object[]{lastValue, called});
                        }
                        if (sql.startsWith("SELECT COALESCE(MAX(")) {
                            return resultRows(new Object[]{tableMaximum});
                        }
                        throw new SQLException("Unexpected PostgreSQL sequence query: " + sql);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement postgresqlSequencePreparedStatement(
            List<String> events, String sql, boolean cycle, AtomicInteger identityReads,
            String catalogVersionBeforeLock, String catalogVersionAfterLock) {
        return (PreparedStatement) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if ("executeQuery".equals(method.getName())) {
                        events.add("PREPARED:" + sql);
                        if (sql.startsWith("SELECT pg_get_serial_sequence")) {
                            return resultRows(new Object[]{"app.users_id_seq"});
                        }
                        if (sql.startsWith("SELECT c.oid::text")) {
                            String catalogVersion = identityReads.getAndIncrement() == 0
                                    ? catalogVersionBeforeLock : catalogVersionAfterLock;
                            return resultRows(new Object[]{"16384", "\"app\".\"users_id_seq\"",
                                    "\"app_owner\"", catalogVersion});
                        }
                        if (sql.startsWith("SELECT CAST(pg_get_serial_sequence")) {
                            return resultRows(new Object[]{"16384"});
                        }
                        if (sql.startsWith("SELECT seqincrement,seqmax,seqcycle")) {
                            return resultRows(new Object[]{1L, 1_000L, cycle});
                        }
                        throw new SQLException("Unexpected PostgreSQL sequence metadata query: " + sql);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet resultRows(Object[]... values) {
        AtomicInteger row = new AtomicInteger(-1);
        AtomicBoolean wasNull = new AtomicBoolean();
        return (ResultSet) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                    if ("next".equals(method.getName())) {
                        return row.incrementAndGet() < values.length;
                    }
                    if ("wasNull".equals(method.getName())) {
                        return wasNull.get();
                    }
                    if (method.getName().startsWith("get") && arguments != null
                            && arguments.length == 1 && arguments[0] instanceof Integer column) {
                        Object value = values[row.get()][column - 1];
                        wasNull.set(value == null);
                        return switch (method.getName()) {
                            case "getString" -> value == null ? null : value.toString();
                            case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                            case "getBoolean" -> value != null && (Boolean) value;
                            default -> value;
                        };
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static int indexOfContaining(List<String> values, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(expected)) {
                return index;
            }
        }
        return -1;
    }

    private static int lastIndexOfContaining(List<String> values, String expected) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (values.get(index).contains(expected)) {
                return index;
            }
        }
        return -1;
    }

    private static Connection recordingStatements(Connection delegate, List<String> statements) {
        return (Connection) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invokeDelegate(delegate, method, arguments);
                    if (!"createStatement".equals(method.getName()) || !(result instanceof Statement statement)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(
                            StagingManifestImporterTest.class.getClassLoader(),
                            new Class<?>[]{Statement.class},
                            (statementProxy, statementMethod, statementArguments) -> {
                                if (statementMethod.getName().startsWith("execute")
                                        && statementArguments != null && statementArguments.length > 0
                                        && statementArguments[0] instanceof String sql) {
                                    statements.add(sql);
                                }
                                return invokeDelegate(statement, statementMethod, statementArguments);
                            });
                });
    }

    private static Connection withoutTransactionSupport(Connection delegate, AtomicBoolean closed)
            throws SQLException {
        DatabaseMetaData metadata = delegate.getMetaData();
        DatabaseMetaData wrappedMetadata = (DatabaseMetaData) Proxy.newProxyInstance(
                StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, arguments) -> "supportsTransactions".equals(method.getName())
                        ? false : invokeDelegate(metadata, method, arguments));
        return (Connection) Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return wrappedMetadata;
                    }
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                    }
                    return invokeDelegate(delegate, method, arguments);
                });
    }

    private static Connection recordingQueries(Connection delegate, List<String> queries) {
        return (Connection) Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object result = invokeDelegate(delegate, method, arguments);
                    if (!"createStatement".equals(method.getName()) || !(result instanceof Statement statement)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArguments) -> {
                                if ("executeQuery".equals(statementMethod.getName())
                                        && statementArguments != null && statementArguments.length == 1
                                        && statementArguments[0] instanceof String sql) {
                                    queries.add(sql);
                                }
                                return invokeDelegate(statement, statementMethod, statementArguments);
                            });
                });
    }

    private static Connection failingAutoCommitRestore(Connection delegate, AtomicBoolean closed) {
        AtomicBoolean transactionStarted = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        boolean value = (boolean) arguments[0];
                        if (!value) {
                            transactionStarted.set(true);
                        } else if (transactionStarted.get()) {
                            throw new SQLException("simulated session restore failure");
                        }
                    }
                    if ("close".equals(method.getName())) {
                        closed.set(true);
                    }
                    return invokeDelegate(delegate, method, arguments);
                });
    }

    private static Connection commitFailsAfterSuccess(Connection delegate, int failingCommit,
            CommitProbe probe, Throwable commitFailure) {
        return (Connection) Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName())) {
                        int commit = probe.commitCalls.incrementAndGet();
                        Object result = invokeDelegate(delegate, method, arguments);
                        if (commit == failingCommit) {
                            throw commitFailure;
                        }
                        return result;
                    }
                    if ("rollback".equals(method.getName())) {
                        probe.rollbackCalls.incrementAndGet();
                    } else if ("setAutoCommit".equals(method.getName())
                            && Boolean.TRUE.equals(arguments[0])) {
                        probe.autoCommitRestoreCalls.incrementAndGet();
                    } else if ("close".equals(method.getName())) {
                        probe.closed.set(true);
                    }
                    return invokeDelegate(delegate, method, arguments);
                });
    }

    private static void assertCommitUnknown(RecordingContext context, String phase,
            boolean targetCommitted) throws IOException {
        JSONObject report = context.jsonArtifact(TaskArtifactRole.IMPORT_REPORT);
        JSONObject transaction = report.getJSONObject("transaction");
        JSONObject failure = report.getJSONObject("failure");
        assertEquals("COMMIT_UNKNOWN", transaction.getString("outcome"));
        assertFalse(transaction.getBooleanValue("rolledBack"));
        assertTrue(transaction.getBooleanValue("manualReconciliationRequired"));
        assertTrue(failure.getBooleanValue("commitOutcomeUnknown"));
        assertTrue(failure.getBooleanValue("manualReconciliationRequired"));
        assertEquals(phase, failure.getString("commitPhase"));
        assertEquals(targetCommitted, failure.getBooleanValue("targetCommitted"));
    }

    private static final class CommitProbe {
        private final AtomicInteger commitCalls = new AtomicInteger();
        private final AtomicInteger rollbackCalls = new AtomicInteger();
        private final AtomicInteger autoCommitRestoreCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
    }

    private static Object invokeDelegate(Object delegate, java.lang.reflect.Method method,
            Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static <T> T warningSource(Class<T> type, SQLWarning warning, AtomicBoolean cleared) {
        return type.cast(Proxy.newProxyInstance(StagingManifestImporterTest.class.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, arguments) -> {
                    if ("getWarnings".equals(method.getName())) {
                        return warning;
                    }
                    if ("clearWarnings".equals(method.getName())) {
                        cleared.set(true);
                    }
                    return defaultValue(method.getReturnType());
                }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        if (type == double.class) {
            return 0.0D;
        }
        return null;
    }

    private void executeSql(String sql) throws SQLException {
        try (Statement statement = keeper.createStatement()) {
            statement.execute(sql);
        }
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

    private static final class RecordingContext implements TaskExecutionContext {

        private final Long taskId;
        private final Path artifactDirectory;
        private final AtomicInteger artifactSequence = new AtomicInteger();
        private final Map<String, List<Path>> artifacts = new LinkedHashMap<>();

        private RecordingContext(Long taskId, Path artifactDirectory) throws IOException {
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

        private JSONObject jsonArtifact(String role) throws IOException {
            List<Path> paths = artifacts.get(role);
            assertFalse(paths == null || paths.isEmpty(), "Missing artifact role " + role);
            return JSON.parseObject(Files.readString(paths.get(paths.size() - 1), StandardCharsets.UTF_8));
        }

        private List<Path> artifactsStartingWith(String rolePrefix) {
            return artifacts.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(rolePrefix))
                    .flatMap(entry -> entry.getValue().stream())
                    .toList();
        }
    }
}
