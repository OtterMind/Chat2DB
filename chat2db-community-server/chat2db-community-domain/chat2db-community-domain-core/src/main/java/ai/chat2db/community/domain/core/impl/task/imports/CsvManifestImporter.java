package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import com.alibaba.fastjson2.JSON;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Executes preprocessed CSV shards with one connection and transaction per manifest shard. */
public final class CsvManifestImporter {

    private static final int MAX_CONCURRENCY = 16;

    private final ImportManifestScheduler scheduler;
    private final IImportStrategy csvImporter;
    private final ConnectionFactory connectionFactory;
    private final Consumer<ConnectInfo> connectionReleaser;

    public CsvManifestImporter(TaskStorage storage) {
        this(new ImportManifestScheduler(storage), ImportFactory.get("csv"), ConnectionPool::getConnection,
                ConnectionPool::close);
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
        if (context.taskId() == null || !context.taskId().equals(manifest.getTaskId())) {
            throw new IllegalArgumentException("Manifest task identity does not match the running task");
        }
        if (original == null || original.getTarget() == null) {
            throw new IllegalArgumentException("CSV manifest import requires a target database");
        }
        if (original.getOptions() != null && "SKIP".equalsIgnoreCase(original.getOptions().getOnError())) {
            throw new IllegalArgumentException("Manifest import does not yet support a shared SKIP reject artifact");
        }
        ConnectInfo parent = Chat2DBContext.getConnectInfo();
        if (parent == null) {
            throw new IllegalStateException("Database context is unavailable for manifest import");
        }
        int requested = Integer.getInteger("chat2db.task.import.manifest-parallelism", 0);
        int concurrency = requested > 0 ? requested : Runtime.getRuntime().availableProcessors();
        concurrency = Math.max(1, Math.min(MAX_CONCURRENCY, Math.min(concurrency, manifest.getShards().size())));
        scheduler.execute(manifest, concurrency, context::checkCancelled,
                shard -> executeShard(original, context, parent, manifest, shard));
    }

    ImportManifestScheduler.ShardResult executeShard(ImportTaskSpec original, TaskExecutionContext context,
            ConnectInfo parent, ImportManifest manifest, ImportManifestShard shard) throws Exception {
        Path source = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new IllegalArgumentException("Manifest shard is not readable: " + shard.getShardId());
        }
        CsvShardPreprocessor.ShardVerification verification =
                CsvShardPreprocessor.verify(source.toFile(), shard);
        ImportTaskSpec shardSpec = shardSpec(original, shard, source);
        ConnectInfo isolated = parent.copy();
        isolated.setLoginUser("task-" + context.taskId() + "#manifest-" + shard.getShardId());
        Connection connection = null;
        boolean originalAutoCommit = true;
        boolean committed = false;
        Throwable failure = null;
        try {
            connection = connectionFactory.open(isolated);
            isolated.setConnection(connection);
            Chat2DBContext.putContext(isolated);
            originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            csvImporter.run(shardSpec, new ShardContext(context, manifest, shard));
            context.checkCancelled();
            connection.commit();
            committed = true;
            return new ImportManifestScheduler.ShardResult(verification.rows(), Files.size(source));
        } catch (Throwable shardFailure) {
            failure = shardFailure;
            if (connection != null && !committed) {
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
            boolean reusable = true;
            if (connection != null) {
                try {
                    connection.setAutoCommit(true);
                } catch (Throwable restoreFailure) {
                    reusable = false;
                    if (failure != null) {
                        failure.addSuppressed(restoreFailure);
                    }
                    try {
                        connection.close();
                    } catch (Throwable closeFailure) {
                        restoreFailure.addSuppressed(closeFailure);
                    }
                }
            }
            try {
                if (reusable && connection != null) {
                    connectionReleaser.accept(isolated);
                }
            } finally {
                Chat2DBContext.removeContext();
            }
        }
    }

    private ImportTaskSpec shardSpec(ImportTaskSpec original, ImportManifestShard shard, Path source) {
        ImportTaskSpec copy = JSON.parseObject(JSON.toJSONString(original), ImportTaskSpec.class);
        copy.setSourceFile(source.toString());
        copy.setDisplayFileName(source.getFileName().toString());
        copy.setImportFileId(null);
        copy.setFormat("csv");
        copy.setMode(TaskExecutionMode.STANDARD);
        copy.getTarget().setTableName(shard.getTableName());
        ImportOptions options = copy.getOptions() == null ? new ImportOptions() : copy.getOptions();
        options.setCharset("UTF-8");
        options.setDelimiter(",");
        options.setQuoteChar("\"");
        options.setSkipRows(0);
        copy.setOptions(options);
        return copy;
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(ConnectInfo connectInfo) throws Exception;
    }

    /** Prevents the legacy single-file watermark from overwriting manifest shard states. */
    private static final class ShardContext implements TaskExecutionContext, ISqlExecutionStatementListener {
        private final TaskExecutionContext delegate;
        private final Map<String, Object> identity;

        private ShardContext(TaskExecutionContext delegate, ImportManifest manifest, ImportManifestShard shard) {
            this.delegate = delegate;
            this.identity = Map.of("manifestFingerprint", manifest.getManifestFingerprint(),
                    "layer", shard.getLayer(), "table", shard.getTableName(), "shardId", shard.getShardId());
        }

        @Override public Long taskId() { return delegate.taskId(); }
        @Override public void reportProgress(int progress, String stage, String message) {
            delegate.reportProgress(progress, stage, message);
        }
        @Override public void logInfo(String code, String message) { logInfo(code, message, Map.of()); }
        @Override public void logInfo(String code, String message, Map<String, Object> details) {
            delegate.logInfo(code, message, details(details));
        }
        @Override public void logWarn(String code, String message, Map<String, Object> details) {
            delegate.logWarn(code, message, details(details));
        }
        @Override public void logError(String code, String message, Map<String, Object> details) {
            delegate.logError(code, message, details(details));
        }
        @Override public void checkCancelled() { delegate.checkCancelled(); }
        @Override public void registerCancelable(TaskCancelable resource) { delegate.registerCancelable(resource); }
        @Override public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            return delegate.createArtifact(outputDirectory, fileName, mediaType);
        }
        @Override public ArtifactDraft createArtifact(String role, String outputDirectory, String fileName,
                String mediaType) { return delegate.createArtifact(role, outputDirectory, fileName, mediaType); }
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
}
