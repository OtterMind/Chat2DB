package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportAdmissionReport;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

    private static TaskStorage storage(AtomicReference<ImportManifest> saved, RuntimeException failure) {
        return (TaskStorage) Proxy.newProxyInstance(CsvManifestPreparerTest.class.getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> {
                    if ("saveImportManifest".equals(method.getName())) {
                        if (failure != null) {
                            throw failure;
                        }
                        saved.set((ImportManifest) args[1]);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
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
