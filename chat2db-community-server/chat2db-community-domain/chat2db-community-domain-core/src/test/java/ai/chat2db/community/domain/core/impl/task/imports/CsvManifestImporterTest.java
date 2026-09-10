package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvManifestImporterTest {

    @Test
    void inheritsAdmissionParallelismUnlessManifestOverrideIsExplicit() {
        String previousAdmission = System.getProperty("chat2db.task.import.parallelism");
        String previousManifest = System.getProperty("chat2db.task.import.manifest-parallelism");
        try {
            System.setProperty("chat2db.task.import.parallelism", "4");
            System.clearProperty("chat2db.task.import.manifest-parallelism");
            assertEquals(4, CsvManifestImporter.effectiveParallelism(20));
            assertEquals(1, CsvManifestImporter.effectiveParallelism(1));

            System.setProperty("chat2db.task.import.manifest-parallelism", "2");
            assertEquals(2, CsvManifestImporter.effectiveParallelism(20));
        } finally {
            restore("chat2db.task.import.parallelism", previousAdmission);
            restore("chat2db.task.import.manifest-parallelism", previousManifest);
        }
    }

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void shardUsesNormalizedSerialSpecAndCommitsOnce(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id,name\n1,Alice\n");
        JdbcProbe jdbc = new JdbcProbe();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger legacyCheckpoints = new AtomicInteger();
        AtomicReference<Map<String, Object>> eventDetails = new AtomicReference<>();
        TaskExecutionContext context = context(legacyCheckpoints, eventDetails);
        ImportManifest manifest = manifest(source);
        ImportManifestShard shard = manifest.getShards().get(0);
        IImportStrategy strategy = (spec, shardContext) -> {
            assertEquals(TaskExecutionMode.STANDARD, spec.getMode());
            assertEquals("orders", spec.getTarget().getTableName());
            assertEquals("UTF-8", spec.getOptions().getCharset());
            assertEquals(",", spec.getOptions().getDelimiter());
            assertEquals(0, spec.getOptions().getSkipRows());
            assertTrue(shardContext.resumeStates().isEmpty());
            shardContext.checkpoint(ResumeState.builder().shardNo(0).kind("IMPORT_WATERMARK").build());
            shardContext.logInfo("SHARD_TEST", "executed", Map.of("rows", 1));
        };
        CsvManifestImporter importer = importer(strategy, jdbc, releases);

        ImportManifestScheduler.ShardResult result = importer.executeShard(spec(), context,
                connectInfo(), manifest, shard);

        assertEquals(1L, result.rows());
        assertEquals(Files.size(source), result.bytes());
        assertEquals(1, jdbc.commits.get());
        assertEquals(0, jdbc.rollbacks.get());
        assertTrue(jdbc.autoCommit.get());
        assertEquals(1, releases.get());
        assertEquals(0, legacyCheckpoints.get());
        assertEquals("orders-0", eventDetails.get().get("shardId"));
        assertEquals("orders", eventDetails.get().get("table"));
        assertEquals(1, eventDetails.get().get("rows"));
        assertEquals(null, Chat2DBContext.getConnectInfo());
    }

    @Test
    void entersTheParentCommitPhaseBeforeTheShardCommit(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-commit-phase.csv"), "id\n1\n");
        AtomicInteger commitPhaseEntries = new AtomicInteger();
        TaskExecutionContext context = (TaskExecutionContext) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("taskId".equals(method.getName())) {
                        return 42L;
                    }
                    if ("enterCommitPhase".equals(method.getName())) {
                        commitPhaseEntries.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });
        JdbcProbe jdbc = new JdbcProbe();
        jdbc.beforeCommit = () -> assertEquals(1, commitPhaseEntries.get());
        ImportManifest manifest = manifest(source);

        importer((ignored, shardContext) -> { }, jdbc, new AtomicInteger()).executeShard(spec(), context,
                connectInfo(), manifest, manifest.getShards().get(0));

        assertEquals(1, commitPhaseEntries.get());
        assertEquals(1, jdbc.commits.get());
    }

    @Test
    void multiTableShardUsesItsOwnQualifiedTargetMappingAndConnection(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("items-0.csv"), "source_id\n10\n");
        ImportManifest manifest = manifest(source);
        ImportManifestShard shard = manifest.getShards().get(0);
        shard.setDatabaseName("app");
        shard.setSchemaName("tenant");
        shard.setTableName("order_items");
        shard.setTableKey("app.tenant.order_items");
        ImportTaskSpec spec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA).format("CSV")
                .mode(TaskExecutionMode.STANDARD)
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("tenant").build())
                .tableSources(List.of(
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("orders").sourceFile("orders.csv").format("CSV").build(),
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("order_items").sourceFile("items.csv").format("CSV")
                                .columnMappings(List.of(ImportColumnMapping.builder()
                                        .sourceColumn("source_id").targetColumn("id").build()))
                                .options(ImportOptions.builder().onError("ABORT").build()).build()))
                .build();
        JdbcProbe jdbc = new JdbcProbe();
        AtomicReference<ConnectInfo> opened = new AtomicReference<>();
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(unusedStorage),
                (shardSpec, context) -> {
                    assertEquals("app", shardSpec.getTarget().getDatabaseName());
                    assertEquals("tenant", shardSpec.getTarget().getSchemaName());
                    assertEquals("order_items", shardSpec.getTarget().getTableName());
                    assertEquals("source_id", shardSpec.getColumnMappings().get(0).getSourceColumn());
                    assertEquals("id", shardSpec.getColumnMappings().get(0).getTargetColumn());
                }, connectInfo -> {
                    opened.set(connectInfo);
                    return jdbc.connection;
                }, ignored -> { });

        importer.executeShard(spec, context(new AtomicInteger(), new AtomicReference<>()),
                connectInfo(), manifest, shard);

        assertEquals("app", opened.get().getDatabaseName());
        assertEquals("tenant", opened.get().getSchemaName());
        assertEquals(1, jdbc.commits.get());
    }

    @Test
    void schemaV2CaseDistinctShardsUseTheirOwnMappingsAndOptions(@TempDir Path directory) throws Exception {
        Path upperSource = Files.writeString(directory.resolve("quoted-parent.csv"), "upper_id\n1\n");
        Path lowerSource = Files.writeString(directory.resolve("quoted-child.csv"), "lower_id\n2\n");
        ImportTaskSpec taskSpec = caseDistinctSpec(upperSource, lowerSource);
        ImportManifest upperManifest = tableManifest(upperSource, "Users", "app.tenant.Users", 2);
        ImportManifest lowerManifest = tableManifest(lowerSource, "users", "app.tenant.users", 2);
        JdbcProbe jdbc = new JdbcProbe();
        AtomicInteger releases = new AtomicInteger();
        Map<String, ImportTaskSpec> observed = new LinkedHashMap<>();
        CsvManifestImporter importer = importer((shardSpec, ignored) ->
                observed.put(shardSpec.getTarget().getTableName(), shardSpec), jdbc, releases);

        importer.executeShard(taskSpec, context(new AtomicInteger(), new AtomicReference<>()),
                connectInfo(), upperManifest, upperManifest.getShards().get(0));
        importer.executeShard(taskSpec, context(new AtomicInteger(), new AtomicReference<>()),
                connectInfo(), lowerManifest, lowerManifest.getShards().get(0));

        assertEquals("upper_id", observed.get("Users").getColumnMappings().get(0).getSourceColumn());
        assertEquals("UPPER_ID", observed.get("Users").getColumnMappings().get(0).getTargetColumn());
        assertEquals("ABORT", observed.get("Users").getOptions().getOnError());
        assertEquals(3, observed.get("Users").getOptions().getMaxErrors());
        assertEquals("lower_id", observed.get("users").getColumnMappings().get(0).getSourceColumn());
        assertEquals("lower_id", observed.get("users").getColumnMappings().get(0).getTargetColumn());
        assertEquals("SKIP", observed.get("users").getOptions().getOnError());
        assertEquals(7, observed.get("users").getOptions().getMaxErrors());
        assertEquals(2, jdbc.commits.get());
        assertEquals(2, releases.get());
    }

    @Test
    void schemaV2RejectsMissingAndMismatchedTableKeysBeforeOpeningJdbc(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("quoted-invalid.csv"), "upper_id\n1\n");
        ImportTaskSpec taskSpec = caseDistinctSpec(source, source);
        ImportManifest missing = tableManifest(source, "Users", null, 2);
        ImportManifest mismatched = tableManifest(source, "users", "app.tenant.Users", 2);
        AtomicInteger opens = new AtomicInteger();
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(unusedStorage),
                (ignored, context) -> { }, ignored -> {
                    opens.incrementAndGet();
                    return new JdbcProbe().connection;
                }, ignored -> { });

        for (ImportManifest manifest : List.of(missing, mismatched)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> importer.executeShard(taskSpec,
                            context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                            manifest.getShards().get(0)));
            assertTrue(failure.getMessage().startsWith("Schema v2 manifest shard"));
        }

        assertEquals(0, opens.get());
    }

    @Test
    void legacyShardPrefersAnExactCaseMatch(@TempDir Path directory) throws Exception {
        Path upperSource = Files.writeString(directory.resolve("legacy-Users.csv"), "upper_id\n1\n");
        Path lowerSource = Files.writeString(directory.resolve("legacy-users.csv"), "lower_id\n2\n");
        ImportTaskSpec taskSpec = caseDistinctSpec(upperSource, lowerSource);
        ImportManifest manifest = tableManifest(lowerSource, "users", null, 1);
        AtomicReference<ImportTaskSpec> observed = new AtomicReference<>();

        importer((shardSpec, ignored) -> observed.set(shardSpec), new JdbcProbe(), new AtomicInteger())
                .executeShard(taskSpec, context(new AtomicInteger(), new AtomicReference<>()),
                        connectInfo(), manifest, manifest.getShards().get(0));

        assertEquals("lower_id", observed.get().getColumnMappings().get(0).getSourceColumn());
        assertEquals("SKIP", observed.get().getOptions().getOnError());
        assertEquals(7, observed.get().getOptions().getMaxErrors());
    }

    @Test
    void legacyShardRejectsAnAmbiguousCaseInsensitiveFallback(@TempDir Path directory) throws Exception {
        Path upperSource = Files.writeString(directory.resolve("legacy-ambiguous.csv"), "id\n1\n");
        ImportTaskSpec taskSpec = caseDistinctSpec(upperSource, upperSource);
        ImportManifest manifest = tableManifest(upperSource, "USERS", null, 1);
        AtomicInteger opens = new AtomicInteger();
        JdbcProbe jdbc = new JdbcProbe();
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(unusedStorage),
                (ignored, context) -> { }, ignored -> {
                    opens.incrementAndGet();
                    return jdbc.connection;
                }, ignored -> { });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> importer.executeShard(taskSpec,
                        context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                        manifest.getShards().get(0)));

        assertTrue(failure.getMessage().contains("ambiguous"));
        assertEquals(0, opens.get());
    }

    @Test
    void legacyShardAllowsAUniqueCaseInsensitiveFallback(@TempDir Path directory) throws Exception {
        Path ordersSource = Files.writeString(directory.resolve("legacy-orders.csv"), "order_id\n1\n");
        ImportTaskSpec taskSpec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA).format("CSV")
                .mode(TaskExecutionMode.ULTRA_FAST).sourceKind("TRUSTED").cycleStrategy("REJECT")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("tenant").build())
                .tableSources(List.of(
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("Orders").sourceFile(ordersSource.toString()).format("CSV")
                                .columnMappings(List.of(ImportColumnMapping.builder()
                                        .sourceColumn("order_id").targetColumn("ORDER_ID").build()))
                                .options(ImportOptions.builder().onError("ABORT").maxErrors(5).build()).build(),
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("Audit").sourceFile("audit.csv").format("CSV").build()))
                .build();
        ImportManifest manifest = tableManifest(ordersSource, "orders", null, 1);
        AtomicReference<ImportTaskSpec> observed = new AtomicReference<>();

        importer((shardSpec, ignored) -> observed.set(shardSpec), new JdbcProbe(), new AtomicInteger())
                .executeShard(taskSpec, context(new AtomicInteger(), new AtomicReference<>()),
                        connectInfo(), manifest, manifest.getShards().get(0));

        assertEquals("order_id", observed.get().getColumnMappings().get(0).getSourceColumn());
        assertEquals("ORDER_ID", observed.get().getColumnMappings().get(0).getTargetColumn());
        assertEquals("ABORT", observed.get().getOptions().getOnError());
        assertEquals(5, observed.get().getOptions().getMaxErrors());
    }

    @Test
    void shardFailureRollsBackAndReleasesConnection(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        JdbcProbe jdbc = new JdbcProbe();
        AtomicInteger releases = new AtomicInteger();
        ImportManifest manifest = manifest(source);
        CsvManifestImporter importer = importer((spec, context) -> {
            throw new IllegalStateException("write failed", new SQLException("deadlock", "40001", 1213));
        }, jdbc, releases);

        assertThrows(IllegalStateException.class, () -> importer.executeShard(spec(),
                context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                manifest.getShards().get(0)));

        assertEquals(0, jdbc.commits.get());
        assertEquals(1, jdbc.rollbacks.get());
        assertTrue(jdbc.autoCommit.get());
        assertEquals(1, releases.get());
        assertEquals(null, Chat2DBContext.getConnectInfo());
    }

    @Test
    void commitFailureIsOutcomeUnknownAndDiscardsConnectionWithoutRetryableRollback(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("orders-commit-unknown.csv"), "id\n1\n");
        JdbcProbe jdbc = new JdbcProbe();
        jdbc.commitFailure = new SQLException("commit deadlock", "40P01");
        AtomicInteger releases = new AtomicInteger();
        ImportManifest manifest = manifest(source);

        ImportManifestScheduler.CommitOutcomeUnknownException failure = assertThrows(
                ImportManifestScheduler.CommitOutcomeUnknownException.class,
                () -> importer((spec, context) -> { }, jdbc, releases).executeShard(spec(),
                        context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                        manifest.getShards().get(0)));

        assertTrue(failure.getMessage().contains("verify target data before retrying"));
        assertEquals(1, jdbc.commits.get());
        assertEquals(0, jdbc.rollbacks.get());
        assertEquals(0, releases.get());
        assertTrue(jdbc.closed.get());
        assertFalse(ImportShardRetryPolicy.isDeadlock(failure));
    }

    @Test
    void skipSummaryPreservesCommitUnknownOutcome(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-commit-unknown.csv"), "id\n1\n");
        Path summary = directory.resolve("reject-summary.json");
        JdbcProbe jdbc = new JdbcProbe();
        jdbc.commitFailure = new SQLException("commit outcome unknown", "08007");
        TaskStorage storage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "listResumeStates" -> List.of();
                    case "compareAndSetResumeState" -> true;
                    default -> defaultValue(method.getReturnType());
                });
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(storage),
                (ignored, context) -> { }, ignored -> jdbc.connection, ignored -> { });
        ImportTaskSpec spec = spec();
        spec.getOptions().setOnError("SKIP");
        ImportManifest manifest = manifest(source);
        TaskExecutionContext context = context(new AtomicInteger(), new AtomicReference<>(),
                new AtomicReference<>(), summary);
        Chat2DBContext.putContext(connectInfo());

        assertThrows(ImportManifestScheduler.CommitOutcomeUnknownException.class,
                () -> importer.execute(spec, context, manifest));

        JSONObject report = JSON.parseObject(Files.readString(summary));
        assertEquals("COMMIT_UNKNOWN", report.getJSONArray("shards")
                .getJSONObject(0).getString("status"));
        assertEquals(0, jdbc.rollbacks.get());
        assertTrue(jdbc.closed.get());
    }

    @Test
    void committedShardWarnsAndDiscardsConnectionWhenStateRestoreFails(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("orders-restore-failure.csv"), "id\n1\n");
        JdbcProbe jdbc = new JdbcProbe();
        jdbc.autoCommitRestoreFailure = new SQLException("restore failed", "08006");
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<String> eventCode = new AtomicReference<>();
        TaskExecutionContext context = context(new AtomicInteger(), new AtomicReference<>(), eventCode);
        ImportManifest manifest = manifest(source);

        importer((ignored, shardContext) -> { }, jdbc, releases).executeShard(spec(), context,
                connectInfo(), manifest, manifest.getShards().get(0));

        assertEquals(1, jdbc.commits.get());
        assertEquals(0, jdbc.rollbacks.get());
        assertEquals(0, releases.get());
        assertTrue(jdbc.closed.get());
        assertEquals("IMPORT_CONNECTION_DISCARDED", eventCode.get());
    }

    @Test
    void sharedLayerCancellationIsCheckedInsideTheImporterAndBeforeCommit(@TempDir Path directory)
            throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        ImportManifest manifest = manifest(source);
        JdbcProbe jdbc = new JdbcProbe();
        AtomicInteger cancellationChecks = new AtomicInteger();
        IImportStrategy strategy = (spec, shardContext) -> shardContext.checkCancelled();
        Runnable cancellationCheck = () -> {
            if (cancellationChecks.incrementAndGet() == 2) {
                throw new CancellationException("peer shard failed");
            }
        };

        assertThrows(CancellationException.class, () -> importer(strategy, jdbc, new AtomicInteger())
                .executeShard(spec(), context(new AtomicInteger(), new AtomicReference<>()),
                        connectInfo(), manifest, manifest.getShards().get(0), cancellationCheck));

        assertEquals(2, cancellationChecks.get());
        assertEquals(0, jdbc.commits.get());
        assertEquals(1, jdbc.rollbacks.get());
    }

    @Test
    void changedShardIsRejectedBeforeOpeningAConnection(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        ImportManifest manifest = manifest(source);
        Files.writeString(source, "id\n2\n");
        JdbcProbe jdbc = new JdbcProbe();
        AtomicInteger opens = new AtomicInteger();
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(unusedStorage),
                (spec, context) -> { }, ignored -> { opens.incrementAndGet(); return jdbc.connection; },
                ignored -> { });

        assertThrows(IllegalStateException.class, () -> importer.executeShard(spec(),
                context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                manifest.getShards().get(0)));
        assertEquals(0, opens.get());
    }

    @Test
    void connectionAcquisitionFailureDoesNotReleaseAnUnopenedConnection(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        ImportManifest manifest = manifest(source);
        AtomicInteger releases = new AtomicInteger();
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(unusedStorage),
                (spec, context) -> { }, ignored -> { throw new SQLException("connection unavailable"); },
                ignored -> releases.incrementAndGet());

        assertThrows(SQLException.class, () -> importer.executeShard(spec(),
                context(new AtomicInteger(), new AtomicReference<>()), connectInfo(), manifest,
                manifest.getShards().get(0)));

        assertEquals(0, releases.get());
        assertEquals(null, Chat2DBContext.getConnectInfo());
    }

    @Test
    void skipModeUsesAShardScopedRejectArtifact(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        ImportManifest manifest = manifest(source);
        JdbcProbe jdbc = new JdbcProbe();
        AtomicReference<String> artifactRole = new AtomicReference<>();
        AtomicReference<Map<String, Object>> eventDetails = new AtomicReference<>();
        TaskExecutionContext context = context(new AtomicInteger(), eventDetails, artifactRole,
                directory.resolve("reject.ndjson"));
        IImportStrategy strategy = (spec, shardContext) -> {
            shardContext.createArtifact(TaskArtifactRole.REJECT, directory.toString(),
                    "orders.rejects.ndjson", "application/x-ndjson");
            shardContext.logWarn("IMPORT_ROW_REJECTED", "bad row", Map.of("rejectedRows", 1));
            shardContext.logInfo("IMPORT_SUMMARY", "done",
                    Map.of("importedRows", 0L, "rejectedRows", 1L));
        };
        ImportTaskSpec spec = spec();
        spec.getOptions().setOnError("SKIP");
        spec.getOptions().setMaxErrors(2);

        importer(strategy, jdbc, new AtomicInteger())
                .executeShard(spec, context, connectInfo(), manifest, manifest.getShards().get(0));

        assertEquals(TaskArtifactRole.rejectForShard("orders-0"), artifactRole.get());
        assertEquals("orders-0", eventDetails.get().get("shardId"));
        assertEquals(1, jdbc.commits.get());
    }

    @Test
    void skipModeEnforcesTaskWideRejectLimitInsideTheShardTransaction(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("orders-0.csv"), "id\n1\n");
        ImportManifest manifest = manifest(source);
        JdbcProbe jdbc = new JdbcProbe();
        ImportTaskSpec spec = spec();
        spec.getOptions().setOnError("SKIP");
        spec.getOptions().setMaxErrors(0);
        IImportStrategy strategy = (ignored, shardContext) -> shardContext.logWarn(
                "IMPORT_ROW_REJECTED", "bad row", Map.of("rejectedRows", 1));

        assertThrows(TaskExecutionException.class, () -> importer(strategy, jdbc, new AtomicInteger())
                .executeShard(spec, context(new AtomicInteger(), new AtomicReference<>()),
                        connectInfo(), manifest, manifest.getShards().get(0)));

        assertEquals(0, jdbc.commits.get());
        assertEquals(1, jdbc.rollbacks.get());
    }

    private CsvManifestImporter importer(IImportStrategy strategy, JdbcProbe jdbc, AtomicInteger releases) {
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new CsvManifestImporter(new ImportManifestScheduler(unusedStorage), strategy,
                ignored -> jdbc.connection, ignored -> releases.incrementAndGet());
    }

    private ImportTaskSpec caseDistinctSpec(Path upperSource, Path lowerSource) {
        return ImportTaskSpec.builder().scope(ImportScope.SCHEMA).format("CSV")
                .mode(TaskExecutionMode.ULTRA_FAST).sourceKind("TRUSTED").cycleStrategy("REJECT")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("tenant").build())
                .tableSources(List.of(
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("Users").sourceFile(upperSource.toString()).format("CSV")
                                .columnMappings(List.of(ImportColumnMapping.builder()
                                        .sourceColumn("upper_id").targetColumn("UPPER_ID").build()))
                                .options(ImportOptions.builder().onError("ABORT").maxErrors(3).build()).build(),
                        ImportTableSource.builder().databaseName("app").schemaName("tenant")
                                .tableName("users").sourceFile(lowerSource.toString()).format("CSV")
                                .columnMappings(List.of(ImportColumnMapping.builder()
                                        .sourceColumn("lower_id").targetColumn("lower_id").build()))
                                .options(ImportOptions.builder().onError("SKIP").maxErrors(7).build()).build()))
                .build();
    }

    private ImportManifest tableManifest(Path source, String tableName, String tableKey, int schemaVersion) {
        ImportManifest manifest = manifest(source);
        ImportManifestShard shard = manifest.getShards().get(0);
        shard.setShardId(tableName + "-0");
        shard.setDatabaseName("app");
        shard.setSchemaName("tenant");
        shard.setTableName(tableName);
        shard.setTableKey(tableKey);
        manifest.setSchemaVersion(schemaVersion);
        manifest.setManifestFingerprint(ImportManifestIntegrity.calculate(manifest));
        return manifest;
    }

    private ImportTaskSpec spec() {
        return ImportTaskSpec.builder()
                .format("CSV")
                .mode(TaskExecutionMode.ULTRA_FAST)
                .target(TaskTargetSnapshot.builder().tableName("original").build())
                .options(ImportOptions.builder().charset("GBK").delimiter(";").skipRows(3).build())
                .build();
    }

    private ImportManifest manifest(Path source) {
        CsvShardPreprocessor.ShardVerification verification;
        try {
            verification = CsvShardPreprocessor.inspect(source.toFile());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ImportManifest manifest = ImportManifest.builder()
                .schemaVersion(1)
                .taskId(42L)
                .mode(ImportPlanMode.SERIAL_SAFE)
                .shards(List.of(ImportManifestShard.builder()
                        .shardId("orders-0")
                        .tableName("orders")
                        .layer(0)
                        .sourcePath(source.toString())
                        .estimatedRows(verification.rows())
                        .expectedChecksum(verification.checksum())
                        .dependencyShardIds(List.of())
                        .build()))
                .build();
        manifest.setManifestFingerprint(ImportManifestIntegrity.calculate(manifest));
        return manifest;
    }

    private ConnectInfo connectInfo() {
        ConnectInfo info = new ConnectInfo();
        info.setDbType("test");
        DriverConfig driver = new DriverConfig();
        driver.setDbType("test");
        info.setDriverConfig(driver);
        return info;
    }

    @SuppressWarnings("unchecked")
    private TaskExecutionContext context(AtomicInteger checkpoints,
            AtomicReference<Map<String, Object>> eventDetails) {
        return context(checkpoints, eventDetails, new AtomicReference<>());
    }

    private TaskExecutionContext context(AtomicInteger checkpoints,
            AtomicReference<Map<String, Object>> eventDetails, AtomicReference<String> eventCode) {
        return context(checkpoints, eventDetails, eventCode, new AtomicReference<>(), null);
    }

    @SuppressWarnings("unchecked")
    private TaskExecutionContext context(AtomicInteger checkpoints,
            AtomicReference<Map<String, Object>> eventDetails, AtomicReference<String> artifactRole,
            Path artifactPath) {
        return context(checkpoints, eventDetails, new AtomicReference<>(), artifactRole, artifactPath);
    }

    @SuppressWarnings("unchecked")
    private TaskExecutionContext context(AtomicInteger checkpoints,
            AtomicReference<Map<String, Object>> eventDetails, AtomicReference<String> eventCode,
            AtomicReference<String> artifactRole, Path artifactPath) {
        return (TaskExecutionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("taskId".equals(method.getName())) {
                        return 42L;
                    }
                    if ("checkpoint".equals(method.getName())) {
                        checkpoints.incrementAndGet();
                    }
                    if (("logInfo".equals(method.getName()) || "logWarn".equals(method.getName()))
                            && args.length == 3) {
                        eventCode.set((String) args[0]);
                        eventDetails.set((Map<String, Object>) args[2]);
                    }
                    if ("resumeStates".equals(method.getName())) {
                        return List.of(ResumeState.builder().shardNo(0).kind("MANIFEST_RUNNING").build());
                    }
                    if ("createArtifact".equals(method.getName()) && args.length == 4) {
                        artifactRole.set((String) args[0]);
                        return ArtifactDraft.builder().role((String) args[0])
                                .temporaryFile(artifactPath == null ? null : artifactPath.toFile())
                                .targetFile(artifactPath == null ? null : artifactPath.toFile())
                                .mediaType((String) args[3]).build();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class JdbcProbe {
        private final AtomicBoolean autoCommit = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private SQLException commitFailure;
        private SQLException autoCommitRestoreFailure;
        private Runnable beforeCommit = () -> { };
        private final Connection connection = (Connection) Proxy.newProxyInstance(
                CsvManifestImporterTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit.get();
                    case "setAutoCommit" -> {
                        boolean value = (Boolean) args[0];
                        if (value && autoCommitRestoreFailure != null) {
                            throw autoCommitRestoreFailure;
                        }
                        autoCommit.set(value);
                        yield null;
                    }
                    case "commit" -> {
                        beforeCommit.run();
                        commits.incrementAndGet();
                        if (commitFailure != null) {
                            throw commitFailure;
                        }
                        yield null;
                    }
                    case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                    case "close" -> { closed.set(true); yield null; }
                    case "isClosed" -> closed.get();
                    case "unwrap" -> proxy;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }
}
