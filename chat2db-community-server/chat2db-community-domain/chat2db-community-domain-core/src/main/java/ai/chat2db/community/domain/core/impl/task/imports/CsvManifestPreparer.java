package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionPolicy;
import ai.chat2db.community.domain.api.model.task.ImportAdmissionReport;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Completes CSV preprocessing and durable manifest publication before any shard worker starts. */
public final class CsvManifestPreparer {

    private static final long DEFAULT_TARGET_BYTES = 128L * 1024L * 1024L;

    private final TaskStorage storage;
    private final AdmissionEnforcer admissionEnforcer;
    private final DependencyResolver dependencyResolver;
    private final Path outputRoot;

    public CsvManifestPreparer(TaskStorage storage) {
        this(storage, CsvManifestPreparer::enforceAdmission,
                (spec, sources) -> new ImportDependencyResolver().resolve(spec, sources),
                Path.of(ConfigUtils.getBasePath(), "import-shards"));
    }

    CsvManifestPreparer(TaskStorage storage, AdmissionEnforcer admissionEnforcer, Path outputRoot) {
        this(storage, admissionEnforcer, (spec, sources) -> List.of(), outputRoot);
    }

    CsvManifestPreparer(TaskStorage storage, AdmissionEnforcer admissionEnforcer,
            DependencyResolver dependencyResolver, Path outputRoot) {
        this.storage = storage;
        this.admissionEnforcer = admissionEnforcer;
        this.dependencyResolver = dependencyResolver;
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public ImportManifest prepare(ImportTaskSpec spec, TaskExecutionContext context) {
        if (storage == null || context.taskId() == null || context.taskId() <= 0L) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "CSV manifest preparation requires durable task storage");
        }
        List<ImportTableSource> sources = ImportTaskSourceSupport.effectiveSources(spec);
        boolean scopedManifest = ImportTaskSourceSupport.isMultiTable(spec);
        List<ImportAdmissionReport> admissions = new ArrayList<>();
        boolean parallel = TaskExecutionMode.isUltraFast(spec.getMode());
        for (ImportTableSource source : sources) {
            ImportTaskSpec sourceSpec = scopedManifest ? ImportTaskSourceSupport.specForSource(spec, source) : spec;
            ImportAdmissionReport admission = admissionEnforcer.enforce(sourceSpec, context);
            admissions.add(admission);
            parallel &= TaskExecutionMode.isUltraFast(sourceSpec.getMode())
                    && TaskExecutionMode.isUltraFast(admission.getEffectiveMode());
        }
        if (!scopedManifest && !parallel) {
            return null;
        }
        if (!parallel) {
            spec.setMode(TaskExecutionMode.STANDARD);
        }
        List<SourcePreparation> preparations = sources.stream().map(source -> preparation(spec, source)).toList();
        String admissionVerdict = aggregateVerdict(admissions);
        List<ImportTableDependency> dependencies = dependencyResolver.resolve(spec, sources);
        List<String> tableKeys = sources.stream().map(ImportTaskSourceSupport::tableKey).toList();
        boolean trustedSource = !"THIRD_PARTY".equalsIgnoreCase(StringUtils.trimToEmpty(spec.getSourceKind()));
        ImportDependencyPlan plan = ImportDependencyPlanner.plan(tableKeys, dependencies, Map.of(),
                admissionVerdict, ImportAdmissionPolicy.MODERATE, trustedSource);
        if (!parallel && plan.getMode() != ImportPlanMode.STAGING_FIRST) {
            plan.setMode(ImportPlanMode.SERIAL_SAFE);
        }

        Path taskRoot = taskRoot(context.taskId());
        Path attemptDirectory = taskRoot.resolve(UUID.randomUUID().toString()).normalize();
        if (!attemptDirectory.startsWith(taskRoot)) {
            throw new IllegalStateException("CSV shard directory escaped the task root");
        }
        try {
            context.checkCancelled();
            context.reportProgress(10, TaskStage.READING.name(), "Preprocessing CSV shards");
            long targetBytes = Long.getLong("chat2db.task.import.csv-shard-target-bytes", DEFAULT_TARGET_BYTES);
            Map<String, String> sourceFingerprints = fingerprints(preparations);
            String sourceFingerprint = combinedFingerprint(sourceFingerprints);
            List<ImportManifestShard> shards = new ArrayList<>();
            for (SourcePreparation preparation : preparations) {
                int layer = layer(plan, preparation.tableKey());
                shards.addAll(CsvShardPreprocessor.preprocess(preparation.file(), preparation.charset(),
                        preparation.csvFormat(), attemptDirectory, preparation.source().getDatabaseName(),
                        preparation.source().getSchemaName(), preparation.source().getTableName(),
                        preparation.tableKey(), layer, targetBytes, preparation.skipRows()));
            }
            context.checkCancelled();
            if (!sourceFingerprints.equals(fingerprints(preparations))) {
                throw new IllegalStateException("A CSV source changed while shards were being prepared");
            }
            attachDependencyShards(shards, dependencies);
            ImportManifest manifest = ImportManifestBuilder.build(context.taskId(), admissionVerdict,
                    sourceFingerprint, plan, dependencies, shards);
            storage.saveImportManifest(context.taskId(), manifest);
            context.logInfo("IMPORT_MANIFEST_PREPARED", "CSV shards and manifest are ready",
                    Map.of("manifestFingerprint", manifest.getManifestFingerprint(),
                            "sourceFingerprint", manifest.getSourceFingerprint(),
                            "shards", manifest.getShards().size(),
                            "tables", sources.size(),
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
                admissionMetadataRequest(spec, connectInfo));
        return ImportParallelAdmission.enforce(spec, columns, context);
    }

    static TableMetadataRequest admissionMetadataRequest(ImportTaskSpec spec, ConnectInfo connectInfo) {
        TaskTargetSnapshot target = spec.getTarget();
        return new TableMetadataRequest(StringUtils.defaultIfBlank(target.getDatabaseName(),
                connectInfo.getDatabaseName()), StringUtils.defaultIfBlank(target.getSchemaName(),
                connectInfo.getSchemaName()), target.getTableName());
    }

    private static SourcePreparation preparation(ImportTaskSpec parent, ImportTableSource source) {
        ImportTaskSpec sourceSpec = ImportTaskSourceSupport.specForSource(parent, source);
        File file = new File(StringUtils.defaultString(source.getSourceFile()));
        if (!file.isFile() || !file.canRead() || StringUtils.isBlank(source.getTableName())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "CSV manifest preparation requires a readable source for every target table");
        }
        if (!"CSV".equalsIgnoreCase(StringUtils.defaultString(sourceSpec.getFormat()))) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Schema/database manifest import currently requires CSV for every table source");
        }
        try {
            Charset charset = ImportFileProbe.effectiveCharset(file,
                    sourceSpec.getOptions() == null ? null : sourceSpec.getOptions().getCharset());
            char quote = ImportFileProbe.quoteChar(
                    sourceSpec.getOptions() == null ? null : sourceSpec.getOptions().getQuoteChar());
            char delimiter = ImportFileProbe.delimiterChar(
                    sourceSpec.getOptions() == null ? null : sourceSpec.getOptions().getDelimiter(), charset, file);
            int skipRows = sourceSpec.getOptions() == null || sourceSpec.getOptions().getSkipRows() == null
                    ? 0 : Math.max(0, sourceSpec.getOptions().getSkipRows());
            return new SourcePreparation(source, file, ImportTaskSourceSupport.tableKey(source), charset,
                    ImportFileProbe.csvFormat(delimiter, quote), skipRows);
        } catch (IOException failure) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not detect CSV dialect for target table " + source.getTableName(), failure);
        }
    }

    private static String aggregateVerdict(List<ImportAdmissionReport> admissions) {
        if (admissions.stream().anyMatch(report -> ImportParallelAdmission.FORBIDDEN.equals(report.getVerdict()))) {
            return ImportParallelAdmission.FORBIDDEN;
        }
        if (admissions.stream().anyMatch(report -> ImportParallelAdmission.DEGRADED.equals(report.getVerdict()))) {
            return ImportParallelAdmission.DEGRADED;
        }
        return ImportParallelAdmission.SAFE;
    }

    private static Map<String, String> fingerprints(List<SourcePreparation> preparations) {
        Map<String, String> result = new LinkedHashMap<>();
        preparations.stream().sorted(Comparator.comparing(SourcePreparation::tableKey,
                        String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(SourcePreparation::tableKey))
                .forEach(preparation -> {
                    try {
                        result.put(preparation.tableKey(), sourceFingerprint(preparation.file().toPath()));
                    } catch (IOException failure) {
                        throw new IllegalStateException("Could not fingerprint CSV source: "
                                + preparation.file().getName(), failure);
                    }
                });
        return Map.copyOf(result);
    }

    private static String combinedFingerprint(Map<String, String> fingerprints) {
        if (fingerprints.size() == 1) {
            return fingerprints.values().iterator().next();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprints.entrySet().stream().sorted(Map.Entry.comparingByKey(
                            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())))
                    .forEach(entry -> digest.update((entry.getKey() + "\u0000" + entry.getValue() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return "SHA-256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int layer(ImportDependencyPlan plan, String tableKey) {
        for (int index = 0; index < plan.getLayers().size(); index++) {
            if (plan.getLayers().get(index).stream().anyMatch(tableKey::equals)) {
                return index;
            }
        }
        throw new IllegalStateException("Target table is missing from the dependency plan: " + tableKey);
    }

    private static void attachDependencyShards(List<ImportManifestShard> shards,
            List<ImportTableDependency> dependencies) {
        Map<String, List<String>> shardIdsByTable = new LinkedHashMap<>();
        for (ImportManifestShard shard : shards) {
            shardIdsByTable.computeIfAbsent(shard.getTableKey(), ignored -> new ArrayList<>())
                    .add(shard.getShardId());
        }
        for (ImportManifestShard shard : shards) {
            List<String> required = dependencies.stream()
                    .filter(edge -> shard.getTableKey().equals(edge.getChildTableKey()))
                    .filter(edge -> !edge.getParentTableKey().equals(edge.getChildTableKey()))
                    .flatMap(edge -> shardIdsByTable.getOrDefault(edge.getParentTableKey(), List.of()).stream())
                    .filter(parentShardId -> shards.stream().anyMatch(parentShard ->
                            parentShard.getShardId().equals(parentShardId)
                                    && parentShard.getLayer() < shard.getLayer()))
                    .distinct().sorted().toList();
            shard.setDependencyShardIds(required);
        }
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

    @FunctionalInterface
    interface DependencyResolver {
        List<ImportTableDependency> resolve(ImportTaskSpec spec, List<ImportTableSource> sources);
    }

    private record SourcePreparation(ImportTableSource source, File file, String tableKey,
            Charset charset, CSVFormat csvFormat, int skipRows) {
    }
}
