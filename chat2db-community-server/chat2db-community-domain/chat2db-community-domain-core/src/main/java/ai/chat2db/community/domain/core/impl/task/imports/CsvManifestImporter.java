package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportPlanMode;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Executes preprocessed CSV shards with one connection and transaction per manifest shard. */
public final class CsvManifestImporter {

    private static final int MAX_CONCURRENCY = 16;

    private final ImportManifestScheduler scheduler;
    private final IImportStrategy csvImporter;
    private final ConnectionFactory connectionFactory;
    private final Consumer<ConnectInfo> connectionReleaser;

    public CsvManifestImporter(TaskStorage storage) {
        this(new ImportManifestScheduler(storage), ImportFactory.get("csv"), ConnectionPool::createNewConnection,
                CsvManifestImporter::closeDedicatedConnection);
    }

    CsvManifestImporter(ImportManifestScheduler scheduler, IImportStrategy csvImporter,
            ConnectionFactory connectionFactory, Consumer<ConnectInfo> connectionReleaser) {
        this.scheduler = scheduler;
        this.csvImporter = csvImporter;
        this.connectionFactory = connectionFactory;
        this.connectionReleaser = connectionReleaser;
    }

    public void execute(ImportTaskSpec original, TaskExecutionContext context, ImportManifest manifest) {
        ImportManifestIntegrity.requireValid(manifest);
        ImportManifestBuilder.requireVersionedShardIdentities(manifest);
        if (context.taskId() == null || !context.taskId().equals(manifest.getTaskId())) {
            throw new IllegalArgumentException("Manifest task identity does not match the running task");
        }
        if (original == null || original.getTarget() == null) {
            throw new IllegalArgumentException("CSV manifest import requires a target database");
        }
        ConnectInfo parent = Chat2DBContext.getConnectInfo();
        if (parent == null) {
            throw new IllegalStateException("Database context is unavailable for manifest import");
        }
        int concurrency = manifest.getMode() == ImportPlanMode.SERIAL_SAFE
                ? 1 : effectiveParallelism(manifest.getShards().size());
        Map<String, ShardImportSummary> summaries = new ConcurrentHashMap<>();
        AtomicLong rejectedRows = new AtomicLong();
        long maxErrors = maxErrors(original);
        boolean skipMode = isSkipMode(original);
        Throwable executionFailure = null;
        try {
            scheduler.execute(manifest, concurrency, context::checkCancelled,
                    (shard, cancellationCheck) -> executeShard(original, context, parent, manifest, shard,
                            summaries, rejectedRows, maxErrors, cancellationCheck));
        } catch (RuntimeException | Error failure) {
            executionFailure = failure;
            throw failure;
        } finally {
            if (skipMode) {
                try {
                    writeRejectSummary(context, manifest, summaries, rejectedRows.get());
                } catch (RuntimeException summaryFailure) {
                    if (executionFailure == null) {
                        throw summaryFailure;
                    }
                    executionFailure.addSuppressed(summaryFailure);
                }
            }
        }
    }

    static int effectiveParallelism(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("Manifest must contain at least one shard");
        }
        int admitted = ImportParallelAdmission.requestedParallelism();
        int requested = Integer.getInteger("chat2db.task.import.manifest-parallelism", admitted);
        return Math.max(1, Math.min(MAX_CONCURRENCY, Math.min(requested, shardCount)));
    }

    ImportManifestScheduler.ShardResult executeShard(ImportTaskSpec original, TaskExecutionContext context,
            ConnectInfo parent, ImportManifest manifest, ImportManifestShard shard) throws Exception {
        return executeShard(original, context, parent, manifest, shard, new ConcurrentHashMap<>(),
                new AtomicLong(), maxErrors(original), context::checkCancelled);
    }

    ImportManifestScheduler.ShardResult executeShard(ImportTaskSpec original, TaskExecutionContext context,
            ConnectInfo parent, ImportManifest manifest, ImportManifestShard shard,
            Runnable cancellationCheck) throws Exception {
        return executeShard(original, context, parent, manifest, shard, new ConcurrentHashMap<>(),
                new AtomicLong(), maxErrors(original), cancellationCheck);
    }

    private ImportManifestScheduler.ShardResult executeShard(ImportTaskSpec original,
            TaskExecutionContext context, ConnectInfo parent, ImportManifest manifest,
            ImportManifestShard shard, Map<String, ShardImportSummary> summaries,
            AtomicLong rejectedRows, long maxErrors, Runnable cancellationCheck) throws Exception {
        ImportManifestBuilder.requireVersionedShardIdentity(manifest.getSchemaVersion(), shard);
        Path source = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("Manifest shard is not readable: " + shard.getShardId());
        }
        CsvShardPreprocessor.ShardVerification verification =
                CsvShardPreprocessor.verify(source.toFile(), shard);
        long sourceBytes = Files.size(source);
        ImportTaskSpec shardSpec = shardSpec(original, shard, source);
        ShardImportSummary summary = new ShardImportSummary(shard);
        summaries.put(shard.getShardId(), summary);
        ConnectInfo isolated = parent.copy();
        if (StringUtils.isNotBlank(shard.getDatabaseName())) {
            isolated.setDatabaseName(shard.getDatabaseName());
        }
        if (StringUtils.isNotBlank(shard.getSchemaName())) {
            isolated.setSchemaName(shard.getSchemaName());
        }
        isolated.setLoginUser(parent.getLoginUser());
        Connection connection = null;
        boolean originalAutoCommit = true;
        boolean transactionStarted = false;
        boolean committed = false;
        boolean commitOutcomeUnknown = false;
        Throwable failure = null;
        try {
            connection = connectionFactory.open(isolated);
            isolated.setConnection(connection);
            Chat2DBContext.putContext(isolated);
            originalAutoCommit = connection.getAutoCommit();
            if (!originalAutoCommit) {
                throw new IllegalStateException("Dedicated manifest connection must start in auto-commit mode");
            }
            connection.setAutoCommit(false);
            transactionStarted = true;
            ShardContext shardContext = new ShardContext(context, manifest, shard, summary,
                    rejectedRows, maxErrors, cancellationCheck);
            csvImporter.run(shardSpec, shardContext);
            shardContext.enterCommitPhase();
            try {
                try {
                    connection.commit();
                } catch (java.sql.SQLException commitFailure) {
                    commitOutcomeUnknown = true;
                    throw new ImportManifestScheduler.CommitOutcomeUnknownException(
                            "Shard commit outcome is unknown; verify target data before retrying: "
                                    + shard.getShardId(), commitFailure);
                }
            } finally {
                shardContext.exitCommitPhase();
            }
            committed = true;
            summary.markCommitted(verification.rows());
            return new ImportManifestScheduler.ShardResult(verification.rows(), sourceBytes);
        } catch (Throwable shardFailure) {
            failure = shardFailure;
            if (commitOutcomeUnknown) {
                summary.markCommitUnknown(shardFailure);
            } else {
                summary.markFailed(shardFailure);
            }
            if (connection != null && transactionStarted && !committed && !commitOutcomeUnknown) {
                try {
                    connection.rollback();
                } catch (Throwable rollbackFailure) {
                    shardFailure.addSuppressed(rollbackFailure);
                }
            }
            if (shardFailure instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("CSV manifest shard failed", shardFailure);
        } finally {
            boolean reusable = !commitOutcomeUnknown;
            if (connection != null) {
                if (commitOutcomeUnknown) {
                    try {
                        connection.close();
                    } catch (Throwable closeFailure) {
                        if (failure != null) {
                            failure.addSuppressed(closeFailure);
                        }
                    } finally {
                        isolated.setConnection(null);
                    }
                } else {
                    try {
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (Throwable restoreFailure) {
                        reusable = false;
                        if (failure != null) {
                            failure.addSuppressed(restoreFailure);
                        }
                        try {
                            connection.close();
                        } catch (Throwable closeFailure) {
                            restoreFailure.addSuppressed(closeFailure);
                        } finally {
                            isolated.setConnection(null);
                        }
                        if (committed) {
                            try {
                                context.logWarn("IMPORT_CONNECTION_DISCARDED",
                                        "CSV shard committed but its connection state could not be restored",
                                        Map.of("shardId", shard.getShardId(),
                                                "errorType", restoreFailure.getClass().getSimpleName()));
                            } catch (Throwable loggingFailure) {
                                restoreFailure.addSuppressed(loggingFailure);
                            }
                        }
                    }
                }
            }
            try {
                if (reusable && connection != null) {
                    connectionReleaser.accept(isolated);
                }
            } finally {
                // The isolated connection was already returned by connectionReleaser.
                Chat2DBContext.clearContextReference();
            }
        }
    }

    private static void closeDedicatedConnection(ConnectInfo connectInfo) {
        connectInfo.close();
        connectInfo.setConnection(null);
    }

    private ImportTaskSpec shardSpec(ImportTaskSpec original, ImportManifestShard shard, Path source) {
        ImportTaskSpec sourceSpec = original;
        if (ImportTaskSourceSupport.isMultiTable(original)) {
            ImportTableSource tableSource = tableSourceForShard(original, shard);
            sourceSpec = ImportTaskSourceSupport.specForSource(original, tableSource);
        }
        ImportTaskSpec copy = JSON.parseObject(JSON.toJSONString(sourceSpec), ImportTaskSpec.class);
        copy.setSourceFile(source.toString());
        copy.setDisplayFileName(source.getFileName().toString());
        copy.setImportFileId(null);
        copy.setFormat("csv");
        copy.setMode(TaskExecutionMode.STANDARD);
        if (StringUtils.isNotBlank(shard.getDatabaseName())) {
            copy.getTarget().setDatabaseName(shard.getDatabaseName());
        }
        if (StringUtils.isNotBlank(shard.getSchemaName())) {
            copy.getTarget().setSchemaName(shard.getSchemaName());
        }
        copy.getTarget().setTableName(shard.getTableName());
        ImportOptions options = copy.getOptions() == null ? new ImportOptions() : copy.getOptions();
        options.setCharset("UTF-8");
        options.setDelimiter(",");
        options.setQuoteChar("\"");
        options.setSkipRows(0);
        copy.setOptions(options);
        return copy;
    }

    private static ImportTableSource tableSourceForShard(ImportTaskSpec original, ImportManifestShard shard) {
        List<ImportTableSource> sources = ImportTaskSourceSupport.effectiveSources(original);
        if (StringUtils.isNotBlank(shard.getTableKey())) {
            return requireUniqueSource(sources.stream()
                    .filter(source -> shard.getTableKey().equals(ImportTaskSourceSupport.tableKey(source)))
                    .toList(), shard);
        }

        List<ImportTableSource> exact = sources.stream()
                .filter(source -> matchesLegacySource(source, shard, false))
                .toList();
        if (!exact.isEmpty()) {
            return requireUniqueSource(exact, shard);
        }
        return requireUniqueSource(sources.stream()
                .filter(source -> matchesLegacySource(source, shard, true))
                .toList(), shard);
    }

    private static boolean matchesLegacySource(ImportTableSource source, ImportManifestShard shard,
            boolean ignoreCase) {
        return sameIdentifier(shard.getTableName(), source.getTableName(), ignoreCase)
                && (StringUtils.isBlank(shard.getDatabaseName())
                        || sameIdentifier(shard.getDatabaseName(), source.getDatabaseName(), ignoreCase))
                && (StringUtils.isBlank(shard.getSchemaName())
                        || sameIdentifier(shard.getSchemaName(), source.getSchemaName(), ignoreCase));
    }

    private static boolean sameIdentifier(String expected, String actual, boolean ignoreCase) {
        String normalizedExpected = StringUtils.trimToNull(expected);
        String normalizedActual = StringUtils.trimToNull(actual);
        return ignoreCase ? StringUtils.equalsIgnoreCase(normalizedExpected, normalizedActual)
                : StringUtils.equals(normalizedExpected, normalizedActual);
    }

    private static ImportTableSource requireUniqueSource(List<ImportTableSource> matches,
            ImportManifestShard shard) {
        String target = StringUtils.defaultIfBlank(shard.getTableKey(),
                ImportTaskSourceSupport.tableKey(shard.getDatabaseName(), shard.getSchemaName(),
                        shard.getTableName()));
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest shard target is not present in the import task: " + target);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "Manifest shard target is ambiguous in the import task: " + target);
        }
        return matches.get(0);
    }

    private static boolean isSkipMode(ImportTaskSpec spec) {
        return spec != null && ImportTaskSourceSupport.effectiveSources(spec).stream()
                .map(source -> ImportTaskSourceSupport.specForSource(spec, source).getOptions())
                .filter(java.util.Objects::nonNull)
                .anyMatch(options -> "SKIP".equalsIgnoreCase(options.getOnError()));
    }

    private static long maxErrors(ImportTaskSpec spec) {
        if (spec == null) {
            return Long.MAX_VALUE;
        }
        long result = Long.MAX_VALUE;
        for (ImportTableSource source : ImportTaskSourceSupport.effectiveSources(spec)) {
            ImportOptions options = ImportTaskSourceSupport.specForSource(spec, source).getOptions();
            if (options != null && "SKIP".equalsIgnoreCase(options.getOnError())
                    && options.getMaxErrors() != null && options.getMaxErrors() >= 0) {
                result = Math.min(result, options.getMaxErrors().longValue());
            }
        }
        return result;
    }

    private static void writeRejectSummary(TaskExecutionContext context, ImportManifest manifest,
            Map<String, ShardImportSummary> summaries, long rejectedRows) {
        List<Map<String, Object>> shards = new ArrayList<>();
        long importedRows = 0L;
        for (ShardImportSummary summary : summaries.values().stream()
                .sorted(Comparator.comparing(ShardImportSummary::shardId)).toList()) {
            importedRows += summary.importedRows();
            shards.add(summary.asMap());
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("taskId", manifest.getTaskId());
        report.put("manifestFingerprint", manifest.getManifestFingerprint());
        report.put("policy", "SKIP");
        report.put("shardCount", manifest.getShards().size());
        report.put("importedRows", importedRows);
        report.put("rejectedRows", rejectedRows);
        report.put("shards", shards);

        Path source = Path.of(manifest.getShards().get(0).getSourcePath()).toAbsolutePath().normalize();
        Path outputDirectory = source.getParent() == null ? Path.of(".").toAbsolutePath() : source.getParent();
        ArtifactDraft draft = context.createArtifact(TaskArtifactRole.REJECT_SUMMARY,
                outputDirectory.toString(), "import-" + manifest.getTaskId() + "-reject-summary.json",
                "application/json");
        try {
            Files.writeString(draft.getTemporaryFile().toPath(), JSON.toJSONString(report));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not write manifest reject summary", failure);
        }
        context.logInfo("IMPORT_SKIP_SUMMARY", "Manifest SKIP summary prepared", Map.of(
                "importedRows", importedRows, "rejectedRows", rejectedRows,
                "shardCount", manifest.getShards().size()));
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(ConnectInfo connectInfo) throws Exception;
    }

    /** Prevents the legacy single-file watermark from overwriting manifest shard states. */
    private static final class ShardContext implements TaskExecutionContext, ISqlExecutionStatementListener {
        private final TaskExecutionContext delegate;
        private final Map<String, Object> identity;
        private final String rejectRole;
        private final ShardImportSummary summary;
        private final AtomicLong rejectedRows;
        private final long maxErrors;
        private final Runnable cancellationCheck;

        private ShardContext(TaskExecutionContext delegate, ImportManifest manifest, ImportManifestShard shard,
                ShardImportSummary summary, AtomicLong rejectedRows, long maxErrors, Runnable cancellationCheck) {
            this.delegate = delegate;
            this.rejectRole = TaskArtifactRole.rejectForShard(shard.getShardId());
            this.summary = summary;
            this.rejectedRows = rejectedRows;
            this.maxErrors = maxErrors;
            this.cancellationCheck = cancellationCheck;
            this.identity = Map.of("manifestFingerprint", manifest.getManifestFingerprint(),
                    "layer", shard.getLayer(), "table", shard.getTableName(), "shardId", shard.getShardId());
        }

        @Override public Long taskId() { return delegate.taskId(); }
        @Override public void reportProgress(int progress, String stage, String message) {
            delegate.reportProgress(progress, stage, message);
        }
        @Override public void logInfo(String code, String message) { logInfo(code, message, Map.of()); }
        @Override public void logInfo(String code, String message, Map<String, Object> details) {
            if ("IMPORT_SUMMARY".equals(code)) {
                summary.capture(details);
            }
            delegate.logInfo(code, message, details(details));
        }
        @Override public void logWarn(String code, String message, Map<String, Object> details) {
            delegate.logWarn(code, message, details(details));
            if ("IMPORT_ROW_REJECTED".equals(code) && rejectedRows.incrementAndGet() > maxErrors) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Import aborted after exceeding the task-wide rejected row limit of " + maxErrors);
            }
        }
        @Override public void logError(String code, String message, Map<String, Object> details) {
            delegate.logError(code, message, details(details));
        }
        @Override public void checkCancelled() { cancellationCheck.run(); }
        @Override public void enterCommitPhase() {
            cancellationCheck.run();
            delegate.enterCommitPhase();
        }
        @Override public void exitCommitPhase() { delegate.exitCommitPhase(); }
        @Override public void registerCancelable(TaskCancelable resource) { delegate.registerCancelable(resource); }
        @Override public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return delegate.createArtifact(outputDirectory, fileName, mediaType);
        }
        @Override public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) {
            String effectiveRole = TaskArtifactRole.REJECT.equals(role) ? rejectRole : role;
            return delegate.createArtifact(effectiveRole, outputDirectory, fileName, mediaType);
        }
        @Override public void write(String content) { delegate.write(content); }
        @Override public List<ResumeState> resumeStates() { return List.of(); }
        @Override public void checkpoint(ResumeState state) { }
        @Override public void onStatementCreated(Statement statement) { delegate.onStatementCreated(statement); }
        @Override public void onStatementClosed(Statement statement) { delegate.onStatementClosed(statement); }

        private Map<String, Object> details(Map<String, Object> supplied) {
            Map<String, Object> merged = new HashMap<>(identity);
            if (supplied != null) {
                merged.putAll(supplied);
            }
            return Map.copyOf(merged);
        }
    }

    private static final class ShardImportSummary {
        private final ImportManifestShard shard;
        private volatile long importedRows = -1L;
        private volatile long rejectedRows;
        private volatile String status = "RUNNING";
        private volatile String failure;

        private ShardImportSummary(ImportManifestShard shard) {
            this.shard = shard;
        }

        private String shardId() {
            return shard.getShardId();
        }

        private long importedRows() {
            return Math.max(0L, importedRows);
        }

        private void capture(Map<String, Object> details) {
            importedRows = number(details, "importedRows", importedRows);
            rejectedRows = number(details, "rejectedRows", rejectedRows);
        }

        private void markCommitted(long inspectedRows) {
            if (importedRows < 0L) {
                importedRows = Math.max(0L, inspectedRows - rejectedRows);
            }
            status = "COMMITTED";
        }

        private void markFailed(Throwable cause) {
            status = "FAILED";
            failure = cause == null || cause.getMessage() == null
                    ? "unknown" : cause.getMessage();
        }

        private void markCommitUnknown(Throwable cause) {
            status = "COMMIT_UNKNOWN";
            failure = cause == null || cause.getMessage() == null
                    ? "unknown" : cause.getMessage();
        }

        private Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("shardId", shard.getShardId());
            value.put("table", shard.getTableName());
            value.put("sourcePath", shard.getSourcePath());
            value.put("status", status);
            value.put("importedRows", importedRows());
            value.put("rejectedRows", rejectedRows);
            value.put("rejectArtifactRole", TaskArtifactRole.rejectForShard(shard.getShardId()));
            if (failure != null) {
                value.put("failure", failure);
            }
            return Map.copyOf(value);
        }

        private static long number(Map<String, Object> details, String key, long fallback) {
            Object value = details == null ? null : details.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            return fallback;
        }
    }
}
