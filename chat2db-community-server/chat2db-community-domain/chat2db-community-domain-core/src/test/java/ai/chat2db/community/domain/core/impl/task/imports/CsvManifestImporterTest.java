package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private CsvManifestImporter importer(IImportStrategy strategy, JdbcProbe jdbc, AtomicInteger releases) {
        TaskStorage unusedStorage = (TaskStorage) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskStorage.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new CsvManifestImporter(new ImportManifestScheduler(unusedStorage), strategy,
                ignored -> jdbc.connection, ignored -> releases.incrementAndGet());
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
        return ImportManifest.builder()
                .schemaVersion(1)
                .taskId(42L)
                .manifestFingerprint("manifest-abc")
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
        return (TaskExecutionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                    if ("taskId".equals(method.getName())) {
                        return 42L;
                    }
                    if ("checkpoint".equals(method.getName())) {
                        checkpoints.incrementAndGet();
                    }
                    if ("logInfo".equals(method.getName()) && args.length == 3) {
                        eventDetails.set((Map<String, Object>) args[2]);
                    }
                    if ("resumeStates".equals(method.getName())) {
                        return List.of(ResumeState.builder().shardNo(0).kind("MANIFEST_RUNNING").build());
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
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final Connection connection = (Connection) Proxy.newProxyInstance(
                CsvManifestImporterTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit.get();
                    case "setAutoCommit" -> { autoCommit.set((Boolean) args[0]); yield null; }
                    case "commit" -> { commits.incrementAndGet(); yield null; }
                    case "rollback" -> { rollbacks.incrementAndGet(); yield null; }
                    case "isClosed" -> false;
                    case "unwrap" -> proxy;
                    case "isWrapperFor" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }
}
