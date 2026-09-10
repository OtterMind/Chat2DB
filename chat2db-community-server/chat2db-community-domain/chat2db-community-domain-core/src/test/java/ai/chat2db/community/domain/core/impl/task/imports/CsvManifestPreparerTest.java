package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionReport;
import ai.chat2db.community.domain.api.model.task.ImportColumnMapping;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvManifestPreparerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void preprocessesAndPersistsAnImmutableSingleTablePlan() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("orders.csv"), "id,name\n1,Alice\n2,Bob\n");
        AtomicReference<ImportManifest> saved = new AtomicReference<>();
        TaskStorage storage = storage(saved, null);
        CsvManifestPreparer preparer = new CsvManifestPreparer(storage, this::safeAdmission,
                tempDirectory.resolve("shards"));

        String previous = System.getProperty("chat2db.task.import.csv-shard-target-bytes");
        System.setProperty("chat2db.task.import.csv-shard-target-bytes", "16");
        ImportManifest manifest;
        try {
            manifest = preparer.prepare(spec(source), context(42L));
        } finally {
            restore("chat2db.task.import.csv-shard-target-bytes", previous);
        }

        assertNotNull(manifest);
        assertEquals(manifest, saved.get());
        assertEquals(42L, manifest.getTaskId());
        assertEquals(2L, manifest.getTotalEstimatedRows());
        assertTrue(manifest.getSourceFingerprint().startsWith("SHA-256:"));
        assertTrue(manifest.getShards().stream().allMatch(shard ->
                Files.isRegularFile(Path.of(shard.getSourcePath()))));
        preparer.cleanup(manifest);
        assertTrue(manifest.getShards().stream().noneMatch(shard ->
                Files.exists(Path.of(shard.getSourcePath()))));
    }

    @Test
    void removesGeneratedShardsWhenManifestPersistenceFails() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("orders.csv"), "id\n1\n");
        Path output = tempDirectory.resolve("shards");
        CsvManifestPreparer preparer = new CsvManifestPreparer(
                storage(new AtomicReference<>(), new IllegalStateException("storage unavailable")),
                this::safeAdmission, output);

        assertThrows(RuntimeException.class, () -> preparer.prepare(spec(source), context(7L)));

        assertFalse(Files.exists(output.resolve("task-7")));
    }

    @Test
    void returnsToSerialExecutionWhenAdmissionDowngradesTheMode() throws Exception {
        Path source = Files.writeString(tempDirectory.resolve("orders.csv"), "id\n1\n");
        CsvManifestPreparer preparer = new CsvManifestPreparer(storage(new AtomicReference<>(), null),
                (spec, context) -> {
                    spec.setMode(TaskExecutionMode.STANDARD);
                    return safeAdmission(spec, context);
                }, tempDirectory.resolve("shards"));

        assertEquals(null, preparer.prepare(spec(source), context(9L)));
        assertFalse(Files.exists(tempDirectory.resolve("shards")));
    }

    @Test
    void persistsAStandardMultiTableDagManifestWithQualifiedTargets() throws Exception {
        Path orders = Files.writeString(tempDirectory.resolve("orders.csv"), "id\n1\n");
        Path items = Files.writeString(tempDirectory.resolve("items.csv"), "id,order_id\n10,1\n");
        ImportTableSource ordersSource = tableSource("orders", orders);
        ImportTableSource itemsSource = tableSource("order_items", items);
        ImportTableDependency dependency = ImportTableDependency.builder()
                .parentDatabaseName("app").parentSchemaName("public").parentTable("orders")
                .parentColumn("id").parentTableKey("app.public.orders")
                .childDatabaseName("app").childSchemaName("public").childTable("order_items")
                .childColumn("order_id").childTableKey("app.public.order_items").build();
        ImportTaskSpec spec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA)
                .mode(TaskExecutionMode.STANDARD).format("CSV")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("public").build())
                .tableSources(List.of(ordersSource, itemsSource)).build();
        AtomicReference<ImportManifest> saved = new AtomicReference<>();
        CsvManifestPreparer preparer = new CsvManifestPreparer(storage(saved, null), this::safeAdmission,
                (task, sources) -> List.of(dependency), tempDirectory.resolve("multi-shards"));

        ImportManifest manifest = preparer.prepare(spec, context(84L));

        assertEquals(manifest, saved.get());
        assertEquals(ImportPlanMode.SERIAL_SAFE, manifest.getMode());
        assertEquals(List.of(List.of("app.public.orders"), List.of("app.public.order_items")),
                manifest.getDependencyPlan().getLayers());
        assertEquals(2, manifest.getShards().size());
        assertTrue(manifest.getShards().stream().allMatch(shard -> "app".equals(shard.getDatabaseName())
                && "public".equals(shard.getSchemaName())));
        String ordersShardId = manifest.getShards().stream()
                .filter(shard -> "orders".equals(shard.getTableName())).findFirst().orElseThrow().getShardId();
        assertEquals(List.of(ordersShardId), manifest.getShards().stream()
                .filter(shard -> "order_items".equals(shard.getTableName())).findFirst().orElseThrow()
                .getDependencyShardIds());
    }

    @Test
    void preservesCaseDistinctQuotedTablesAcrossPlanningShardsAndDependencies() throws Exception {
        Path parentFile = Files.writeString(tempDirectory.resolve("Users.csv"), "id,name\n1,Alice\n");
        Path childFile = Files.writeString(tempDirectory.resolve("users.csv"), "id,parent_id\n10,1\n");
        ImportTableSource parent = tableSource("Users", parentFile);
        ImportTableSource child = tableSource("users", childFile);
        parent.setColumnMappings(List.of(ImportColumnMapping.builder()
                .sourceColumn("id").targetColumn("UPPER_ID").build()));
        parent.getOptions().setOnError("ABORT");
        parent.getOptions().setMaxErrors(3);
        parent.getOptions().setNullString("\\N");
        child.setColumnMappings(List.of(ImportColumnMapping.builder()
                .sourceColumn("parent_id").targetColumn("lower_parent_id").build()));
        child.getOptions().setOnError("ABORT");
        child.getOptions().setMaxErrors(7);
        child.getOptions().setNullString("NULL");
        ForeignKeyInfo dependency = new ForeignKeyInfo();
        dependency.setPkTableCat("app");
        dependency.setPkTableSchem("public");
        dependency.setPkTableName("Users");
        dependency.setPkColumnName("id");
        dependency.setFkTableCat("app");
        dependency.setFkTableSchem("public");
        dependency.setFkTableName("users");
        dependency.setFkColumnName("parent_id");
        dependency.setFkName("fk_users_Users");
        dependency.setKeySeq((short) 1);
        dependency.setDeferrability((short) 5);
        ImportTaskSpec taskSpec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA)
                .mode(TaskExecutionMode.STANDARD).format("CSV")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("public").build())
                .tableSources(List.of(parent, child)).build();
        ImportDependencyResolver resolver = new ImportDependencyResolver(source ->
                "users".equals(source.getTableName()) ? List.of(dependency) : List.of());
        AtomicReference<ImportManifest> saved = new AtomicReference<>();
        TaskStorage storage = storage(saved, null);
        CsvManifestPreparer preparer = new CsvManifestPreparer(
                storage, this::safeAdmission,
                resolver::resolve, tempDirectory.resolve("quoted-case-shards"));

        ImportManifest manifest = preparer.prepare(taskSpec, context(86L));

        assertEquals(List.of(List.of("app.public.Users"), List.of("app.public.users")),
                manifest.getDependencyPlan().getLayers());
        assertEquals(2, manifest.getShards().size());
        String parentShard = manifest.getShards().stream()
                .filter(shard -> "Users".equals(shard.getTableName()))
                .findFirst().orElseThrow().getShardId();
        var childShard = manifest.getShards().stream()
                .filter(shard -> "users".equals(shard.getTableName()))
                .findFirst().orElseThrow();
        assertFalse(parentShard.equals(childShard.getShardId()));
        assertEquals(List.of(parentShard), childShard.getDependencyShardIds());

        Map<String, ImportTaskSpec> executed = new ConcurrentHashMap<>();
        AtomicInteger commits = new AtomicInteger();
        CsvManifestImporter importer = new CsvManifestImporter(new ImportManifestScheduler(storage),
                (shardSpec, ignored) -> executed.put(shardSpec.getTarget().getTableName(), shardSpec),
                ignored -> transactionalConnection(commits), ignored -> { });
        ConnectInfo parentContext = new ConnectInfo();
        parentContext.setDbType("H2");
        parentContext.setDatabaseName("app");
        parentContext.setSchemaName("public");
        Chat2DBContext.putContext(parentContext);
        try {
            importer.execute(taskSpec, context(86L),
                    storage.loadImportManifest(86L).orElseThrow());
        } finally {
            Chat2DBContext.removeContext();
        }

        assertEquals(2, commits.get());
        assertEquals(List.of("Users", "users"), executed.keySet().stream().sorted().toList());
        assertEquals("UPPER_ID", executed.get("Users").getColumnMappings().get(0).getTargetColumn());
        assertEquals("\\N", executed.get("Users").getOptions().getNullString());
        assertEquals(3, executed.get("Users").getOptions().getMaxErrors());
        assertEquals("lower_parent_id",
                executed.get("users").getColumnMappings().get(0).getTargetColumn());
        assertEquals("NULL", executed.get("users").getOptions().getNullString());
        assertEquals(7, executed.get("users").getOptions().getMaxErrors());
    }

    @Test
    void appliesEachSourceSkipRowsBeforeBuildingTheManifest() throws Exception {
        Path orders = Files.writeString(tempDirectory.resolve("orders-with-prefix.csv"),
                "id\nignore\n1\n2\n");
        Path items = Files.writeString(tempDirectory.resolve("items-with-prefix.csv"),
                "id\nignore-a\nignore-b\n10\n");
        ImportTableSource ordersSource = tableSource("orders", orders);
        ordersSource.getOptions().setSkipRows(1);
        ImportTableSource itemsSource = tableSource("order_items", items);
        itemsSource.getOptions().setSkipRows(2);
        ImportTaskSpec spec = ImportTaskSpec.builder().scope(ImportScope.SCHEMA)
                .mode(TaskExecutionMode.STANDARD).format("CSV")
                .target(TaskTargetSnapshot.builder().databaseName("app").schemaName("public").build())
                .tableSources(List.of(ordersSource, itemsSource)).build();
        CsvManifestPreparer preparer = new CsvManifestPreparer(storage(new AtomicReference<>(), null),
                this::safeAdmission, (task, sources) -> List.of(), tempDirectory.resolve("skip-shards"));

        ImportManifest manifest = preparer.prepare(spec, context(85L));

        assertEquals(3L, manifest.getTotalEstimatedRows());
        assertEquals(2L, manifest.getShards().stream()
                .filter(shard -> "orders".equals(shard.getTableName()))
                .mapToLong(shard -> shard.getEstimatedRows()).sum());
        assertEquals(1L, manifest.getShards().stream()
                .filter(shard -> "order_items".equals(shard.getTableName()))
                .mapToLong(shard -> shard.getEstimatedRows()).sum());
    }

    @Test
    void admissionUsesTheSourceTargetNamespaceInsteadOfTheCurrentConnectionNamespace() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDatabaseName("catalog_a");
        connectInfo.setSchemaName("schema_a");
        ImportTaskSpec sourceSpec = ImportTaskSpec.builder()
                .target(TaskTargetSnapshot.builder()
                        .databaseName("catalog_b")
                        .schemaName("schema_b")
                        .tableName("orders")
                        .build())
                .build();

        TableMetadataRequest request = CsvManifestPreparer.admissionMetadataRequest(sourceSpec, connectInfo);

        assertEquals("catalog_b", request.getDatabaseName());
        assertEquals("schema_b", request.getSchemaName());
        assertEquals("orders", request.getTableName());
    }

    private ImportAdmissionReport safeAdmission(ImportTaskSpec spec, TaskExecutionContext context) {
        return ImportAdmissionReport.builder().verdict(ImportParallelAdmission.SAFE)
                .requestedMode(TaskExecutionMode.ULTRA_FAST).effectiveMode(spec.getMode())
                .parallelAllowed(TaskExecutionMode.isUltraFast(spec.getMode())).build();
    }

    private static ImportTaskSpec spec(Path source) {
        return ImportTaskSpec.builder().sourceFile(source.toString()).format("CSV")
                .mode(TaskExecutionMode.ULTRA_FAST).importFileId("staged")
                .confirmedNoStrongRelations(true)
                .target(TaskTargetSnapshot.builder().tableName("orders").build())
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",").quoteChar("\"").build())
                .build();
    }

    private static ImportTableSource tableSource(String tableName, Path source) {
        return ImportTableSource.builder().databaseName("app").schemaName("public")
                .tableName(tableName).sourceFile(source.toString()).format("CSV")
                .options(ImportOptions.builder().charset("UTF-8").delimiter(",").quoteChar("\"").build())
                .build();
    }

    private static TaskStorage storage(AtomicReference<ImportManifest> saved, RuntimeException failure) {
        Map<Integer, ResumeState> resumeStates = new ConcurrentHashMap<>();
        return (TaskStorage) Proxy.newProxyInstance(CsvManifestPreparerTest.class.getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> {
                    if ("saveImportManifest".equals(method.getName())) {
                        if (failure != null) {
                            throw failure;
                        }
                        saved.set((ImportManifest) args[1]);
                        return null;
                    }
                    if ("loadImportManifest".equals(method.getName())) {
                        return Optional.ofNullable(saved.get());
                    }
                    if ("listResumeStates".equals(method.getName())) {
                        return new ArrayList<>(resumeStates.values());
                    }
                    if ("saveResumeState".equals(method.getName())) {
                        ResumeState state = (ResumeState) args[1];
                        resumeStates.put(state.getShardNo(), state);
                        return null;
                    }
                    if ("compareAndSetResumeState".equals(method.getName())) {
                        Integer shardNo = (Integer) args[1];
                        synchronized (resumeStates) {
                            ResumeState current = resumeStates.get(shardNo);
                            if (current == null || !Objects.equals(args[2], current.getKind())) {
                                return false;
                            }
                            resumeStates.put(shardNo, (ResumeState) args[3]);
                            return true;
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Connection transactionalConnection(AtomicInteger commits) {
        AtomicBoolean autoCommit = new AtomicBoolean(true);
        return (Connection) Proxy.newProxyInstance(CsvManifestPreparerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit.get();
                    case "setAutoCommit" -> {
                        autoCommit.set((Boolean) args[0]);
                        yield null;
                    }
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback", "close" -> null;
                    case "isClosed" -> false;
                    case "unwrap" -> proxy;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static TaskExecutionContext context(Long taskId) {
        return (TaskExecutionContext) Proxy.newProxyInstance(CsvManifestPreparerTest.class.getClassLoader(),
                new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("taskId".equals(method.getName())) {
                        return taskId;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return type == Map.class ? Map.of() : null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
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
}
