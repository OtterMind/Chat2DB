package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionPolicy;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionReport;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Completes CSV preprocessing and durable manifest publication before any shard worker starts. */
public final class CsvManifestPreparer {

    private static final long DEFAULT_TARGET_BYTES = 128L * 1024L * 1024L;

    private final TaskStorage storage;
    private final AdmissionEnforcer admissionEnforcer;
    private final Path outputRoot;

    public CsvManifestPreparer(TaskStorage storage) {
        this(storage, CsvManifestPreparer::enforceAdmission,
                Path.of(ConfigUtils.getBasePath(), "import-shards"));
    }

    CsvManifestPreparer(TaskStorage storage, AdmissionEnforcer admissionEnforcer, Path outputRoot) {
        this.storage = storage;
        this.admissionEnforcer = admissionEnforcer;
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public ImportManifest prepare(ImportTaskSpec spec, TaskExecutionContext context) {
        if (storage == null || context.taskId() == null || context.taskId() <= 0L) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "CSV manifest preparation requires durable task storage");
        }
        ImportAdmissionReport admission = admissionEnforcer.enforce(spec, context);
        if (!TaskExecutionMode.isUltraFast(spec.getMode())) {
            return null;
        }
        if (spec.getOptions() != null && "SKIP".equalsIgnoreCase(spec.getOptions().getOnError())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Ultra-fast CSV import does not yet support per-shard reject artifacts");
        }

        File source = new File(StringUtils.defaultString(spec.getSourceFile()));
        String tableName = spec.getTarget() == null ? null : spec.getTarget().getTableName();
        if (!source.isFile() || !source.canRead() || StringUtils.isBlank(tableName)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "CSV manifest preparation requires a readable source and target table");
        }

        Path taskRoot = taskRoot(context.taskId());
        Path attemptDirectory = taskRoot.resolve(UUID.randomUUID().toString()).normalize();
        if (!attemptDirectory.startsWith(taskRoot)) {
            throw new IllegalStateException("CSV shard directory escaped the task root");
        }
        try {
            context.checkCancelled();
            context.reportProgress(10, TaskStage.READING.name(), "Preprocessing CSV shards");
            Charset charset = ImportFileProbe.effectiveCharset(source,
                    spec.getOptions() == null ? null : spec.getOptions().getCharset());
            char quote = ImportFileProbe.quoteChar(
                    spec.getOptions() == null ? null : spec.getOptions().getQuoteChar());
            char delimiter = ImportFileProbe.delimiterChar(
                    spec.getOptions() == null ? null : spec.getOptions().getDelimiter(), charset, source);
            CSVFormat csvFormat = ImportFileProbe.csvFormat(delimiter, quote);
            long targetBytes = Long.getLong("chat2db.task.import.csv-shard-target-bytes", DEFAULT_TARGET_BYTES);
            String sourceFingerprint = sourceFingerprint(source.toPath());
            List<ImportManifestShard> shards = CsvShardPreprocessor.preprocess(source, charset, csvFormat,
                    attemptDirectory, tableName, 0, targetBytes);
            context.checkCancelled();
            if (!sourceFingerprint.equals(sourceFingerprint(source.toPath()))) {
                throw new IllegalStateException("CSV source changed while shards were being prepared");
            }
            ImportDependencyPlan plan = ImportDependencyPlanner.plan(List.of(tableName), List.of(), Map.of(),
                    admission.getVerdict(), ImportAdmissionPolicy.STRICT, true);
            ImportManifest manifest = ImportManifestBuilder.build(context.taskId(), admission.getVerdict(),
                    sourceFingerprint, plan, List.of(), shards);
            storage.saveImportManifest(context.taskId(), manifest);
            context.logInfo("IMPORT_MANIFEST_PREPARED", "CSV shards and manifest are ready",
                    Map.of("manifestFingerprint", manifest.getManifestFingerprint(),
                            "sourceFingerprint", manifest.getSourceFingerprint(),
                            "shards", manifest.getShards().size(),
                            "estimatedRows", manifest.getTotalEstimatedRows()));
            return manifest;
        } catch (Exception failure) {
            deleteTree(attemptDirectory);
            deleteEmptyParentsQuietly(attemptDirectory.getParent(), taskRoot);
            if (failure instanceof TaskExecutionException taskFailure) {
                throw taskFailure;
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prepare CSV import manifest", failure);
        }
    }

    public void cleanup(ImportManifest manifest) {
        if (manifest == null || manifest.getTaskId() == null) {
            return;
        }
        Path root = taskRoot(manifest.getTaskId());
        for (ImportManifestShard shard : manifest.getShards()) {
            try {
                Path path = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
                if (path.startsWith(root)) {
                    Files.deleteIfExists(path);
                    deleteEmptyParents(path.getParent(), root);
                }
            } catch (IOException | RuntimeException ignored) {
                // Cleanup must not turn an already committed import into a failed task.
            }
        }
    }

    private Path taskRoot(Long taskId) {
        Path root = outputRoot.resolve("task-" + taskId).normalize();
        if (!root.startsWith(outputRoot)) {
            throw new IllegalStateException("CSV shard task root escaped configured storage");
        }
        return root;
    }

    private static ImportAdmissionReport enforceAdmission(ImportTaskSpec spec, TaskExecutionContext context) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IDbMetaData metadata = Chat2DBContext.getDbMetaData();
        List<TableColumn> columns = metadata.columns(Chat2DBContext.getConnection(),
                new TableMetadataRequest(connectInfo.getDatabaseName(), connectInfo.getSchemaName(),
                        spec.getTarget().getTableName()));
        return ImportParallelAdmission.enforce(spec, columns, context);
    }

    private static String sourceFingerprint(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(source)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return "SHA-256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The original preparation failure remains authoritative.
                }
            });
        } catch (IOException ignored) {
            // The original preparation failure remains authoritative.
        }
    }

    private static void deleteEmptyParents(Path directory, Path stop) throws IOException {
        Path current = directory;
        while (current != null && current.startsWith(stop)) {
            try (var entries = Files.list(current)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            if (current.equals(stop)) {
                return;
            }
            current = current.getParent();
        }
    }

    private static void deleteEmptyParentsQuietly(Path directory, Path stop) {
        try {
            deleteEmptyParents(directory, stop);
        } catch (IOException ignored) {
            // The original preparation failure remains authoritative.
        }
    }

    @FunctionalInterface
    interface AdmissionEnforcer {
        ImportAdmissionReport enforce(ImportTaskSpec spec, TaskExecutionContext context);
    }
}
