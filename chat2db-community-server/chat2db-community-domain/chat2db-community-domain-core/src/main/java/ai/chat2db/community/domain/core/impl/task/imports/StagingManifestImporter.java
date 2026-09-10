package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.metadata.PrimaryKey;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportDependencyPlan;
import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.ImportManifestIntegrity;
import ai.chat2db.community.domain.api.model.task.ImportManifestShard;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportTableSource;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.domain.api.model.task.TaskArtifactRole;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes schema/database CSV imports through raw VARCHAR and typed temporary tables. Target
 * writes share one transaction, so preflight failures and rehearsals cannot leave partial tables.
 */
public final class StagingManifestImporter {

    private static final int BATCH_ROWS = 500;

    private static final String COMMIT_UNKNOWN = "COMMIT_UNKNOWN";

    private static final String TARGET_COMMIT_PHASE = "TARGET";

    private static final String FINALIZATION_COMMIT_PHASE = "FINALIZATION";

    private static final List<String> REQUIRED_MYSQL_SQL_MODES = List.of(
            "STRICT_ALL_TABLES", "NO_ZERO_DATE", "NO_ZERO_IN_DATE");

    private final ConnectionFactory connectionFactory;

    public StagingManifestImporter() {
        this(ConnectionPool::createNewConnection);
    }

    StagingManifestImporter(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public static boolean required(ImportTaskSpec spec, ImportManifest manifest) {
        if (spec == null || manifest == null) {
            return false;
        }
        return requested(spec)
                || manifest.getDependencyPlan() != null
                    && (manifest.getDependencyPlan().isStagingRequired()
                        || manifest.getDependencyPlan().isCycleResolutionRequired())
                || manifest.getMode() == ai.chat2db.community.domain.api.model.task.ImportPlanMode.STAGING_FIRST;
    }

    public static boolean requested(ImportTaskSpec spec) {
        if (spec == null) {
            return false;
        }
        ImportStagingPolicy staging = spec.getStagingPolicy();
        ImportRollbackOptions rollback = spec.getRollbackOptions();
        return Boolean.TRUE.equals(staging == null ? null : staging.getEnabled())
                || Boolean.TRUE.equals(staging == null ? null : staging.getTwoPhase())
                || Boolean.TRUE.equals(staging == null ? null : staging.getAllVarchar())
                || "THIRD_PARTY".equalsIgnoreCase(spec.getSourceKind())
                || Boolean.TRUE.equals(rollback == null ? null : rollback.getFullRollback())
                || Boolean.TRUE.equals(rollback == null ? null : rollback.getRehearsal());
    }

    public void execute(ImportTaskSpec spec, TaskExecutionContext context, ImportManifest manifest) {
        ImportManifestIntegrity.requireValid(manifest);
        ImportManifestBuilder.requireVersionedShardIdentities(manifest);
        requireRequest(spec, context, manifest);
        validatePhysicalDependencyIdentity(manifest);
        ConnectInfo parent = Chat2DBContext.getConnectInfo();
        if (parent == null) {
            throw new IllegalStateException("Database context is unavailable for staging import");
        }

        ConnectInfo isolated = parent.copy();
        isolated.setLoginUser(parent.getLoginUser());
        Connection connection = null;
        boolean originalAutoCommit = true;
        boolean transactionStarted = false;
        boolean targetCommitted = false;
        boolean commitOutcomeUnknown = false;
        boolean rolledBack = false;
        boolean finalizationRolledBack = false;
        boolean finalizationRollbackFailed = false;
        String uncertainCommitPhase = null;
        SessionControl sessionControl = SessionControl.NONE;
        ArtifactDraft reportDraft = createReportDraft(context, manifest);
        Map<String, Object> report = baseReport(spec, manifest);
        Map<String, Long> beforeRows = new LinkedHashMap<>();
        Map<TableIdentity, StageTable> stages = new LinkedHashMap<>();
        MutableLong taskRejects = new MutableLong();
        List<RejectWriter> rejectWriters = new ArrayList<>();
        long started = System.nanoTime();
        try {
            connection = connectionFactory.open(isolated);
            isolated.setConnection(connection);
            Chat2DBContext.putContext(isolated);
            originalAutoCommit = connection.getAutoCommit();
            DbFamily family = DbFamily.of(isolated.getDbType());
            validateAtomicStagingSafety(connection, spec, family, isolated.getDbType());
            sessionControl = configureSession(connection, context, family);
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            transactionStarted = true;
            IDbMetaData metadata = Chat2DBContext.getDbMetaData();
            if (family == DbFamily.MYSQL) {
                lockAndValidateMysqlTargets(connection, context, metadata, isolated, spec);
            }
            context.reportProgress(8, "STAGING", "Profiling import sources");

            Map<TableIdentity, ImportTaskSpec> sourceSpecs = sourceSpecs(spec);
            Map<TableIdentity, List<ImportManifestShard>> groupedShards = groupShards(manifest);
            preflightDependencyColumns(connection, metadata, manifest, sourceSpecs, groupedShards);
            preflightRollbackSafeGeneratedColumns(connection, metadata, spec, sourceSpecs, groupedShards);
            long maxErrors = maxErrors(sourceSpecs.values());
            int samplePercent = samplePercent(spec);
            for (Map.Entry<TableIdentity, List<ImportManifestShard>> entry : groupedShards.entrySet()) {
                ImportTaskSpec tableSpec = sourceSpecs.get(entry.getKey());
                if (tableSpec == null) {
                    throw new IllegalArgumentException("Manifest table has no submitted source: " + entry.getKey());
                }
                String tableKey = shardTableKey(entry.getValue().get(0));
                StageTable stage = stageTable(connection, context, metadata, family, tableSpec,
                        tableKey, entry.getKey(), entry.getValue());
                stages.put(entry.getKey(), stage);
                beforeRows.put(tableKey, queryLong(connection, context,
                        "SELECT COUNT(*) FROM " + stage.targetName));
                loadShards(connection, context, family, tableSpec, stage, entry.getValue(),
                        samplePercent, taskRejects, maxErrors, rejectWriters);
            }
            report.put("sourceProfiles", stages.values().stream().map(StageTable::profile).toList());
            report.put("samplePercent", samplePercent);
            report.put("rejectedRows", taskRejects.value);

            context.reportProgress(35, "VALIDATING", "Checking staged relationships");
            List<Map<String, Object>> orphanChecks = checkStagedOrphans(connection, context, manifest, stages);
            report.put("orphanChecks", orphanChecks);

            CycleControl cycle = cycleControl(connection, context, spec, manifest, stages, family);
            context.reportProgress(50, "IMPORTING", "Publishing staged tables");
            try {
                insertTargets(connection, context, manifest, stages, cycle.nullableCycleColumns,
                        taskRejects, maxErrors, rejectWriters);
                if (!cycle.nullableCycleColumns.isEmpty()) {
                    updateNullableCycles(connection, context, stages, cycle.nullableCycleColumns, family);
                }
                cycle.restoreBeforeValidation.run();
                report.put("postImportOrphanChecks",
                        checkTargetOrphans(connection, context, manifest, stages));
                report.put("sourceProfiles", stages.values().stream().map(StageTable::profile).toList());
                report.put("rejectedRows", taskRejects.value);

                Map<String, Long> afterRows = rowCounts(connection, context, stages);
                report.put("rowCounts", rowCountReport(stages, beforeRows, afterRows));
                report.put("checksums", checksumReport(connection, context, stages));
                report.put("indexes", indexReport(connection, metadata, stages));
                context.checkCancelled();

                boolean rehearsal = rehearsal(spec);
                if (!rehearsal) {
                    validatePreCommitSequenceSafety(connection, context, spec, stages, family);
                }
                if (rehearsal) {
                    connection.rollback();
                    rolledBack = true;
                    Map<String, Long> restored = rowCounts(connection, context, stages);
                    requireRestored(beforeRows, restored);
                    report.put("transaction", transactionReport("REHEARSAL_ROLLED_BACK", true, true));
                } else {
                    writeReport(reportDraft, report);
                    context.enterCommitPhase();
                    try {
                        connection.commit();
                    } catch (SQLException | RuntimeException commitFailure) {
                        commitOutcomeUnknown = true;
                        uncertainCommitPhase = TARGET_COMMIT_PHASE;
                        throw commitOutcomeUnknown(TARGET_COMMIT_PHASE, commitFailure);
                    }
                    targetCommitted = true;
                    report.put("transaction", transactionReport("COMMITTED", false, false));
                    if (finalizationRequested(spec)) {
                        List<Map<String, Object>> finalization = new ArrayList<>();
                        report.put("finalization", finalization);
                        try {
                            finalizeTargets(connection, context, spec, stages, family, finalization);
                        } catch (Throwable finalizationFailure) {
                            finalizationRolledBack = rollbackFinalization(
                                    connection, finalization, finalizationFailure);
                            finalizationRollbackFailed = !finalizationRolledBack;
                            report.put("finalizationRolledBack", finalizationRolledBack);
                            report.put("finalizationRollbackFailed", finalizationRollbackFailed);
                            throw finalizationFailure;
                        }
                        try {
                            connection.commit();
                        } catch (SQLException | RuntimeException commitFailure) {
                            commitOutcomeUnknown = true;
                            uncertainCommitPhase = FINALIZATION_COMMIT_PHASE;
                            throw commitOutcomeUnknown(FINALIZATION_COMMIT_PHASE, commitFailure);
                        }
                    }
                }
            } finally {
                if (!commitOutcomeUnknown) {
                    cycle.restoreQuietly.run();
                }
            }

            report.put("elapsedMillis", elapsedMillis(started));
            report.put("throughputRowsPerSecond", throughput(stages, started));
            writeReport(reportDraft, report);
            writeRejectSummary(context, spec, manifest, stages, taskRejects.value);
            context.logInfo("IMPORT_DRILL_REPORT", "Staging import report prepared", Map.of(
                    "rehearsal", rehearsal(spec), "rolledBack", rolledBack,
                    "tables", stages.size(), "rejectedRows", taskRejects.value));
            context.reportProgress(92, "FINALIZING", rehearsal(spec)
                    ? "Import rehearsal rolled back" : "Staging import finalized");
        } catch (Throwable failure) {
            if (connection != null && transactionStarted && !targetCommitted && !rolledBack
                    && !commitOutcomeUnknown) {
                try {
                    connection.rollback();
                    rolledBack = true;
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rolledBack", rolledBack);
            details.put("targetCommitted", targetCommitted);
            details.put("reason", rootMessage(failure));
            if (targetCommitted) {
                details.put("manualReconciliationRequired", true);
                details.put("retrySafe", false);
                details.put("finalizationRolledBack", finalizationRolledBack);
                details.put("finalizationRollbackFailed", finalizationRollbackFailed);
            }
            if (commitOutcomeUnknown) {
                details.put("commitOutcomeUnknown", true);
                details.put("commitPhase", uncertainCommitPhase);
                details.put("manualReconciliationRequired", true);
            }
            if (connection != null && rolledBack && !beforeRows.isEmpty()) {
                try {
                    Map<String, Long> restored = rowCountsByTarget(connection, context, spec, manifest);
                    details.put("rollbackVerified", countsMatch(beforeRows, restored));
                } catch (Throwable verificationFailure) {
                    details.put("rollbackVerified", false);
                    failure.addSuppressed(verificationFailure);
                }
            }
            String failureOutcome = commitOutcomeUnknown ? COMMIT_UNKNOWN
                    : targetCommitted ? "FAILED_AFTER_COMMIT"
                    : rolledBack ? "FAILED_ROLLED_BACK" : "FAILED_BEFORE_TRANSACTION";
            report.put("transaction", transactionReport(failureOutcome, rolledBack,
                    Boolean.TRUE.equals(details.get("rollbackVerified"))));
            report.put("failure", details);
            report.put("rejectedRows", taskRejects.value);
            if (!stages.isEmpty()) {
                report.put("sourceProfiles", stages.values().stream().map(StageTable::profile).toList());
            }
            report.put("elapsedMillis", elapsedMillis(started));
            try {
                writeReport(reportDraft, report);
            } catch (Throwable reportFailure) {
                failure.addSuppressed(reportFailure);
            }
            try {
                writeRejectSummary(context, spec, manifest, stages, taskRejects.value);
            } catch (Throwable summaryFailure) {
                failure.addSuppressed(summaryFailure);
            }
            try {
                context.logError(commitOutcomeUnknown ? "IMPORT_COMMIT_UNKNOWN"
                                : targetCommitted ? "IMPORT_FAILED_AFTER_COMMIT" : "IMPORT_ROLLBACK_COMPLETED",
                        commitOutcomeUnknown
                                ? "Staging import commit outcome is unknown; manually verify target data before retrying"
                                : targetCommitted ? "Post-import finalization failed after target commit"
                                        : "Staging import failed and was rolled back",
                        details);
            } catch (Throwable ignored) {
                // Preserve the original database failure when cancellation prevents event logging.
            }
            if (failure instanceof TaskExecutionException taskFailure) {
                throw taskFailure;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not execute staging import", failure);
        } finally {
            for (RejectWriter writer : rejectWriters) {
                writer.closeQuietly();
            }
            if (connection != null && !commitOutcomeUnknown && !finalizationRollbackFailed) {
                try {
                    sessionControl.restore.run();
                } catch (Throwable restoreFailure) {
                    logCleanupFailure(context, "STAGING_SESSION_RESTORE_FAILED",
                            "Could not restore the staging session before disposal", restoreFailure);
                }
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (Throwable restoreFailure) {
                    logCleanupFailure(context, "STAGING_AUTOCOMMIT_RESTORE_FAILED",
                            "Could not restore auto-commit before disposing the staging connection",
                            restoreFailure);
                }
            }
            try {
                discardDedicatedConnection(isolated, connection, context);
            } finally {
                Chat2DBContext.putContext(parent);
            }
        }
    }

    private static void requireRequest(ImportTaskSpec spec, TaskExecutionContext context,
            ImportManifest manifest) {
        if (spec == null || context == null || context.taskId() == null
                || !context.taskId().equals(manifest.getTaskId())) {
            throw new IllegalArgumentException("Staging manifest identity does not match the running task");
        }
        if (!required(spec, manifest)) {
            throw new IllegalArgumentException("Staging import was not requested by this task");
        }
        if (manifest.getShards() == null || manifest.getShards().isEmpty()) {
            throw new IllegalArgumentException("Staging import requires manifest shards");
        }
    }

    private static void validateAtomicStagingSafety(Connection connection, ImportTaskSpec spec,
            DbFamily family, String dbType) throws SQLException {
        validateFinalizationCompatibility(spec, family, dbType);
        if (!connection.getMetaData().supportsTransactions()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Staging import requires JDBC transaction support");
        }
    }

    static void lockAndValidateMysqlTargets(Connection connection, TaskExecutionContext context,
            IDbMetaData metadata, ConnectInfo connectInfo, ImportTaskSpec spec)
            throws SQLException {
        Map<TableIdentity, MysqlTarget> targets = new LinkedHashMap<>();
        for (ImportTableSource source : ImportTaskSourceSupport.effectiveSources(spec)) {
            String database = firstNonBlank(source.getDatabaseName(), source.getSchemaName(),
                    spec.getTarget() == null ? null : spec.getTarget().getDatabaseName(),
                    spec.getTarget() == null ? null : spec.getTarget().getSchemaName(),
                    connectInfo.getDatabaseName(), connectInfo.getSchemaName());
            String table = source.getTableName();
            String target = StringUtils.defaultString(database) + "." + StringUtils.defaultString(table);
            if (StringUtils.isBlank(database) || StringUtils.isBlank(table)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "MySQL rollback safety requires a database and table for every target");
            }
            TableIdentity identity = TableIdentity.of(source);
            String qualifiedName = metadata.getQualifiedTableName(database, null, table);
            targets.putIfAbsent(identity, new MysqlTarget(database, table, target, qualifiedName));
        }
        List<MysqlTarget> ordered = targets.values().stream()
                .sorted(Comparator.comparing(MysqlTarget::qualifiedName)).toList();
        for (MysqlTarget target : ordered) {
            acquireMysqlMetadataLock(connection, context, target.qualifiedName());
        }
        for (MysqlTarget target : ordered) {
            requireTransactionalMysqlEngine(target.displayName(),
                    mysqlTableEngine(connection, context, target.database(), target.table()));
        }
    }

    private static void acquireMysqlMetadataLock(Connection connection, TaskExecutionContext context,
            String qualifiedTarget) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try (ResultSet ignored = statement.executeQuery(
                    "SELECT 1 FROM " + qualifiedTarget + " LIMIT 0")) {
                // Referencing the target holds MySQL's metadata lock until this transaction ends.
            } finally {
                context.onStatementClosed(statement);
            }
        }
    }

    static void validatePhysicalDependencyIdentity(ImportManifest manifest) {
        if (manifest == null || manifest.getDependencies() == null) {
            return;
        }
        Map<DependencyEndpoints, List<ImportTableDependency>> unnamedByEndpoints = manifest.getDependencies().stream()
                .filter(Objects::nonNull)
                .filter(dependency -> !dependency.isLogical())
                .filter(dependency -> StringUtils.isBlank(dependency.getConstraintName()))
                .collect(Collectors.groupingBy(dependency -> new DependencyEndpoints(
                                tableKey(dependency, true), tableKey(dependency, false)),
                        LinkedHashMap::new, Collectors.toList()));
        for (List<ImportTableDependency> candidates : unnamedByEndpoints.values()) {
            boolean ambiguous = candidates.size() > 1 || candidates.stream()
                    .anyMatch(dependency -> dependency.getKeySequence() == null
                            || dependency.getKeySequence() != 1);
            if (ambiguous) {
                ImportTableDependency first = candidates.get(0);
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Cannot safely identify unnamed composite foreign key between "
                                + tableKey(first, false) + " and " + tableKey(first, true)
                                + "; refusing constraint changes");
            }
        }
    }

    private static void validateFinalizationCompatibility(ImportTaskSpec spec, DbFamily family,
            String dbType) {
        ImportRollbackOptions rollback = spec == null ? null : spec.getRollbackOptions();
        ImportFinalizationOptions finalization = spec.getFinalizationOptions();
        boolean requested = Boolean.TRUE.equals(finalization == null ? null : finalization.getResetSequences())
                || Boolean.TRUE.equals(finalization == null ? null : finalization.getRebuildIndexes())
                || Boolean.TRUE.equals(finalization == null ? null : finalization.getRefreshStatistics());
        if (Boolean.TRUE.equals(rollback == null ? null : rollback.getRehearsal())) {
            return;
        }
        if (requested && Boolean.TRUE.equals(rollback == null ? null : rollback.getFullRollback())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Full rollback cannot be combined with post-commit sequence, index, or statistics maintenance");
        }
        if (!requested) {
            return;
        }
        if (StringUtils.containsIgnoreCase(dbType, "KINGBASE")) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Atomic post-import maintenance is not supported for Kingbase without verified sequence and maintenance semantics");
        }
        if (family == DbFamily.MYSQL) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Atomic post-import maintenance is not supported for MySQL because its maintenance statements commit implicitly");
        }
        if (family == DbFamily.H2 && (Boolean.TRUE.equals(finalization.getRebuildIndexes())
                || Boolean.TRUE.equals(finalization.getRefreshStatistics()))) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Atomic index or statistics maintenance is not supported for H2");
        }
    }

    private static boolean finalizationRequested(ImportTaskSpec spec) {
        ImportFinalizationOptions options = spec == null ? null : spec.getFinalizationOptions();
        return Boolean.TRUE.equals(options == null ? null : options.getResetSequences())
                || Boolean.TRUE.equals(options == null ? null : options.getRebuildIndexes())
                || Boolean.TRUE.equals(options == null ? null : options.getRefreshStatistics());
    }

    private static String mysqlTableEngine(Connection connection, TaskExecutionContext context,
            String database, String table) throws SQLException {
        PreparedStatement statement = prepare(connection, context,
                "SELECT ENGINE FROM information_schema.TABLES "
                        + "WHERE CAST(TABLE_SCHEMA AS BINARY)=CAST(? AS BINARY) "
                        + "AND CAST(TABLE_NAME AS BINARY)=CAST(? AS BINARY)");
        try {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not verify the MySQL storage engine for " + database + "." + table);
                }
                return rows.getString(1);
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    static void requireTransactionalMysqlEngine(String target, String engine) {
        if (!"INNODB".equalsIgnoreCase(StringUtils.trimToEmpty(engine))) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Atomic staging import is not supported for MySQL target " + target
                            + " with storage engine " + StringUtils.defaultIfBlank(engine, "UNKNOWN"));
        }
    }

    private static SessionControl configureSession(Connection connection, TaskExecutionContext context,
            DbFamily family) throws SQLException {
        if (family != DbFamily.MYSQL) {
            return SessionControl.NONE;
        }
        String originalMode = queryString(connection, context, "SELECT @@SESSION.sql_mode");
        String strictMode = strictSqlMode(originalMode);
        boolean changed = !strictMode.equals(originalMode);
        try {
            if (changed) {
                setMysqlSqlMode(connection, context, strictMode);
            }
            String effectiveMode = queryString(connection, context, "SELECT @@SESSION.sql_mode");
            if (REQUIRED_MYSQL_SQL_MODES.stream()
                    .anyMatch(required -> !containsSqlMode(effectiveMode, required))) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "MySQL staging requires strict numeric and date sql_mode settings");
            }
            return changed
                    ? new SessionControl(() -> setMysqlSqlMode(connection, context, originalMode))
                    : SessionControl.NONE;
        } catch (Throwable failure) {
            if (changed) {
                try {
                    setMysqlSqlMode(connection, context, originalMode);
                } catch (Throwable restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            if (failure instanceof SQLException sqlFailure) {
                throw sqlFailure;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SQLException("Could not configure strict MySQL staging mode", failure);
        }
    }

    static String strictSqlMode(String originalMode) {
        List<String> modes = new ArrayList<>();
        for (String mode : StringUtils.defaultString(originalMode).split(",")) {
            String normalized = mode.trim();
            if (!normalized.isEmpty() && modes.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
                modes.add(normalized);
            }
        }
        for (String required : REQUIRED_MYSQL_SQL_MODES) {
            if (modes.stream().noneMatch(mode -> required.equalsIgnoreCase(mode))) {
                modes.add(required);
            }
        }
        return String.join(",", modes);
    }

    private static boolean containsSqlMode(String modes, String expected) {
        return java.util.Arrays.stream(StringUtils.defaultString(modes).split(","))
                .map(String::trim)
                .anyMatch(mode -> expected.equalsIgnoreCase(mode));
    }

    private static void setMysqlSqlMode(Connection connection, TaskExecutionContext context, String sqlMode)
            throws SQLException {
        PreparedStatement statement = prepare(connection, context, "SET SESSION sql_mode=?");
        try {
            statement.setString(1, StringUtils.defaultString(sqlMode));
            statement.executeUpdate();
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private static String queryString(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw new SQLException("Query returned no row");
                }
                return StringUtils.defaultString(rows.getString(1));
            } finally {
                context.onStatementClosed(statement);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static Map<TableIdentity, ImportTaskSpec> sourceSpecs(ImportTaskSpec spec) {
        Map<TableIdentity, ImportTaskSpec> result = new LinkedHashMap<>();
        for (ImportTableSource source : ImportTaskSourceSupport.effectiveSources(spec)) {
            ImportTaskSpec tableSpec = ImportTaskSourceSupport.specForSource(spec, source);
            TableIdentity identity = TableIdentity.of(source);
            if (result.putIfAbsent(identity, tableSpec) != null) {
                throw new IllegalArgumentException("Duplicate import table source: "
                        + ImportTaskSourceSupport.tableKey(source));
            }
        }
        return result;
    }

    private static Map<TableIdentity, List<ImportManifestShard>> groupShards(ImportManifest manifest) {
        Map<TableIdentity, List<ImportManifestShard>> result = new LinkedHashMap<>();
        for (ImportManifestShard shard : manifest.getShards()) {
            result.computeIfAbsent(TableIdentity.of(shard), ignored -> new ArrayList<>()).add(shard);
        }
        result.values().forEach(shards -> shards.sort(Comparator.comparing(ImportManifestShard::getShardId)));
        return result;
    }

    private static String shardTableKey(ImportManifestShard shard) {
        return StringUtils.defaultIfBlank(shard.getTableKey(), ImportTaskSourceSupport.tableKey(
                shard.getDatabaseName(), shard.getSchemaName(), shard.getTableName()));
    }

    private static StageTable stageTable(Connection connection, TaskExecutionContext context,
            IDbMetaData metadata, DbFamily family, ImportTaskSpec tableSpec, String tableKey,
            TableIdentity identity, List<ImportManifestShard> shards) throws Exception {
        ImportManifestShard first = shards.get(0);
        TableMetadataRequest request = metadataRequest(tableSpec, first);
        String database = request.getDatabaseName();
        String schema = request.getSchemaName();
        String table = request.getTableName();
        List<TableColumn> columns = metadata.columns(connection, request);
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Target table has no importable columns: " + tableKey);
        }
        requireRollbackSafeGeneratedColumns(tableSpec, tableKey, columns);
        String targetName = metadata.getQualifiedTableName(database, schema, table);
        ISQLIdentifierProcessor identifiers = metadata.getSQLIdentifierProcessor();
        String suffix = identifierSuffix(tableKey);
        String rawTable = identifiers.quoteIdentifierAlways("c2d_raw_" + context.taskId() + "_" + suffix);
        String typedTable = identifiers.quoteIdentifierAlways("c2d_typed_" + context.taskId() + "_" + suffix);
        String typedShardColumn = internalColumnName(columns, "__c2d_shard_" + suffix);
        String typedRowColumn = internalColumnName(columns, "__c2d_row_" + suffix);

        List<String> header = header(shards.get(0));
        ImportColumnResolver.Resolution resolution = ImportColumnResolver.resolveForSpec(columns, header, tableSpec);
        ImportColumnResolver.validateForImport(columns, resolution, tableSpec);
        createRawTable(connection, context, family, identifiers, rawTable, header.size());
        createTypedTable(connection, context, family, identifiers, typedTable, targetName,
                typedShardColumn, typedRowColumn);
        List<PrimaryKey> primaryKeys = sortedPrimaryKeys(metadata.getPrimaryKeys(connection, request));
        Set<String> indexes = indexSnapshot(metadata.indexes(connection, request));
        return new StageTable(tableSpec, tableKey, identity, database, schema, table, targetName,
                rawTable, typedTable, typedShardColumn, typedRowColumn, columns, resolution,
                primaryKeys, indexes, header, shards);
    }

    private static String internalColumnName(List<TableColumn> columns, String candidate) {
        String result = candidate;
        while (containsColumn(columns, result)) {
            result = '_' + result;
        }
        return result;
    }

    private static boolean containsColumn(List<TableColumn> columns, String name) {
        return ImportColumnResolver.uniqueNameIndex(name,
                columns.stream().map(TableColumn::getName).toList(), "target column") >= 0;
    }

    private static void preflightDependencyColumns(Connection connection, IDbMetaData metadata,
            ImportManifest manifest, Map<TableIdentity, ImportTaskSpec> sourceSpecs,
            Map<TableIdentity, List<ImportManifestShard>> groupedShards) {
        if (manifest.getDependencies() == null || manifest.getDependencies().isEmpty()) {
            return;
        }
        Map<TableIdentity, List<String>> columnsByTable = new LinkedHashMap<>();
        for (Map.Entry<TableIdentity, List<ImportManifestShard>> entry : groupedShards.entrySet()) {
            ImportTaskSpec tableSpec = sourceSpecs.get(entry.getKey());
            if (tableSpec == null) {
                throw new IllegalArgumentException("Manifest table has no submitted source: " + entry.getKey());
            }
            List<TableColumn> columns = metadata.columns(connection,
                    metadataRequest(tableSpec, entry.getValue().get(0)));
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("Target table has no importable columns: "
                        + shardTableKey(entry.getValue().get(0)));
            }
            columnsByTable.put(entry.getKey(), columns.stream().map(TableColumn::getName).toList());
        }
        for (ImportTableDependency dependency : manifest.getDependencies()) {
            requireDependencyColumn(columnsByTable, TableIdentity.of(dependency, false),
                    dependency.getChildColumn(), dependency.isLogical(), "child");
            requireDependencyColumn(columnsByTable, TableIdentity.of(dependency, true),
                    dependency.getParentColumn(), dependency.isLogical(), "parent");
        }
    }

    private static void requireDependencyColumn(Map<TableIdentity, List<String>> columnsByTable,
            TableIdentity table, String column, boolean logical, String endpoint) {
        List<String> candidates = columnsByTable.get(table);
        if (candidates == null) {
            return;
        }
        String description = (logical ? "logical" : "physical")
                + " dependency " + endpoint + " column";
        if (ImportColumnResolver.uniqueNameIndex(column, candidates, description) < 0) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Dependency column is not present in " + table + ": " + column);
        }
    }

    private static void preflightRollbackSafeGeneratedColumns(Connection connection, IDbMetaData metadata,
            ImportTaskSpec spec, Map<TableIdentity, ImportTaskSpec> sourceSpecs,
            Map<TableIdentity, List<ImportManifestShard>> groupedShards) {
        if (!rollbackRequested(spec)) {
            return;
        }
        for (Map.Entry<TableIdentity, List<ImportManifestShard>> entry : groupedShards.entrySet()) {
            ImportTaskSpec tableSpec = sourceSpecs.get(entry.getKey());
            if (tableSpec == null) {
                throw new IllegalArgumentException("Manifest table has no submitted source: " + entry.getKey());
            }
            List<TableColumn> columns = metadata.columns(connection,
                    metadataRequest(tableSpec, entry.getValue().get(0)));
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("Target table has no importable columns: "
                        + shardTableKey(entry.getValue().get(0)));
            }
            requireRollbackSafeGeneratedColumns(tableSpec, shardTableKey(entry.getValue().get(0)), columns);
        }
    }

    private static TableMetadataRequest metadataRequest(ImportTaskSpec tableSpec, ImportManifestShard shard) {
        return new TableMetadataRequest(StringUtils.defaultIfBlank(shard.getDatabaseName(),
                tableSpec.getTarget().getDatabaseName()), StringUtils.defaultIfBlank(shard.getSchemaName(),
                tableSpec.getTarget().getSchemaName()), shard.getTableName());
    }

    private static void loadShards(Connection connection, TaskExecutionContext context, DbFamily family,
            ImportTaskSpec tableSpec, StageTable stage, List<ImportManifestShard> shards,
            int samplePercent, MutableLong taskRejects, long maxErrors,
            List<RejectWriter> rejectWriters) throws Throwable {
        for (ImportManifestShard shard : shards) {
            long sourceBefore = stage.sourceRows;
            long stagedBefore = stage.stagedRows;
            long rejectedBefore = stage.rejectedRows;
            try {
                loadShard(connection, context, family, tableSpec, stage, shard, samplePercent,
                        taskRejects, maxErrors, rejectWriters);
                stage.shardOutcomes.add(ShardOutcome.completed(shard,
                        stage.sourceRows - sourceBefore, stage.stagedRows - stagedBefore,
                        stage.rejectedRows - rejectedBefore));
            } catch (Throwable failure) {
                stage.shardOutcomes.add(ShardOutcome.failed(shard,
                        stage.sourceRows - sourceBefore, stage.stagedRows - stagedBefore,
                        stage.rejectedRows - rejectedBefore, rootMessage(failure)));
                throw failure;
            }
        }
    }

    private static List<String> header(ImportManifestShard shard) throws IOException {
        Path source = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
        try (CSVParser parser = CSVParser.parse(source, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
            var records = parser.iterator();
            if (!records.hasNext()) {
                throw new IllegalArgumentException("CSV shard requires a header: " + shard.getShardId());
            }
            return List.copyOf(records.next().toList());
        }
    }

    private static void createRawTable(Connection connection, TaskExecutionContext context, DbFamily family,
            ISQLIdentifierProcessor identifiers, String rawTable, int width) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE ").append(family.temporaryPrefix())
                .append(' ').append(rawTable).append(" (")
                .append(identifiers.quoteIdentifierAlways("__c2d_shard"))
                .append(" VARCHAR(255) NOT NULL,")
                .append(identifiers.quoteIdentifierAlways("__c2d_row")).append(" BIGINT NOT NULL");
        for (int index = 0; index < width; index++) {
            sql.append(',').append(identifiers.quoteIdentifierAlways("c" + index)).append(' ')
                    .append(family.textType());
        }
        sql.append(')');
        executeUpdate(connection, context, sql.toString());
    }

    private static void createTypedTable(Connection connection, TaskExecutionContext context,
            DbFamily family, ISQLIdentifierProcessor identifiers, String typedTable, String targetName,
            String typedShardColumn, String typedRowColumn) throws SQLException {
        String sql = switch (family) {
            case MYSQL -> "CREATE TEMPORARY TABLE " + typedTable + " LIKE " + targetName;
            case POSTGRESQL -> "CREATE TEMPORARY TABLE " + typedTable + " (LIKE " + targetName
                    + " INCLUDING DEFAULTS) ON COMMIT DROP";
            case H2 -> "CREATE LOCAL TEMPORARY TABLE " + typedTable
                    + " AS SELECT t.*,CAST(NULL AS VARCHAR(255)) AS "
                    + identifiers.quoteIdentifierAlways(typedShardColumn)
                    + ",CAST(NULL AS BIGINT) AS " + identifiers.quoteIdentifierAlways(typedRowColumn)
                    + " FROM " + targetName + " t WHERE 1=0";
        };
        executeUpdate(connection, context, sql);
        if (family != DbFamily.H2) {
            executeUpdate(connection, context, "ALTER TABLE " + typedTable + " ADD "
                    + identifiers.quoteIdentifierAlways(typedShardColumn) + " VARCHAR(255) NOT NULL");
            executeUpdate(connection, context, "ALTER TABLE " + typedTable + " ADD "
                    + identifiers.quoteIdentifierAlways(typedRowColumn) + " BIGINT NOT NULL");
        }
    }

    private static void loadShard(Connection connection, TaskExecutionContext context, DbFamily family,
            ImportTaskSpec tableSpec, StageTable stage, ImportManifestShard shard, int samplePercent,
            MutableLong taskRejects, long maxErrors, List<RejectWriter> rejectWriters) throws Exception {
        Path source = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
        CsvShardPreprocessor.ShardVerification verification = CsvShardPreprocessor.verify(source.toFile(), shard);
        long limit = samplePercent >= 100 ? Long.MAX_VALUE
                : Math.max(1L, (long) Math.ceil(verification.rows() * (samplePercent / 100.0D)));
        String rawSql = rawInsertSql(stage);
        String typedSql = typedInsertSql(stage, family);
        boolean skip = skipMode(tableSpec);
        RejectWriter rejectWriter = null;
        long acceptedInBatch = 0L;
        PreparedStatement raw = null;
        PreparedStatement typed = null;
        try (CSVParser parser = CSVParser.parse(source, StandardCharsets.UTF_8, CSVFormat.DEFAULT)) {
            raw = prepare(connection, context, rawSql);
            typed = prepare(connection, context, typedSql);
            var records = parser.iterator();
            if (!records.hasNext() || !stage.header.equals(records.next().toList())) {
                throw new IllegalArgumentException("CSV shard header changed after manifest preparation: "
                        + shard.getShardId());
            }
            long row = 0L;
            int batch = 0;
            while (records.hasNext() && row < limit) {
                context.checkCancelled();
                CSVRecord record = records.next();
                row++;
                stage.sourceRows++;
                if (record.size() != stage.header.size()) {
                    if (!skip) {
                        throw new IllegalArgumentException("CSV row width mismatch in shard "
                                + shard.getShardId() + " at row " + row);
                    }
                    rejectWriter = reject(rejectWriter, context, shard, source, rejectWriters);
                    rejectWriter.write(row, record.toList(), "column count does not match header");
                    rejected(stage, taskRejects, maxErrors);
                    continue;
                }
                stage.observe(record, tableSpec.getOptions());
                bindRaw(raw, shard.getShardId(), row, record);
                raw.addBatch();
                if (skip) {
                    Savepoint rowSavepoint = connection.setSavepoint();
                    try {
                        bindTyped(typed, tableSpec, stage, shard.getShardId(), row, record);
                        clearConversionWarnings(connection, typed, family);
                        typed.executeUpdate();
                        requireNoConversionWarnings(connection, typed, family);
                        stage.stagedRows++;
                        connection.releaseSavepoint(rowSavepoint);
                    } catch (SQLException conversionFailure) {
                        connection.rollback(rowSavepoint);
                        try {
                            connection.releaseSavepoint(rowSavepoint);
                        } catch (SQLException ignored) {
                            // Some drivers release a savepoint implicitly when rolling back to it.
                        }
                        rejectWriter = reject(rejectWriter, context, shard, source, rejectWriters);
                        rejectWriter.write(row, record.toList(), rootMessage(conversionFailure));
                        rejected(stage, taskRejects, maxErrors);
                    }
                } else {
                    bindTyped(typed, tableSpec, stage, shard.getShardId(), row, record);
                    typed.addBatch();
                    acceptedInBatch++;
                }
                if (++batch >= BATCH_ROWS) {
                    raw.executeBatch();
                    if (!skip) {
                        clearConversionWarnings(connection, typed, family);
                        typed.executeBatch();
                        requireNoConversionWarnings(connection, typed, family);
                        stage.stagedRows += acceptedInBatch;
                        acceptedInBatch = 0L;
                    }
                    batch = 0;
                }
            }
            if (batch > 0) {
                raw.executeBatch();
                if (!skip) {
                    clearConversionWarnings(connection, typed, family);
                    typed.executeBatch();
                    requireNoConversionWarnings(connection, typed, family);
                    stage.stagedRows += acceptedInBatch;
                }
            }
        } finally {
            closePreparedStatement(context, typed);
            closePreparedStatement(context, raw);
        }
    }

    private static void closePreparedStatement(TaskExecutionContext context, PreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException ignored) {
            // The connection lifecycle remains authoritative for statement cleanup.
        } finally {
            context.onStatementClosed(statement);
        }
    }

    private static PreparedStatement prepare(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        context.onStatementCreated(statement);
        return statement;
    }

    private static void bindRaw(PreparedStatement statement, String shardId, long row,
            CSVRecord record) throws SQLException {
        statement.setString(1, shardId);
        statement.setLong(2, row);
        for (int index = 0; index < record.size(); index++) {
            statement.setString(index + 3, record.get(index));
        }
    }

    private static void bindTyped(PreparedStatement statement, ImportTaskSpec spec, StageTable stage,
            String shardId, long row, CSVRecord record) throws SQLException {
        ImportOptions options = spec.getOptions();
        for (int index = 0; index < stage.resolution.tableColumns().size(); index++) {
            Integer sourceIndex = stage.resolution.fileIndexes().get(index);
            String value = sourceIndex == null ? null : record.get(sourceIndex);
            if (value == null || value.isEmpty()
                    || options != null && Objects.equals(options.getNullString(), value)) {
                statement.setNull(index + 1, Types.NULL);
            } else {
                statement.setString(index + 1, value);
            }
        }
        statement.setString(stage.resolution.tableColumns().size() + 1, shardId);
        statement.setLong(stage.resolution.tableColumns().size() + 2, row);
    }

    private static void clearConversionWarnings(Connection connection, Statement statement, DbFamily family)
            throws SQLException {
        if (family == DbFamily.MYSQL) {
            clearSqlWarnings(connection, statement);
        }
    }

    static void clearSqlWarnings(Connection connection, Statement statement) throws SQLException {
        statement.clearWarnings();
        connection.clearWarnings();
    }

    private static void requireNoConversionWarnings(Connection connection, Statement statement,
            DbFamily family) throws SQLException {
        if (family == DbFamily.MYSQL) {
            requireNoSqlWarnings(connection, statement);
        }
    }

    static void requireNoSqlWarnings(Connection connection, Statement statement) throws SQLException {
        SQLWarning warning = statement.getWarnings();
        if (warning == null) {
            warning = connection.getWarnings();
        }
        SQLException warningFailure = null;
        if (warning != null) {
            warningFailure = new SQLException("MySQL staging conversion warning: "
                    + StringUtils.defaultIfBlank(warning.getMessage(), "unspecified conversion warning"),
                    warning.getSQLState(), warning.getErrorCode());
            warningFailure.initCause(warning);
        }
        try {
            clearSqlWarnings(connection, statement);
        } catch (SQLException clearFailure) {
            if (warningFailure == null) {
                throw clearFailure;
            }
            warningFailure.addSuppressed(clearFailure);
        }
        if (warningFailure != null) {
            throw warningFailure;
        }
    }

    private static String rawInsertSql(StageTable stage) {
        List<String> columns = new ArrayList<>();
        columns.add(quoted(stage, "__c2d_shard"));
        columns.add(quoted(stage, "__c2d_row"));
        for (int index = 0; index < stage.header.size(); index++) {
            columns.add(quoted(stage, "c" + index));
        }
        return "INSERT INTO " + stage.rawTable + " (" + String.join(",", columns) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
    }

    private static String typedInsertSql(StageTable stage, DbFamily family) {
        List<String> columns = stage.resolution.tableColumns().stream()
                .map(TableColumn::getName).map(name -> quoted(stage, name)).toList();
        List<String> values = stage.resolution.tableColumns().stream()
                .map(column -> family.castPlaceholder(column.getColumnType())).toList();
        List<String> allColumns = new ArrayList<>(columns);
        allColumns.add(quoted(stage, stage.typedShardColumn));
        allColumns.add(quoted(stage, stage.typedRowColumn));
        List<String> allValues = new ArrayList<>(values);
        allValues.add("?");
        allValues.add("?");
        return "INSERT INTO " + stage.typedTable + " (" + String.join(",", allColumns) + ") VALUES ("
                + String.join(",", allValues) + ")";
    }

    private static RejectWriter reject(RejectWriter current, TaskExecutionContext context,
            ImportManifestShard shard, Path source, List<RejectWriter> writers) throws IOException {
        if (current != null) {
            return current;
        }
        RejectWriter created = new RejectWriter(context, shard, source);
        writers.add(created);
        return created;
    }

    private static void rejected(StageTable stage, MutableLong taskRejects, long maxErrors) {
        stage.rejectedRows++;
        taskRejects.value++;
        if (taskRejects.value > maxErrors) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import aborted after exceeding the task-wide rejected row limit of " + maxErrors);
        }
    }

    private static List<Map<String, Object>> checkStagedOrphans(Connection connection,
            TaskExecutionContext context, ImportManifest manifest, Map<TableIdentity, StageTable> stages)
            throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (manifest.getDependencies() == null || manifest.getDependencies().isEmpty()) {
            return result;
        }
        Map<DependencyGroup, List<ImportTableDependency>> groups = manifest.getDependencies().stream()
                .collect(Collectors.groupingBy(StagingManifestImporter::dependencyGroup,
                        LinkedHashMap::new, Collectors.toList()));
        for (List<ImportTableDependency> dependencies : groups.values()) {
            ImportTableDependency first = dependencies.get(0);
            StageTable child = stageForDependency(stages, first, false);
            StageTable parent = stageForDependency(stages, first, true);
            if (child == null) {
                continue;
            }
            boolean enabled = !first.isLogical() || validationEnabled(child.spec, "orphan")
                    || "THIRD_PARTY".equalsIgnoreCase(child.spec.getSourceKind());
            if (!enabled) {
                continue;
            }
            String parentTarget = parent == null
                    ? Chat2DBContext.getDbMetaData().getQualifiedTableName(first.getParentDatabaseName(),
                            first.getParentSchemaName(), first.getParentTable())
                    : parent.targetName;
            String childAlias = "c";
            List<String> nonNull = new ArrayList<>();
            List<String> targetMatch = new ArrayList<>();
            List<String> stageMatch = new ArrayList<>();
            for (ImportTableDependency edge : dependencies) {
                String childColumn = child.requiredColumnName(edge.getChildColumn());
                String parentColumn = parent == null
                        ? edge.getParentColumn() : parent.requiredColumnName(edge.getParentColumn());
                nonNull.add(childAlias + "." + quoted(child, childColumn) + " IS NOT NULL");
                targetMatch.add("p." + quoted(parent, parentColumn) + "=" + childAlias + "."
                        + quoted(child, childColumn));
                if (parent != null) {
                    stageMatch.add("s." + quoted(parent, parentColumn) + "=" + childAlias + "."
                            + quoted(child, childColumn));
                }
            }
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(child.typedTable)
                    .append(' ').append(childAlias).append(" WHERE ").append(String.join(" AND ", nonNull));
            if (parent != null) {
                sql.append(" AND NOT EXISTS (SELECT 1 FROM ").append(parent.typedTable)
                        .append(" s WHERE ").append(String.join(" AND ", stageMatch)).append(')');
            }
            sql.append(" AND NOT EXISTS (SELECT 1 FROM ").append(parentTarget)
                    .append(" p WHERE ").append(String.join(" AND ", targetMatch)).append(')');
            long orphans = queryLong(connection, context, sql.toString());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("constraint", StringUtils.defaultIfBlank(first.getConstraintName(), "LOGICAL"));
            item.put("parentTable", tableKey(first, true));
            item.put("childTable", tableKey(first, false));
            item.put("logical", first.isLogical());
            item.put("orphanRows", orphans);
            result.add(item);
            if (orphans > 0L) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Staging orphan check failed for " + tableKey(first, false));
            }
        }
        return result;
    }

    private static List<Map<String, Object>> checkTargetOrphans(Connection connection,
            TaskExecutionContext context, ImportManifest manifest, Map<TableIdentity, StageTable> stages)
            throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (manifest.getDependencies() == null || manifest.getDependencies().isEmpty()) {
            return result;
        }
        Map<DependencyGroup, List<ImportTableDependency>> groups = manifest.getDependencies().stream()
                .collect(Collectors.groupingBy(StagingManifestImporter::dependencyGroup,
                        LinkedHashMap::new, Collectors.toList()));
        for (List<ImportTableDependency> dependencies : groups.values()) {
            ImportTableDependency first = dependencies.get(0);
            StageTable child = stageForDependency(stages, first, false);
            StageTable parent = stageForDependency(stages, first, true);
            if (child == null) {
                continue;
            }
            boolean enabled = !first.isLogical() || validationEnabled(child.spec, "orphan")
                    || "THIRD_PARTY".equalsIgnoreCase(child.spec.getSourceKind());
            if (!enabled) {
                continue;
            }
            String parentTarget = parent == null
                    ? Chat2DBContext.getDbMetaData().getQualifiedTableName(first.getParentDatabaseName(),
                            first.getParentSchemaName(), first.getParentTable())
                    : parent.targetName;
            List<String> importedMatch = child.resolution.tableColumns().stream()
                    .map(TableColumn::getName)
                    .map(column -> nullSafeEquals("s." + quoted(child, column),
                            "c." + quoted(child, column)))
                    .toList();
            if (importedMatch.isEmpty()) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Post-import orphan check cannot identify imported target rows for "
                                + child.tableKey);
            }
            List<String> nonNull = new ArrayList<>();
            List<String> targetMatch = new ArrayList<>();
            for (ImportTableDependency edge : dependencies) {
                String childColumn = child.requiredColumnName(edge.getChildColumn());
                String parentColumn = parent == null
                        ? edge.getParentColumn() : parent.requiredColumnName(edge.getParentColumn());
                nonNull.add("c." + quoted(child, childColumn) + " IS NOT NULL");
                targetMatch.add("p." + quoted(parent, parentColumn) + "=c."
                        + quoted(child, childColumn));
            }
            String sql = "SELECT COUNT(*) FROM " + child.targetName + " c WHERE EXISTS (SELECT 1 FROM "
                    + child.typedTable + " s WHERE " + String.join(" AND ", importedMatch) + ") AND "
                    + String.join(" AND ", nonNull) + " AND NOT EXISTS (SELECT 1 FROM " + parentTarget
                    + " p WHERE " + String.join(" AND ", targetMatch) + ")";
            long orphans = queryLong(connection, context, sql);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("constraint", StringUtils.defaultIfBlank(first.getConstraintName(), "LOGICAL"));
            item.put("parentTable", tableKey(first, true));
            item.put("childTable", tableKey(first, false));
            item.put("logical", first.isLogical());
            item.put("orphanRows", orphans);
            item.put("source", "TARGET");
            result.add(item);
            if (orphans > 0L) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Post-import orphan check failed for " + tableKey(first, false));
            }
        }
        return result;
    }

    private static String nullSafeEquals(String left, String right) {
        return "(" + left + '=' + right + " OR (" + left + " IS NULL AND " + right + " IS NULL))";
    }

    private static CycleControl cycleControl(Connection connection, TaskExecutionContext context,
            ImportTaskSpec spec, ImportManifest manifest, Map<TableIdentity, StageTable> stages, DbFamily family)
            throws SQLException {
        if (!hasCycles(manifest.getDependencyPlan())) {
            return CycleControl.NONE;
        }
        String strategy = StringUtils.defaultIfBlank(spec.getCycleStrategy(), "REJECT").toUpperCase(Locale.ROOT);
        if ("REJECT".equals(strategy)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Cyclic dependencies require DEFER_CONSTRAINTS or STAGING_TWO_PHASE");
        }
        List<ImportTableDependency> cyclicPhysical = cyclicPhysicalDependencies(manifest);
        if (cyclicPhysical.isEmpty()) {
            return CycleControl.NONE;
        }
        if ("DEFER_CONSTRAINTS".equals(strategy)) {
            if (family != DbFamily.POSTGRESQL || !allDeferrable(cyclicPhysical)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "The selected cyclic constraints cannot be deferred by this database");
            }
            return deferConstraints(connection, context);
        }
        if (!"STAGING_TWO_PHASE".equals(strategy)) {
            throw new IllegalArgumentException("Unknown import cycle strategy: " + strategy);
        }
        if (family == DbFamily.MYSQL) {
            long oldValue = queryLong(connection, context, "SELECT @@FOREIGN_KEY_CHECKS");
            executeUpdate(connection, context, "SET FOREIGN_KEY_CHECKS=0");
            CheckedRunnable restore = () -> executeUpdate(connection, context,
                    "SET FOREIGN_KEY_CHECKS=" + oldValue);
            return new CycleControl(Map.of(), restore, () -> runQuietly(restore));
        }
        if (family == DbFamily.POSTGRESQL && allDeferrable(cyclicPhysical)) {
            return deferConstraints(connection, context);
        }
        Map<TableIdentity, Set<String>> nullableColumns = new LinkedHashMap<>();
        for (ImportTableDependency dependency : cyclicPhysical) {
            StageTable child = stageForDependency(stages, dependency, false);
            if (child == null) {
                continue;
            }
            TableColumn column = child.column(dependency.getChildColumn());
            if (column == null || Integer.valueOf(0).equals(column.getNullable())) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Non-deferrable cyclic foreign key is not nullable: " + child.table + "."
                                + dependency.getChildColumn());
            }
            nullableColumns.computeIfAbsent(child.identity, ignored -> new LinkedHashSet<>())
                    .add(column.getName());
        }
        return new CycleControl(nullableColumns, CheckedRunnable.NOOP, CheckedRunnable.NOOP);
    }

    private static CycleControl deferConstraints(Connection connection, TaskExecutionContext context)
            throws SQLException {
        executeUpdate(connection, context, "SET CONSTRAINTS ALL DEFERRED");
        CheckedRunnable validate = () -> executeUpdate(connection, context, "SET CONSTRAINTS ALL IMMEDIATE");
        return new CycleControl(Map.of(), validate, CheckedRunnable.NOOP);
    }

    private static void insertTargets(Connection connection, TaskExecutionContext context,
            ImportManifest manifest, Map<TableIdentity, StageTable> stages,
            Map<TableIdentity, Set<String>> nullableCycleColumns, MutableLong taskRejects, long maxErrors,
            List<RejectWriter> rejectWriters) throws Exception {
        for (StageTable stage : orderedStages(manifest, stages)) {
            Set<String> nullColumns = nullableCycleColumns.getOrDefault(
                    stage.identity, Set.of());
            if (skipMode(stage.spec)) {
                publishRowsWithRejects(connection, context, stage, nullColumns,
                        taskRejects, maxErrors, rejectWriters);
                continue;
            }
            List<String> columns = stage.resolution.tableColumns().stream()
                    .map(TableColumn::getName).map(name -> quoted(stage, name)).toList();
            List<String> values = stage.resolution.tableColumns().stream().map(TableColumn::getName)
                    .map(name -> containsColumnName(nullColumns, name) ? "NULL" : quoted(stage, name)).toList();
            String sql = "INSERT INTO " + stage.targetName + " (" + String.join(",", columns)
                    + ") SELECT " + String.join(",", values) + " FROM " + stage.typedTable;
            stage.insertedRows = executeUpdate(connection, context, sql);
        }
    }

    private static void publishRowsWithRejects(Connection connection, TaskExecutionContext context,
            StageTable stage, Set<String> nullColumns, MutableLong taskRejects, long maxErrors,
            List<RejectWriter> rejectWriters) throws Exception {
        List<String> columnNames = stage.resolution.tableColumns().stream()
                .map(TableColumn::getName).toList();
        List<String> selected = columnNames.stream().map(name -> quoted(stage, name))
                .collect(Collectors.toCollection(ArrayList::new));
        selected.add(quoted(stage, stage.typedShardColumn));
        selected.add(quoted(stage, stage.typedRowColumn));
        String selectSql = "SELECT " + String.join(",", selected) + " FROM " + stage.typedTable
                + " ORDER BY " + quoted(stage, stage.typedShardColumn) + ','
                + quoted(stage, stage.typedRowColumn);
        String insertSql = "INSERT INTO " + stage.targetName + " ("
                + columnNames.stream().map(name -> quoted(stage, name)).collect(Collectors.joining(","))
                + ") VALUES (" + String.join(",",
                        java.util.Collections.nCopies(columnNames.size(), "?")) + ')';
        PreparedStatement select = null;
        PreparedStatement insert = null;
        try {
            select = prepare(connection, context, selectSql);
            insert = prepare(connection, context, insertSql);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    context.checkCancelled();
                    String shardId = rows.getString(columnNames.size() + 1);
                    long row = rows.getLong(columnNames.size() + 2);
                    Savepoint savepoint = connection.setSavepoint();
                    try {
                        for (int index = 0; index < columnNames.size(); index++) {
                            if (containsColumnName(nullColumns, columnNames.get(index))) {
                                insert.setNull(index + 1, Types.NULL);
                            } else {
                                insert.setObject(index + 1, rows.getObject(index + 1));
                            }
                        }
                        insert.executeUpdate();
                    } catch (SQLException publishFailure) {
                        rollbackRow(connection, savepoint, publishFailure);
                        ImportManifestShard shard = stage.shardsById.get(shardId);
                        if (shard == null) {
                            throw new IllegalStateException(
                                    "Typed staging row references an unknown shard: " + shardId,
                                    publishFailure);
                        }
                        Path source = Path.of(shard.getSourcePath()).toAbsolutePath().normalize();
                        RejectWriter writer = rejectForShard(context, shard, source, rejectWriters);
                        writer.write(row, rawValues(connection, context, stage, shardId, row),
                                rootMessage(publishFailure));
                        deleteTypedRow(connection, context, stage, shardId, row);
                        stage.recordPublishReject(shardId);
                        rejected(stage, taskRejects, maxErrors);
                        continue;
                    }
                    connection.releaseSavepoint(savepoint);
                    stage.insertedRows++;
                }
            }
        } finally {
            closePreparedStatement(context, insert);
            closePreparedStatement(context, select);
        }
    }

    private static void rollbackRow(Connection connection, Savepoint savepoint,
            SQLException publishFailure) throws SQLException {
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            publishFailure.addSuppressed(rollbackFailure);
            throw publishFailure;
        }
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException ignored) {
            // Some drivers release a savepoint implicitly when rolling back to it.
        }
    }

    private static List<String> rawValues(Connection connection, TaskExecutionContext context,
            StageTable stage, String shardId, long row) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int index = 0; index < stage.header.size(); index++) {
            columns.add(quoted(stage, "c" + index));
        }
        PreparedStatement statement = prepare(connection, context, "SELECT " + String.join(",", columns)
                + " FROM " + stage.rawTable + " WHERE " + quoted(stage, "__c2d_shard") + "=? AND "
                + quoted(stage, "__c2d_row") + "=?");
        try {
            statement.setString(1, shardId);
            statement.setLong(2, row);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Raw staging provenance is missing for " + shardId + " row " + row);
                }
                List<String> values = new ArrayList<>();
                for (int index = 0; index < columns.size(); index++) {
                    values.add(rows.getString(index + 1));
                }
                return values;
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private static void deleteTypedRow(Connection connection, TaskExecutionContext context,
            StageTable stage, String shardId, long row) throws SQLException {
        PreparedStatement statement = prepare(connection, context, "DELETE FROM " + stage.typedTable
                + " WHERE " + quoted(stage, stage.typedShardColumn) + "=? AND "
                + quoted(stage, stage.typedRowColumn) + "=?");
        try {
            statement.setString(1, shardId);
            statement.setLong(2, row);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not remove rejected typed staging row");
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private static RejectWriter rejectForShard(TaskExecutionContext context, ImportManifestShard shard,
            Path source, List<RejectWriter> writers) throws IOException {
        for (RejectWriter writer : writers) {
            if (writer.shardId.equals(shard.getShardId())) {
                return writer;
            }
        }
        RejectWriter created = new RejectWriter(context, shard, source);
        writers.add(created);
        return created;
    }

    private static List<StageTable> orderedStages(ImportManifest manifest,
            Map<TableIdentity, StageTable> stages) {
        List<StageTable> result = new ArrayList<>();
        Set<TableIdentity> seen = new HashSet<>();
        Map<TableIdentity, StageTable> stagesByManifestKey = new LinkedHashMap<>();
        for (StageTable stage : stages.values()) {
            if (stagesByManifestKey.putIfAbsent(stage.identity, stage) != null) {
                throw new IllegalArgumentException("Duplicate manifest table key: " + stage.tableKey);
            }
        }
        ImportDependencyPlan plan = manifest.getDependencyPlan();
        if (plan != null && plan.getLayers() != null) {
            for (List<String> layer : plan.getLayers()) {
                for (String key : layer) {
                    StageTable stage = stagesByManifestKey.get(new TableIdentity(key));
                    if (stage != null && seen.add(stage.identity)) {
                        result.add(stage);
                    }
                }
            }
        }
        stages.values().stream().sorted(Comparator.comparing(value -> value.tableKey))
                .filter(value -> seen.add(value.identity)).forEach(result::add);
        return result;
    }

    private static void updateNullableCycles(Connection connection, TaskExecutionContext context,
            Map<TableIdentity, StageTable> stages, Map<TableIdentity, Set<String>> nullableColumns,
            DbFamily family)
            throws SQLException {
        for (Map.Entry<TableIdentity, Set<String>> entry : nullableColumns.entrySet()) {
            StageTable stage = stages.get(entry.getKey());
            List<PrimaryKey> keys = stage.primaryKeys;
            if (keys.isEmpty() || keys.stream().anyMatch(key -> !stage.hasInsertedColumn(key.getColumnName()))) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Two-phase cyclic update requires mapped primary keys for " + stage.tableKey);
            }
            String join = keys.stream().map(PrimaryKey::getColumnName)
                    .map(name -> "t." + quoted(stage, name) + "=s." + quoted(stage, name))
                    .collect(Collectors.joining(" AND "));
            String assignments = entry.getValue().stream().sorted(String.CASE_INSENSITIVE_ORDER)
                    .map(name -> "t." + quoted(stage, name) + "=s." + quoted(stage, name))
                    .collect(Collectors.joining(","));
            String sql;
            if (family == DbFamily.POSTGRESQL) {
                sql = "UPDATE " + stage.targetName + " t SET " + assignments
                        + " FROM " + stage.typedTable + " s WHERE " + join;
            } else if (family == DbFamily.MYSQL) {
                sql = "UPDATE " + stage.targetName + " t JOIN " + stage.typedTable
                        + " s ON " + join + " SET " + assignments;
            } else {
                List<String> genericAssignments = entry.getValue().stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .map(name -> quoted(stage, name) + "=(SELECT s." + quoted(stage, name)
                                + " FROM " + stage.typedTable + " s WHERE " + join + ")")
                        .toList();
                sql = "UPDATE " + stage.targetName + " t SET " + String.join(",", genericAssignments)
                        + " WHERE EXISTS (SELECT 1 FROM " + stage.typedTable + " s WHERE " + join + ")";
            }
            executeUpdate(connection, context, sql);
        }
    }

    private static List<Map<String, Object>> rowCountReport(Map<TableIdentity, StageTable> stages,
            Map<String, Long> before, Map<String, Long> after) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StageTable stage : stages.values()) {
            long beforeCount = before.getOrDefault(stage.tableKey, 0L);
            long afterCount = after.getOrDefault(stage.tableKey, 0L);
            long delta = afterCount - beforeCount;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", stage.tableKey);
            item.put("before", beforeCount);
            item.put("after", afterCount);
            item.put("delta", delta);
            item.put("expectedDelta", stage.insertedRows);
            item.put("matched", delta == stage.insertedRows);
            result.add(item);
            if (validationEnabled(stage.spec, "rows") && delta != stage.insertedRows) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Post-import row count mismatch for " + stage.tableKey);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> checksumReport(Connection connection,
            TaskExecutionContext context, Map<TableIdentity, StageTable> stages)
            throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StageTable stage : stages.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", stage.tableKey);
            if (!validationEnabled(stage.spec, "checksum")) {
                item.put("status", "NOT_REQUESTED");
                result.add(item);
                continue;
            }
            if (stage.primaryKeys.isEmpty()
                    || stage.primaryKeys.stream().anyMatch(key -> !stage.hasInsertedColumn(key.getColumnName()))) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Requested content checksum requires mapped primary keys for " + stage.tableKey);
            }
            List<String> columns = stage.resolution.tableColumns().stream()
                    .map(TableColumn::getName).toList();
            String order = stage.primaryKeys.stream().map(PrimaryKey::getColumnName)
                    .map(name -> quoted(stage, name)).collect(Collectors.joining(","));
            String stagedSql = "SELECT " + quotedList(stage, columns, null) + " FROM "
                    + stage.typedTable + " ORDER BY " + order;
            String join = stage.primaryKeys.stream().map(PrimaryKey::getColumnName)
                    .map(name -> "t." + quoted(stage, name) + "=s." + quoted(stage, name))
                    .collect(Collectors.joining(" AND "));
            String targetOrder = stage.primaryKeys.stream().map(PrimaryKey::getColumnName)
                    .map(name -> "t." + quoted(stage, name)).collect(Collectors.joining(","));
            String targetSql = "SELECT " + quotedList(stage, columns, "t") + " FROM "
                    + stage.targetName + " t JOIN " + stage.typedTable + " s ON " + join
                    + " ORDER BY " + targetOrder;
            DigestResult staged = digest(connection, context, stagedSql, columns.size());
            DigestResult target = digest(connection, context, targetSql, columns.size());
            boolean matched = staged.rows == target.rows && staged.sha256.equals(target.sha256);
            item.put("status", matched ? "MATCHED" : "MISMATCHED");
            item.put("rows", target.rows);
            item.put("stagingSha256", staged.sha256);
            item.put("targetSha256", target.sha256);
            result.add(item);
            if (!matched) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Post-import content checksum mismatch for " + stage.tableKey);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> indexReport(Connection connection, IDbMetaData metadata,
            Map<TableIdentity, StageTable> stages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StageTable stage : stages.values()) {
            Set<String> after = indexSnapshot(metadata.indexes(connection,
                    new TableMetadataRequest(stage.database, stage.schema, stage.table)));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", stage.tableKey);
            item.put("before", stage.indexesBefore.size());
            item.put("after", after.size());
            item.put("matched", stage.indexesBefore.equals(after));
            result.add(item);
            if (!stage.indexesBefore.equals(after)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Target index definitions changed during import for " + stage.tableKey);
            }
        }
        return result;
    }

    private static void finalizeTargets(Connection connection,
            TaskExecutionContext context, ImportTaskSpec spec, Map<TableIdentity, StageTable> stages,
            DbFamily family, List<Map<String, Object>> result) {
        ImportFinalizationOptions options = spec.getFinalizationOptions();
        List<FinalizationTarget> targets = new ArrayList<>();
        for (StageTable stage : stages.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", stage.tableKey);
            item.put("sequenceReset", requested(options == null ? null : options.getResetSequences())
                    ? "PENDING" : "NOT_REQUESTED");
            item.put("indexesRebuilt", requested(options == null ? null : options.getRebuildIndexes())
                    ? "PENDING" : "NOT_REQUESTED");
            item.put("statisticsRefreshed", requested(options == null ? null : options.getRefreshStatistics())
                    ? "PENDING" : "NOT_REQUESTED");
            result.add(item);
            targets.add(new FinalizationTarget(stage, item));
        }
        for (FinalizationTarget target : targets) {
            StageTable stage = target.stage();
            Map<String, Object> item = target.result();
            if (family == DbFamily.H2 && requested(options == null ? null : options.getResetSequences())) {
                item.put("sequenceReset", "ALREADY_SAFE");
            } else {
                maintenance(item, "sequenceReset", () -> resetSequences(connection, context, stage, family),
                        Boolean.TRUE.equals(options == null ? null : options.getResetSequences()), context,
                        "IMPORT_SEQUENCE_RESET_FAILED", "sequence reset", stage.tableKey);
            }
            maintenance(item, "indexesRebuilt", () -> rebuildIndexes(connection, context, stage, family),
                    Boolean.TRUE.equals(options == null ? null : options.getRebuildIndexes()), context,
                    "IMPORT_INDEX_REBUILD_FAILED", "index rebuild", stage.tableKey);
            maintenance(item, "statisticsRefreshed", () -> refreshStatistics(connection, context, stage, family),
                    Boolean.TRUE.equals(options == null ? null : options.getRefreshStatistics()), context,
                    "IMPORT_STATISTICS_FAILED", "statistics refresh", stage.tableKey);
        }
    }

    private static boolean requested(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static void markFinalizationRolledBack(List<Map<String, Object>> finalization) {
        for (Map<String, Object> item : finalization) {
            for (String key : List.of("sequenceReset", "indexesRebuilt", "statisticsRefreshed")) {
                Object status = item.get(key);
                if ("COMPLETED".equals(status)) {
                    item.put(key, "ROLLED_BACK");
                } else if ("PENDING".equals(status)) {
                    item.put(key, "NOT_ATTEMPTED");
                }
            }
        }
    }

    static boolean rollbackFinalization(Connection connection,
            List<Map<String, Object>> finalization, Throwable failure) {
        try {
            connection.rollback();
            markFinalizationRolledBack(finalization);
            return true;
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private static void validatePreCommitSequenceSafety(Connection connection,
            TaskExecutionContext context, ImportTaskSpec spec, Map<TableIdentity, StageTable> stages,
            DbFamily family) throws SQLException {
        ImportFinalizationOptions options = spec.getFinalizationOptions();
        if (family != DbFamily.H2
                || !requested(options == null ? null : options.getResetSequences())) {
            return;
        }
        for (StageTable stage : stages.values()) {
            for (TableColumn column : generatedColumns(stage)) {
                long maximum = queryLong(connection, context, "SELECT COALESCE(MAX("
                        + quoted(stage, column.getName()) + "),0) FROM " + stage.targetName);
                long requiredNext;
                try {
                    requiredNext = Math.max(1L, Math.addExact(maximum, 1L));
                } catch (ArithmeticException overflow) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                            "H2 identity value is too large to advance safely for " + stage.tableKey,
                            overflow);
                }
                PreparedStatement statement = prepare(connection, context,
                        "SELECT IDENTITY_BASE,IDENTITY_INCREMENT,IDENTITY_CYCLE "
                                + "FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?");
                try {
                    statement.setString(1, stage.schema);
                    statement.setString(2, stage.table);
                    statement.setString(3, column.getName());
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                                    "Could not verify H2 identity state for " + stage.tableKey + "."
                                            + column.getName());
                        }
                        long identityBase = rows.getLong(1);
                        boolean identityBaseNull = rows.wasNull();
                        long increment = rows.getLong(2);
                        boolean incrementNull = rows.wasNull();
                        boolean cycle = rows.getBoolean(3);
                        boolean cycleNull = rows.wasNull();
                        if (identityBaseNull || incrementNull || cycleNull
                                || increment <= 0L || cycle || identityBase < requiredNext) {
                            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                                    "H2 identity cannot be reset without risking a sequence regression for "
                                            + stage.tableKey + "." + column.getName());
                        }
                    }
                } finally {
                    closePreparedStatement(context, statement);
                }
            }
        }
    }

    private static void maintenance(Map<String, Object> item, String resultKey, CheckedRunnable action,
            boolean requested, TaskExecutionContext context, String code, String operation, String table) {
        if (!requested) {
            item.put(resultKey, "NOT_REQUESTED");
            return;
        }
        try {
            action.run();
            item.put(resultKey, "COMPLETED");
        } catch (Throwable failure) {
            item.put(resultKey, "FAILED: " + rootMessage(failure));
            context.logWarn(code, "Post-import maintenance failed", Map.of(
                    "table", table, "reason", rootMessage(failure)));
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "Target data was committed, but requested post-import maintenance failed",
                    "Do not retry the import blindly; reconcile " + operation + " for " + table,
                    failure);
        }
    }

    private static void resetSequences(Connection connection, TaskExecutionContext context,
            StageTable stage, DbFamily family) throws SQLException {
        if (family == DbFamily.H2) {
            return;
        }
        if (family != DbFamily.POSTGRESQL) {
            throw new SQLException("Atomic sequence reset is not supported for " + family);
        }
        List<TableColumn> generated = generatedColumns(stage);
        if (generated.isEmpty()) {
            return;
        }
        executeUpdate(connection, context,
                "LOCK TABLE " + stage.targetName + " IN SHARE ROW EXCLUSIVE MODE");
        for (TableColumn column : generated) {
            resetPostgresqlSequence(connection, context, stage.targetName, stage.tableKey,
                    column.getName(), quoted(stage, column.getName()));
        }
    }

    private static List<TableColumn> generatedColumns(StageTable stage) {
        return stage.columns.stream().filter(column -> Boolean.TRUE.equals(column.getAutoIncrement())).toList();
    }

    static void resetPostgresqlSequence(Connection connection, TaskExecutionContext context,
            String targetName, String tableKey, String columnName, String quotedColumn) throws SQLException {
        String sequence = preparedString(connection, context, "SELECT pg_get_serial_sequence(?,?)",
                statement -> {
                    statement.setString(1, targetName);
                    statement.setString(2, columnName);
                });
        if (StringUtils.isBlank(sequence)) {
            return;
        }
        PostgresqlSequenceIdentity identity = postgresqlSequenceIdentity(
                connection, context, sequence);
        if (identity == null) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "Could not resolve the owned PostgreSQL sequence for " + tableKey + "."
                            + columnName);
        }

        executeUpdate(connection, context,
                "ALTER SEQUENCE " + identity.qualifiedName() + " OWNER TO " + identity.owner());
        PostgresqlSequenceIdentity lockedIdentity = postgresqlSequenceIdentity(
                connection, context, sequence);
        if (!identity.equals(lockedIdentity)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "PostgreSQL sequence metadata changed while resetting " + tableKey + "."
                            + columnName);
        }
        String lockedOwner = preparedString(connection, context,
                "SELECT CAST(pg_get_serial_sequence(?,?) AS regclass)::oid::text",
                statement -> {
                    statement.setString(1, targetName);
                    statement.setString(2, columnName);
                });
        if (!Objects.equals(identity.oid(), lockedOwner)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "PostgreSQL sequence ownership changed while resetting " + tableKey + "."
                            + columnName);
        }
        PostgresqlSequenceParameters parameters = postgresqlSequenceParameters(
                connection, context, sequence, identity.qualifiedName());
        if (parameters.increment() <= 0L || parameters.cycle()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "Only ascending non-cycling PostgreSQL sequences can be reset safely for "
                            + tableKey + "." + columnName);
        }
        long maximum = queryLong(connection, context, "SELECT COALESCE(MAX("
                + quotedColumn + "),0) FROM " + targetName);
        long tableNext = checkedSequenceAdd(maximum, 1L, tableKey, columnName);
        long sequenceNext = parameters.called()
                ? checkedSequenceAdd(parameters.lastValue(), parameters.increment(), tableKey, columnName)
                : parameters.lastValue();
        long restart = Math.max(Math.max(1L, tableNext), sequenceNext);
        if (restart > parameters.maximum()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "PostgreSQL sequence maximum would be exceeded for " + tableKey + "."
                            + columnName);
        }
        executeUpdate(connection, context, "ALTER SEQUENCE " + identity.qualifiedName()
                + " RESTART WITH " + restart);
    }

    private static PostgresqlSequenceIdentity postgresqlSequenceIdentity(Connection connection,
            TaskExecutionContext context, String sequence) throws SQLException {
        PreparedStatement statement = prepare(connection, context,
                "SELECT c.oid::text,format('%I.%I',n.nspname,c.relname),"
                        + "format('%I',r.rolname),c.xmin::text FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid=c.relnamespace "
                        + "JOIN pg_roles r ON r.oid=c.relowner "
                        + "WHERE c.oid=CAST(? AS regclass) AND c.relkind='S'");
        try {
            statement.setString(1, sequence);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                return new PostgresqlSequenceIdentity(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4));
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private static PostgresqlSequenceParameters postgresqlSequenceParameters(Connection connection,
            TaskExecutionContext context, String sequence, String safeSequence) throws SQLException {
        long increment;
        long maximum;
        boolean cycle;
        PreparedStatement statement = prepare(connection, context,
                "SELECT seqincrement,seqmax,seqcycle FROM pg_sequence "
                        + "WHERE seqrelid=CAST(? AS regclass)");
        try {
            statement.setString(1, sequence);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("PostgreSQL sequence metadata is unavailable for " + sequence);
                }
                increment = rows.getLong(1);
                maximum = rows.getLong(2);
                cycle = rows.getBoolean(3);
            }
        } finally {
            closePreparedStatement(context, statement);
        }
        try (Statement state = connection.createStatement()) {
            context.onStatementCreated(state);
            try (ResultSet rows = state.executeQuery("SELECT last_value,is_called FROM " + safeSequence)) {
                if (!rows.next()) {
                    throw new SQLException("PostgreSQL sequence state is unavailable for " + sequence);
                }
                return new PostgresqlSequenceParameters(rows.getLong(1), rows.getBoolean(2),
                        increment, maximum, cycle);
            } finally {
                context.onStatementClosed(state);
            }
        }
    }

    private static long checkedSequenceAdd(long value, long increment, String tableKey,
            String columnName) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FINALIZATION_FAILED.name(),
                    "Sequence value overflow for " + tableKey + "." + columnName, overflow);
        }
    }

    private static String preparedString(Connection connection, TaskExecutionContext context, String sql,
            StatementBinder binder) throws SQLException {
        PreparedStatement statement = prepare(connection, context, sql);
        try {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private static void rebuildIndexes(Connection connection, TaskExecutionContext context,
            StageTable stage, DbFamily family) throws SQLException {
        switch (family) {
            case MYSQL -> executeStatement(connection, context, "OPTIMIZE TABLE " + stage.targetName);
            case POSTGRESQL -> executeUpdate(connection, context, "REINDEX TABLE " + stage.targetName);
            case H2 -> throw new SQLException("H2 does not expose an online index rebuild operation");
        }
    }

    private static void refreshStatistics(Connection connection, TaskExecutionContext context,
            StageTable stage, DbFamily family) throws SQLException {
        switch (family) {
            case MYSQL -> executeStatement(connection, context, "ANALYZE TABLE " + stage.targetName);
            case POSTGRESQL, H2 -> executeUpdate(connection, context, "ANALYZE " + stage.targetName);
        }
    }

    private static Map<String, Long> rowCounts(Connection connection, TaskExecutionContext context,
            Map<TableIdentity, StageTable> stages) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (StageTable stage : stages.values()) {
            result.put(stage.tableKey, queryLong(connection, context,
                    "SELECT COUNT(*) FROM " + stage.targetName));
        }
        return result;
    }

    private static Map<String, Long> rowCountsByTarget(Connection connection,
            TaskExecutionContext context, ImportTaskSpec spec, ImportManifest manifest) throws SQLException {
        IDbMetaData metadata = Chat2DBContext.getDbMetaData();
        Map<TableIdentity, ImportTaskSpec> specs = sourceSpecs(spec);
        Map<String, Long> result = new LinkedHashMap<>();
        for (ImportManifestShard shard : manifest.getShards()) {
            String key = shardTableKey(shard);
            if (!result.containsKey(key)) {
                ImportTaskSpec tableSpec = specs.get(TableIdentity.of(shard));
                String database = StringUtils.defaultIfBlank(shard.getDatabaseName(),
                        tableSpec == null ? null : tableSpec.getTarget().getDatabaseName());
                String schema = StringUtils.defaultIfBlank(shard.getSchemaName(),
                        tableSpec == null ? null : tableSpec.getTarget().getSchemaName());
                String target = metadata.getQualifiedTableName(database, schema, shard.getTableName());
                result.put(key, queryLong(connection, context, "SELECT COUNT(*) FROM " + target));
            }
        }
        return result;
    }

    private static long queryLong(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw new SQLException("Query returned no aggregate row");
                }
                return rows.getLong(1);
            } finally {
                context.onStatementClosed(statement);
            }
        }
    }

    private static int executeUpdate(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try {
                return statement.executeUpdate(sql);
            } finally {
                context.onStatementClosed(statement);
            }
        }
    }

    private static void executeStatement(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try {
                statement.execute(sql);
            } finally {
                context.onStatementClosed(statement);
            }
        }
    }

    private static DigestResult digest(Connection connection, TaskExecutionContext context,
            String sql, int columns) throws SQLException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        long count = 0L;
        try (Statement statement = connection.createStatement()) {
            context.onStatementCreated(statement);
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    count++;
                    for (int column = 1; column <= columns; column++) {
                        byte[] value = rows.getBytes(column);
                        if (value == null) {
                            digest.update((byte) 0);
                        } else {
                            digest.update((byte) 1);
                            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value.length).array());
                            digest.update(value);
                        }
                    }
                }
            } finally {
                context.onStatementClosed(statement);
            }
        }
        return new DigestResult(count, Base64.getEncoder().encodeToString(digest.digest()));
    }

    private static Set<String> indexSnapshot(List<TableIndex> indexes) {
        if (indexes == null) {
            return Set.of();
        }
        return indexes.stream().map(index -> String.join("|",
                        StringUtils.defaultString(index.getName()),
                        StringUtils.defaultString(index.getType()),
                        String.valueOf(index.getUnique()),
                        StringUtils.defaultString(index.getMethod()),
                        String.valueOf(index.getVisible()),
                        indexColumnSnapshot(index.getColumnList())))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String indexColumnSnapshot(List<TableIndexColumn> columns) {
        if (columns == null) {
            return "";
        }
        return columns.stream()
                .sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition,
                        Comparator.nullsLast(Short::compareTo)))
                .map(column -> String.join(":",
                        StringUtils.defaultString(column.getColumnName()),
                        String.valueOf(column.getOrdinalPosition()),
                        StringUtils.defaultString(column.getAscOrDesc()),
                        StringUtils.defaultString(column.getCollation()),
                        StringUtils.defaultString(column.getFilterCondition()),
                        String.valueOf(column.getSubPart())))
                .collect(Collectors.joining(","));
    }

    private static List<PrimaryKey> sortedPrimaryKeys(List<PrimaryKey> keys) {
        if (keys == null) {
            return List.of();
        }
        return keys.stream().sorted(Comparator.comparing(PrimaryKey::getKeySeq,
                Comparator.nullsLast(Integer::compareTo))).toList();
    }

    private static boolean hasCycles(ImportDependencyPlan plan) {
        return plan != null
                && (plan.getCyclicComponents() != null && !plan.getCyclicComponents().isEmpty()
                    || plan.getSelfReferencingTables() != null && !plan.getSelfReferencingTables().isEmpty());
    }

    static List<ImportTableDependency> cyclicPhysicalDependencies(ImportManifest manifest) {
        if (manifest == null || manifest.getDependencies() == null
                || manifest.getDependencyPlan() == null) {
            return List.of();
        }
        ImportDependencyPlan plan = manifest.getDependencyPlan();
        Map<TableIdentity, Integer> componentByTable = new LinkedHashMap<>();
        List<List<String>> components = plan.getCyclicComponents() == null
                ? List.of() : plan.getCyclicComponents();
        for (int index = 0; index < components.size(); index++) {
            for (String table : components.get(index)) {
                componentByTable.put(new TableIdentity(table), index);
            }
        }
        Set<TableIdentity> selfReferences = plan.getSelfReferencingTables() == null
                ? Set.of() : plan.getSelfReferencingTables().stream()
                        .map(TableIdentity::new)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        return manifest.getDependencies().stream().filter(edge -> !edge.isLogical())
                .filter(edge -> {
                    TableIdentity parent = TableIdentity.of(edge, true);
                    TableIdentity child = TableIdentity.of(edge, false);
                    if (parent.equals(child)) {
                        return selfReferences.contains(parent);
                    }
                    Integer parentComponent = componentByTable.get(parent);
                    return parentComponent != null && parentComponent.equals(componentByTable.get(child));
                })
                .toList();
    }

    private static boolean allDeferrable(List<ImportTableDependency> dependencies) {
        return dependencies.stream().allMatch(edge -> edge.getDeferrability() != null
                && edge.getDeferrability() != DatabaseMetaData.importedKeyNotDeferrable);
    }

    private static DependencyGroup dependencyGroup(ImportTableDependency dependency) {
        String name = StringUtils.defaultIfBlank(dependency.getConstraintName(),
                dependency.getParentColumn() + ">" + dependency.getChildColumn());
        return new DependencyGroup(tableKey(dependency, true), tableKey(dependency, false), name);
    }

    private static String tableKey(ImportTableDependency dependency, boolean parent) {
        String configured = parent ? dependency.getParentTableKey() : dependency.getChildTableKey();
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        return ImportTaskSourceSupport.tableKey(
                parent ? dependency.getParentDatabaseName() : dependency.getChildDatabaseName(),
                parent ? dependency.getParentSchemaName() : dependency.getChildSchemaName(),
                parent ? dependency.getParentTable() : dependency.getChildTable());
    }

    private static StageTable stageForDependency(Map<TableIdentity, StageTable> stages,
            ImportTableDependency dependency, boolean parent) {
        StageTable stage = stages.get(TableIdentity.of(dependency, parent));
        if (stage != null) {
            return stage;
        }
        String configuredKey = tableKey(dependency, parent);
        return stages.values().stream().filter(candidate -> candidate.tableKey.equals(configuredKey))
                .findFirst().orElse(null);
    }

    private static boolean validationEnabled(ImportTaskSpec spec, String kind) {
        ImportValidationOptions options = spec == null ? null : spec.getValidationOptions();
        return switch (kind) {
            case "rows" -> Boolean.TRUE.equals(options == null ? null : options.getRowCount());
            case "checksum" -> Boolean.TRUE.equals(options == null ? null : options.getChecksum());
            case "orphan" -> Boolean.TRUE.equals(options == null ? null : options.getOrphanCheck());
            default -> Boolean.TRUE.equals(options == null ? null : options.getSourceProfiling());
        };
    }

    private static boolean skipMode(ImportTaskSpec spec) {
        return spec.getOptions() != null && "SKIP".equalsIgnoreCase(spec.getOptions().getOnError());
    }

    private static long maxErrors(Iterable<ImportTaskSpec> specs) {
        long result = Long.MAX_VALUE;
        for (ImportTaskSpec spec : specs) {
            if (!skipMode(spec)) {
                continue;
            }
            Integer configured = spec.getOptions() == null ? null : spec.getOptions().getMaxErrors();
            if (configured != null && configured >= 0) {
                result = Math.min(result, configured.longValue());
            }
        }
        return result;
    }

    private static void requireRollbackSafeGeneratedColumns(ImportTaskSpec spec, String tableKey,
            List<TableColumn> columns) {
        if (!rollbackRequested(spec)) {
            return;
        }
        List<String> generated = columns.stream()
                .filter(column -> Boolean.TRUE.equals(column.getAutoIncrement())
                        || Boolean.TRUE.equals(column.getGeneratedColumn())
                        || StringUtils.defaultString(column.getDefaultValue())
                                .matches("(?is).*\\bnextval\\s*\\(.*"))
                .map(TableColumn::getName)
                .toList();
        if (!generated.isEmpty()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Full rollback or rehearsal cannot prove generated sequence restoration for "
                            + tableKey + " columns " + generated);
        }
    }

    private static boolean rollbackRequested(ImportTaskSpec spec) {
        ImportRollbackOptions rollback = spec == null ? null : spec.getRollbackOptions();
        return Boolean.TRUE.equals(rollback == null ? null : rollback.getFullRollback())
                || Boolean.TRUE.equals(rollback == null ? null : rollback.getRehearsal());
    }

    private static boolean rehearsal(ImportTaskSpec spec) {
        return spec.getRollbackOptions() != null
                && Boolean.TRUE.equals(spec.getRollbackOptions().getRehearsal());
    }

    private static int samplePercent(ImportTaskSpec spec) {
        if (!rehearsal(spec)) {
            return 100;
        }
        Integer configured = spec.getPerformanceSamplePercent();
        return configured == null ? 5 : Math.max(1, Math.min(100, configured));
    }

    private static Map<String, Object> baseReport(ImportTaskSpec spec, ImportManifest manifest) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("taskId", manifest.getTaskId());
        report.put("manifestFingerprint", manifest.getManifestFingerprint());
        report.put("scope", StringUtils.defaultIfBlank(spec.getScope(), "TABLE"));
        report.put("sourceKind", StringUtils.defaultIfBlank(spec.getSourceKind(), "TRUSTED"));
        report.put("cycleStrategy", StringUtils.defaultIfBlank(spec.getCycleStrategy(), "REJECT"));
        report.put("rehearsal", rehearsal(spec));
        return report;
    }

    private static ArtifactDraft createReportDraft(TaskExecutionContext context, ImportManifest manifest) {
        Path source = Path.of(manifest.getShards().get(0).getSourcePath()).toAbsolutePath().normalize();
        Path directory = source.getParent() == null ? Path.of(".").toAbsolutePath() : source.getParent();
        return context.createArtifact(TaskArtifactRole.IMPORT_REPORT, directory.toString(),
                "import-" + manifest.getTaskId() + "-report.json", "application/json");
    }

    private static void writeReport(ArtifactDraft draft, Map<String, Object> report) {
        try {
            Files.writeString(draft.getTemporaryFile().toPath(), JSON.toJSONString(report),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write staging import report", failure);
        }
    }

    private static void writeRejectSummary(TaskExecutionContext context, ImportTaskSpec spec,
            ImportManifest manifest,
            Map<TableIdentity, StageTable> stages, long rejectedRows) {
        if (!anySkipMode(spec)) {
            return;
        }
        Path source = Path.of(manifest.getShards().get(0).getSourcePath()).toAbsolutePath().normalize();
        Path directory = source.getParent() == null ? Path.of(".").toAbsolutePath() : source.getParent();
        ArtifactDraft draft = context.createArtifact(TaskArtifactRole.REJECT_SUMMARY, directory.toString(),
                "import-" + manifest.getTaskId() + "-reject-summary.json", "application/json");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", 1);
        summary.put("taskId", manifest.getTaskId());
        summary.put("manifestFingerprint", manifest.getManifestFingerprint());
        summary.put("policy", "SKIP");
        summary.put("rejectedRows", rejectedRows);
        summary.put("shards", rejectShardOutcomes(manifest, stages));
        try {
            Files.writeString(draft.getTemporaryFile().toPath(), JSON.toJSONString(summary),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write staging reject summary", failure);
        }
    }

    private static List<Map<String, Object>> rejectShardOutcomes(ImportManifest manifest,
            Map<TableIdentity, StageTable> stages) {
        Map<String, ShardOutcome> outcomes = stages.values().stream()
                .flatMap(stage -> stage.shardOutcomes.stream())
                .collect(Collectors.toMap(ShardOutcome::shardId, outcome -> outcome,
                        (first, ignored) -> first, LinkedHashMap::new));
        return manifest.getShards().stream()
                .map(shard -> outcomes.getOrDefault(shard.getShardId(), ShardOutcome.notAttempted(shard)))
                .map(ShardOutcome::asMap)
                .toList();
    }

    private static boolean anySkipMode(ImportTaskSpec spec) {
        return ImportTaskSourceSupport.effectiveSources(spec).stream()
                .map(source -> ImportTaskSourceSupport.specForSource(spec, source))
                .anyMatch(StagingManifestImporter::skipMode);
    }

    private static Map<String, Object> transactionReport(String outcome, boolean rolledBack,
            boolean rollbackVerified) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("outcome", outcome);
        transaction.put("rolledBack", rolledBack);
        transaction.put("rollbackVerified", rollbackVerified);
        transaction.put("manualReconciliationRequired",
                COMMIT_UNKNOWN.equals(outcome) || "FAILED_AFTER_COMMIT".equals(outcome));
        transaction.put("retrySafe", !COMMIT_UNKNOWN.equals(outcome) && !"FAILED_AFTER_COMMIT".equals(outcome));
        return Map.copyOf(transaction);
    }

    private static TaskExecutionException commitOutcomeUnknown(String phase, Throwable cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "Staging import commit outcome is unknown",
                "Manually verify target data before retrying (" + phase + " commit)", cause);
    }

    private static void requireRestored(Map<String, Long> before, Map<String, Long> restored) {
        if (!countsMatch(before, restored)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Import rehearsal rollback did not restore target row counts");
        }
    }

    private static boolean countsMatch(Map<String, Long> expected, Map<String, Long> actual) {
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static long elapsedMillis(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static long throughput(Map<TableIdentity, StageTable> stages, long started) {
        long rows = stages.values().stream().mapToLong(stage -> stage.stagedRows).sum();
        double seconds = Math.max(0.001D, (System.nanoTime() - started) / 1_000_000_000.0D);
        return (long) (rows / seconds);
    }

    private static String quoted(StageTable stage, String identifier) {
        return Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor().quoteIdentifierAlways(identifier);
    }

    private static String identifierSuffix(String tableKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(tableKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String quotedList(StageTable stage, List<String> columns, String alias) {
        return columns.stream().map(name -> (alias == null ? "" : alias + ".") + quoted(stage, name))
                .collect(Collectors.joining(","));
    }

    private static boolean containsColumnName(Set<String> values, String expected) {
        return ImportColumnResolver.uniqueNameIndex(expected, List.copyOf(values),
                "logical dependency column") >= 0;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return StringUtils.defaultIfBlank(root.getMessage(), root.getClass().getSimpleName());
    }

    private static void discardDedicatedConnection(ConnectInfo connectInfo, Connection connection,
            TaskExecutionContext context) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                logCleanupFailure(context, "STAGING_CONNECTION_CLOSE_FAILED",
                        "Could not close the dedicated staging connection", closeFailure);
            } finally {
                connectInfo.setConnection(null);
            }
        }
        try {
            connectInfo.close();
        } catch (Throwable closeFailure) {
            logCleanupFailure(context, "STAGING_CONNECTION_DISCARD_FAILED",
                    "Could not finish disposing staging connection resources", closeFailure);
        }
    }

    private static void logCleanupFailure(TaskExecutionContext context, String code, String message,
            Throwable failure) {
        try {
            context.logWarn(code, message, Map.of("errorType", failure.getClass().getSimpleName()));
        } catch (Throwable ignored) {
            // Cleanup and physical connection disposal remain authoritative.
        }
    }

    private static void runQuietly(CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable ignored) {
            // The task's primary failure or connection disposal remains authoritative.
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(ConnectInfo connectInfo) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        CheckedRunnable NOOP = () -> { };

        void run() throws Exception;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private enum DbFamily {
        MYSQL,
        POSTGRESQL,
        H2;

        private static DbFamily of(String dbType) {
            String normalized = StringUtils.defaultString(dbType).toUpperCase(Locale.ROOT);
            if (normalized.contains("MYSQL") || normalized.contains("MARIADB")) {
                return MYSQL;
            }
            if (normalized.contains("POSTGRE") || normalized.contains("KINGBASE")) {
                return POSTGRESQL;
            }
            if (normalized.contains("H2")) {
                return H2;
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "All-VARCHAR staging is not supported for database type " + normalized);
        }

        private String temporaryPrefix() {
            return this == H2 ? "LOCAL TEMPORARY TABLE" : "TEMPORARY TABLE";
        }

        private String textType() {
            return this == MYSQL ? "LONGTEXT" : this == POSTGRESQL ? "TEXT" : "VARCHAR";
        }

        private String castPlaceholder(String columnType) {
            if (this != POSTGRESQL) {
                return "?";
            }
            String type = StringUtils.trimToEmpty(columnType);
            if (type.isEmpty() || !type.matches("[A-Za-z0-9_ .\\[\\]\"]+")) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "PostgreSQL staging cannot safely cast target type " + type);
            }
            return "CAST(? AS " + type + ")";
        }
    }

    private record DigestResult(long rows, String sha256) {
    }

    private record MysqlTarget(String database, String table, String displayName, String qualifiedName) {
    }

    private record FinalizationTarget(StageTable stage, Map<String, Object> result) {
    }

    private record PostgresqlSequenceParameters(long lastValue, boolean called, long increment,
                                                long maximum, boolean cycle) {
    }

    private record PostgresqlSequenceIdentity(String oid, String qualifiedName, String owner,
                                              String catalogVersion) {
    }

    private record ShardOutcome(String shardId, long sourceRows, long stagedRows, long rejectedRows,
                                String status, String failure) {
        private static ShardOutcome completed(ImportManifestShard shard, long sourceRows,
                long stagedRows, long rejectedRows) {
            return new ShardOutcome(shard.getShardId(), sourceRows, stagedRows, rejectedRows,
                    "COMPLETED", null);
        }

        private static ShardOutcome failed(ImportManifestShard shard, long sourceRows,
                long stagedRows, long rejectedRows, String failure) {
            return new ShardOutcome(shard.getShardId(), sourceRows, stagedRows, rejectedRows,
                    "FAILED", failure);
        }

        private static ShardOutcome notAttempted(ImportManifestShard shard) {
            return new ShardOutcome(shard.getShardId(), 0L, 0L, 0L,
                    "NOT_ATTEMPTED", null);
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("shardId", shardId);
            result.put("status", status);
            result.put("sourceRows", sourceRows);
            result.put("stagedRows", stagedRows);
            result.put("rejectedRows", rejectedRows);
            result.put("rejectArtifactRole", TaskArtifactRole.rejectForShard(shardId));
            if (StringUtils.isNotBlank(failure)) {
                result.put("failure", failure);
            }
            return result;
        }
    }

    private record CycleControl(Map<TableIdentity, Set<String>> nullableCycleColumns,
                                CheckedRunnable restoreBeforeValidation,
                                CheckedRunnable restoreQuietly) {
        private static final CycleControl NONE = new CycleControl(Map.of(),
                CheckedRunnable.NOOP, CheckedRunnable.NOOP);
    }

    private record SessionControl(CheckedRunnable restore) {
        private static final SessionControl NONE = new SessionControl(CheckedRunnable.NOOP);
    }

    private record TableIdentity(String tableKey) {
        private TableIdentity {
            tableKey = StringUtils.trimToNull(tableKey);
            if (tableKey == null) {
                throw new IllegalArgumentException("A table identity requires a table name");
            }
        }

        private static TableIdentity of(ImportTableSource source) {
            return new TableIdentity(ImportTaskSourceSupport.tableKey(source));
        }

        private static TableIdentity of(ImportManifestShard shard) {
            return new TableIdentity(shardTableKey(shard));
        }

        private static TableIdentity of(ImportTableDependency dependency, boolean parent) {
            return new TableIdentity(StagingManifestImporter.tableKey(dependency, parent));
        }

        @Override
        public String toString() {
            return tableKey;
        }
    }

    private record DependencyEndpoints(String parentTableKey, String childTableKey) {
    }

    private record DependencyGroup(String parentTableKey, String childTableKey, String constraint) {
    }

    private static final class MutableLong {
        private long value;
    }

    private static final class StageTable {
        private final String tableKey;
        private final TableIdentity identity;
        private final String database;
        private final String schema;
        private final String table;
        private final String targetName;
        private final String rawTable;
        private final String typedTable;
        private final String typedShardColumn;
        private final String typedRowColumn;
        private final List<TableColumn> columns;
        private final ImportColumnResolver.Resolution resolution;
        private final List<PrimaryKey> primaryKeys;
        private final Set<String> indexesBefore;
        private final List<String> header;
        private final ImportTaskSpec spec;
        private final Map<String, ImportManifestShard> shardsById;
        private final List<ShardOutcome> shardOutcomes = new ArrayList<>();
        private final List<ColumnProfile> columnProfiles;
        private long sourceRows;
        private long stagedRows;
        private long rejectedRows;
        private long insertedRows;

        private StageTable(ImportTaskSpec spec, String tableKey, TableIdentity identity, String database,
                String schema, String table, String targetName, String rawTable, String typedTable, String typedShardColumn,
                String typedRowColumn, List<TableColumn> columns, ImportColumnResolver.Resolution resolution,
                List<PrimaryKey> primaryKeys, Set<String> indexesBefore, List<String> header,
                List<ImportManifestShard> shards) {
            this.spec = spec;
            this.tableKey = tableKey;
            this.identity = identity;
            this.database = database;
            this.schema = schema;
            this.table = table;
            this.targetName = targetName;
            this.rawTable = rawTable;
            this.typedTable = typedTable;
            this.typedShardColumn = typedShardColumn;
            this.typedRowColumn = typedRowColumn;
            this.columns = List.copyOf(columns);
            this.resolution = resolution;
            this.primaryKeys = primaryKeys;
            this.indexesBefore = indexesBefore;
            this.header = header;
            this.shardsById = shards.stream().collect(Collectors.toMap(ImportManifestShard::getShardId,
                    shard -> shard, (first, ignored) -> first, LinkedHashMap::new));
            this.columnProfiles = header.stream().map(ColumnProfile::new).toList();
        }

        private void recordPublishReject(String shardId) {
            stagedRows--;
            for (int index = 0; index < shardOutcomes.size(); index++) {
                ShardOutcome outcome = shardOutcomes.get(index);
                if (outcome.shardId().equals(shardId)) {
                    shardOutcomes.set(index, new ShardOutcome(outcome.shardId(), outcome.sourceRows(),
                            Math.max(0L, outcome.stagedRows() - 1L), outcome.rejectedRows() + 1L,
                            outcome.status(), outcome.failure()));
                    return;
                }
            }
            throw new IllegalStateException("Publish reject has no completed shard outcome: " + shardId);
        }

        private void observe(CSVRecord record, ImportOptions options) {
            for (int index = 0; index < columnProfiles.size(); index++) {
                columnProfiles.get(index).observe(record.get(index),
                        options == null ? null : options.getNullString());
            }
        }

        private TableColumn column(String name) {
            int index = ImportColumnResolver.uniqueNameIndex(name,
                    columns.stream().map(TableColumn::getName).toList(), "target column");
            return index < 0 ? null : columns.get(index);
        }

        private String requiredColumnName(String name) {
            TableColumn column = column(name);
            if (column == null) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Dependency column is not present in " + tableKey + ": " + name);
            }
            return column.getName();
        }

        private boolean hasInsertedColumn(String name) {
            return ImportColumnResolver.uniqueNameIndex(name,
                    resolution.tableColumns().stream().map(TableColumn::getName).toList(),
                    "inserted target column") >= 0;
        }

        private Map<String, Object> profile() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table", tableKey);
            result.put("sourceColumns", header);
            result.put("targetColumns", resolution.tableColumns().stream()
                    .map(TableColumn::getName).toList());
            result.put("sourceRows", sourceRows);
            result.put("stagedRows", stagedRows);
            result.put("rejectedRows", rejectedRows);
            result.put("columns", columnProfiles.stream().map(ColumnProfile::asMap).toList());
            return result;
        }
    }

    private static final class ColumnProfile {
        private final String name;
        private long rows;
        private long nullRows;
        private long blankRows;
        private int minLength = Integer.MAX_VALUE;
        private int maxLength;

        private ColumnProfile(String name) {
            this.name = name;
        }

        private void observe(String value, String nullString) {
            rows++;
            if (value == null || Objects.equals(nullString, value)) {
                nullRows++;
                return;
            }
            if (value.isEmpty()) {
                blankRows++;
            }
            minLength = Math.min(minLength, value.length());
            maxLength = Math.max(maxLength, value.length());
        }

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("rows", rows);
            result.put("nullRows", nullRows);
            result.put("blankRows", blankRows);
            result.put("minLength", minLength == Integer.MAX_VALUE ? 0 : minLength);
            result.put("maxLength", maxLength);
            return result;
        }
    }

    private static final class RejectWriter {
        private final String shardId;
        private final BufferedWriter writer;

        private RejectWriter(TaskExecutionContext context, ImportManifestShard shard, Path source)
                throws IOException {
            shardId = shard.getShardId();
            Path directory = source.getParent() == null ? Path.of(".").toAbsolutePath() : source.getParent();
            String role = TaskArtifactRole.rejectForShard(shard.getShardId());
            ArtifactDraft draft = context.createArtifact(role, directory.toString(),
                    source.getFileName() + ".rejects.ndjson", "application/x-ndjson");
            writer = Files.newBufferedWriter(draft.getTemporaryFile().toPath(), StandardCharsets.UTF_8);
        }

        private void write(long row, List<String> values, String reason) throws IOException {
            writer.write(JSON.toJSONString(Map.of("row", row, "values", values,
                    "reason", StringUtils.defaultIfBlank(reason, "unknown"))));
            writer.newLine();
        }

        private void closeQuietly() {
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // Artifact publication will surface an unreadable draft.
            }
        }
    }
}
