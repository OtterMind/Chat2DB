package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.imports.ConsoleTaskProgressListener;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFileProbe;
import ai.chat2db.community.domain.core.impl.task.imports.ImportSqlExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.SyncSqlBatchHandler;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.LineHandler;
import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
public class SQLImporter implements IImportStrategy {

    private static final int EXPORTED_SQL_BATCH_SIZE = 1000;
    private static final long EXPORTED_SQL_BATCH_CHARS = 10L * 1024L * 1024L;
    private static final int TARGET_LOCK_TIMEOUT_SECONDS = 5;
    private static final String COMMIT_OUTCOME_UNKNOWN = "SQL_IMPORT_COMMIT_OUTCOME_UNKNOWN";
    private static final Pattern MYSQL_GRANT = Pattern.compile(
            "^GRANT\\s+(.+?)\\s+ON\\s+(.+?)\\s+TO\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MYSQL_REVOKE = Pattern.compile(
            "^REVOKE\\s+(.+?)\\s+ON\\s+(.+?)\\s+FROM\\s+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MYSQL_TEMPORARY_TABLE_DDL = Pattern.compile(
            "^\\s*CREATE\\s+TEMPORARY\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MYSQL_PATCH_VERSION = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\D.*)?$");
    private static final Comparator<ExportedSqlStatementReader.TargetTable> TARGET_LOCK_ORDER =
            Comparator.comparing(
                            (ExportedSqlStatementReader.TargetTable target) ->
                                    StringUtils.defaultString(target.catalog()),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(target -> StringUtils.defaultString(target.schema()),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(target -> StringUtils.defaultString(target.table()),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(target -> StringUtils.defaultString(target.catalog()))
                    .thenComparing(target -> StringUtils.defaultString(target.schema()))
                    .thenComparing(target -> StringUtils.defaultString(target.table()));

    @Override
    public void run(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            context.checkCancelled();
            File sourceFile = new File(spec.getSourceFile());
            ImportSqlExecutor sqlExecutor = new ImportSqlExecutor(context);
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            String databaseType = connectInfo.getDbType();
            context.logInfo(TaskEventCode.FILE_READ_STARTED.name(), "Reading SQL import file");
            ExportedSqlStatementReader.ExporterProfile exporterProfile;
            try {
                exporterProfile = configuredExporterProfile(spec);
            } catch (IllegalArgumentException invalidProfile) {
                throw invalidExport(invalidProfile);
            }
            if (exporterProfile != null) {
                boolean thirdParty = isThirdParty(spec);
                if (thirdParty) {
                    try {
                        ExportedSqlStatementReader.requireCompatibleThirdPartyProfile(
                                exporterProfile, databaseType);
                    } catch (IllegalArgumentException incompatibleProfile) {
                        throw invalidExport(incompatibleProfile);
                    }
                }
                ExportedSqlStatementReader.StatementPolicy statementPolicy = thirdParty
                        ? ExportedSqlStatementReader.StatementPolicy.THIRD_PARTY_LITERAL_VALUES
                        : ExportedSqlStatementReader.StatementPolicy.TRUSTED;
                importExportedDataOnly(spec, context, sourceFile, sqlExecutor, exporterProfile, databaseType,
                        statementPolicy);
            } else if (StringUtils.equalsAnyIgnoreCase(databaseType, DatabaseTypeEnum.MYSQL.name(),
                    DatabaseTypeEnum.ORACLE.name(), DatabaseTypeEnum.OSCAR.name(),
                    DatabaseTypeEnum.SQLSERVER.name(), DatabaseTypeEnum.POSTGRESQL.name())) {
                ConsoleTaskProgressListener consoleProgressListener =
                        new ConsoleTaskProgressListener(context, sourceFile);
                SyncSqlBatchHandler syncSqlBatchHandler = new SyncSqlBatchHandler(context, sqlExecutor);
                int statementCount = DefaultSqlSyntaxHandler.parserSqlScript(
                        sourceFile, databaseType, consoleProgressListener, syncSqlBatchHandler);
                context.checkCancelled();
                context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "SQL file parsed",
                        Map.of("statementCount", statementCount));
            } else {
                StringBuilder sb = new StringBuilder();
                List<String> sqls = new ArrayList<>();
                DbType dbType = JdbcUtils.parse2DruidDbType(databaseType);
                long totalBytes = sourceFile.length();
                AtomicLong bytesRead = new AtomicLong();
                StringBuilder processStr = new StringBuilder();
                long startedAt = System.currentTimeMillis();
                FileUtil.readLines(sourceFile, Charset.forName("UTF-8"), (LineHandler) line -> {
                    context.checkCancelled();
                    bytesRead.addAndGet(line.getBytes().length + System.lineSeparator().getBytes().length);
                    setProgress(context, bytesRead.get(), totalBytes, processStr);
                    sb.append(line).append('\n');
                    String trimmed = line == null ? "" : line.trim();

                    if (trimmed.endsWith(";")) {
                        List<String> list = SqlUtils.parse(sb.toString(), dbType, false);
                        if (CollectionUtils.isNotEmpty(list)) {
                            for (int i = 0; i < list.size() - 1; i++) {
                                String sql = list.get(i);
                                if (StringUtils.isNotBlank(sql) && !sql.trim().equals(";")) {
                                    sqls.add(EasyStringUtils.sqlEscape(sql));
                                }
                            }
                            sb.setLength(0);
                            String last = list.get(list.size() - 1);
                            sb.append(last);
                            if (!last.trim().endsWith(";")) {
                                sb.append(";");
                            }
                            sb.append('\n');
                        }
                    }
                    if (sqls.size() >= 100) {
                        sqlExecutor.executeBatch(sqls);
                        sqls.clear();
                    }
                });
                String endStr = sb.toString();
                if (StringUtils.isNotBlank(endStr) && !endStr.trim().equals(";")) {
                    sqls.add(EasyStringUtils.sqlEscape(endStr));
                }
                log.info("parse sql cost:{}", System.currentTimeMillis() - startedAt);
                sqlExecutor.executeBatch(sqls);
                context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "SQL file parsed");
            }
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not import SQL file", e);
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import SQL file", e);
        }
    }

    static ExportedSqlStatementReader.ExporterProfile configuredExporterProfile(ImportTaskSpec spec) {
        ImportOptions options = spec == null ? null : spec.getOptions();
        ExportedSqlStatementReader.ExporterProfile profile = ExportedSqlStatementReader.ExporterProfile.resolve(
                options == null ? null : options.getSqlExporterProfile());
        if (profile == null && spec != null
                && "THIRD_PARTY".equalsIgnoreCase(StringUtils.trimToEmpty(spec.getSourceKind()))) {
            throw new IllegalArgumentException(
                    "THIRD_PARTY SQL imports require an explicit SQL exporter profile");
        }
        return profile;
    }

    private static boolean isThirdParty(ImportTaskSpec spec) {
        return spec != null
                && "THIRD_PARTY".equalsIgnoreCase(StringUtils.trimToEmpty(spec.getSourceKind()));
    }

    private void importExportedDataOnly(ImportTaskSpec spec, TaskExecutionContext context, File sourceFile,
            ImportSqlExecutor sqlExecutor, ExportedSqlStatementReader.ExporterProfile profile,
            String databaseType, ExportedSqlStatementReader.StatementPolicy statementPolicy) throws IOException {
        ImportOptions options = spec.getOptions();
        Charset charset = ImportFileProbe.effectiveCharset(sourceFile,
                options == null ? null : options.getCharset());
        long expectedSize = Files.size(sourceFile.toPath());
        long expectedModifiedAt = Files.getLastModifiedTime(sourceFile.toPath()).toMillis();

        ExportedSqlStatementReader.Inspection inspected;
        try {
            inspected = ExportedSqlStatementReader.inspect(sourceFile, charset, profile,
                    databaseType, statementPolicy, (bytesRead, statementCount) -> context.checkCancelled());
        } catch (IllegalArgumentException invalidExport) {
            throw invalidExport(invalidExport);
        }
        rejectUnsupportedDirective(context, inspected);
        requireTargetsWithinScope(spec, inspected, databaseType);
        ensureSourceUnchanged(sourceFile, expectedSize, expectedModifiedAt);
        context.checkCancelled();

        ExportedSqlStatementReader.Inspection streamed = executeExportTransaction(
                spec, context, inspected, databaseType, () -> {
            List<String> batch = new ArrayList<>(EXPORTED_SQL_BATCH_SIZE);
            AtomicLong batchChars = new AtomicLong();
            ConsoleTaskProgressListener consoleProgress = new ConsoleTaskProgressListener(context, sourceFile);
            ExportedSqlStatementReader.Inspection secondPass;
            try {
                secondPass = ExportedSqlStatementReader.streamVerifiedDataStatements(sourceFile, charset, profile,
                        databaseType, statementPolicy, (sql, target) -> {
                    context.checkCancelled();
                    if (!inspected.targetTables().contains(target)) {
                        throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                                "SQL export target changed after transactional preflight");
                    }
                    if (!batch.isEmpty() && (batch.size() >= EXPORTED_SQL_BATCH_SIZE
                            || batchChars.get() + sql.length() > EXPORTED_SQL_BATCH_CHARS)) {
                        flushExportedSqlBatch(context, sqlExecutor, batch, batchChars);
                    }
                    batch.add(sql);
                    batchChars.addAndGet(sql.length());
                }, (bytesRead, statementCount) -> {
                    context.checkCancelled();
                    consoleProgress.onProgress(bytesRead, statementCount);
                });
            } catch (IllegalArgumentException invalidExport) {
                throw invalidExport(invalidExport);
            }
            rejectUnsupportedDirective(context, secondPass);
            ensureSourceUnchanged(sourceFile, expectedSize, expectedModifiedAt);
            ensureInspectionMatches(inspected, secondPass);
            flushExportedSqlBatch(context, sqlExecutor, batch, batchChars);
            return secondPass;
        });

        if (streamed.dataStatementCount() == 0) {
            context.logWarn("SQL_EXPORT_NO_DATA_STATEMENTS", "SQL export contained no supported data statements",
                    Map.of("exporterProfile", profile.name(),
                            "filteredStatementCount", streamed.filteredStatementCount()));
        }
        context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "SQL export parsed in data-only mode",
                Map.of("exporterProfile", profile.name(),
                        "statementCount", streamed.dataStatementCount(),
                        "parsedStatementCount", streamed.statementCount(),
                        "filteredStatementCount", streamed.filteredStatementCount()));
    }

    static void requireTargetsWithinScope(ImportTaskSpec spec,
            ExportedSqlStatementReader.Inspection inspection, String databaseType) {
        TaskTargetSnapshot selected = spec == null ? null : spec.getTarget();
        if (selected == null) {
            throw invalidScopeTarget("SQL import target is required");
        }

        String scope;
        try {
            scope = ImportScope.normalize(spec.getScope());
        } catch (IllegalArgumentException invalidScope) {
            throw invalidScopeTarget(invalidScope.getMessage());
        }

        String database = normalizeTargetIdentifier(selected.getDatabaseName());
        String schema = normalizeTargetIdentifier(selected.getSchemaName());
        String table = normalizeTargetIdentifier(selected.getTableName());
        boolean mysqlProtocol = isMysqlProtocolFamily(databaseType);
        boolean selectedSchemaRequired = requiresSelectedSchema(databaseType, scope);
        boolean targetSchemaRequired = requiresExplicitSchemaQualifier(databaseType, scope);
        if (ImportScope.TABLE.equals(scope) && table == null) {
            throw invalidScopeTarget("TABLE SQL import requires a target table");
        }
        if (selectedSchemaRequired && schema == null) {
            throw invalidScopeTarget(scope + " SQL import requires a target schema for " + databaseType);
        }
        if (ImportScope.SCHEMA.equals(scope) && (mysqlProtocol ? database == null : schema == null)) {
            throw invalidScopeTarget("SCHEMA SQL import requires a target schema");
        }
        if (ImportScope.DATABASE.equals(scope) && database == null) {
            throw invalidScopeTarget("DATABASE SQL import requires a target database");
        }

        for (ExportedSqlStatementReader.TargetTable target : inspection.targetTables()) {
            if (targetSchemaRequired && target.schema() == null) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "SQL export target " + qualifiedName(target)
                                + " is not schema-qualified for the selected " + scope + " import target",
                        "Qualify every SQL target with the selected schema", null);
            }
            if (!targetWithinScope(target, scope, database, schema, table, mysqlProtocol)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "SQL export target " + qualifiedName(target)
                                + " is outside the selected " + scope + " import target",
                        "Select the matching import target or regenerate the export for that target", null);
            }
        }
    }

    private static boolean targetWithinScope(ExportedSqlStatementReader.TargetTable target, String scope,
            String database, String schema, String table, boolean mysqlProtocol) {
        String targetDatabase = mysqlProtocol ? target.schema() : target.catalog();
        String targetSchema = mysqlProtocol ? null : target.schema();
        if (!qualifierMatches(database, targetDatabase)) {
            return false;
        }
        if (!ImportScope.DATABASE.equals(scope) && !qualifierMatches(schema, targetSchema)) {
            return false;
        }
        return !ImportScope.TABLE.equals(scope) || Objects.equals(table, target.table());
    }

    private static boolean qualifierMatches(String selected, String parsed) {
        return parsed == null || selected != null && Objects.equals(selected, parsed);
    }

    private static String normalizeTargetIdentifier(String identifier) {
        return StringUtils.isEmpty(identifier) ? null : identifier;
    }

    private static boolean isMysqlProtocolFamily(String databaseType) {
        DatabaseTypeEnum database = DatabaseTypeEnum.from(databaseType);
        return database != null && database.isMysqlProtocolFamily();
    }

    private static boolean requiresExplicitSchemaQualifier(String databaseType, String scope) {
        return StringUtils.equalsIgnoreCase(databaseType, DatabaseTypeEnum.POSTGRESQL.name())
                || requiresSelectedSchema(databaseType, scope);
    }

    private static boolean requiresSelectedSchema(String databaseType, String scope) {
        return !ImportScope.DATABASE.equals(scope)
                && StringUtils.equalsAnyIgnoreCase(databaseType,
                        DatabaseTypeEnum.POSTGRESQL.name(),
                        DatabaseTypeEnum.ORACLE.name(),
                        DatabaseTypeEnum.SQLSERVER.name());
    }

    private static String qualifiedName(ExportedSqlStatementReader.TargetTable target) {
        return String.join(".", java.util.stream.Stream.of(target.catalog(), target.schema(), target.table())
                .filter(Objects::nonNull).toList());
    }

    private static TaskExecutionException invalidScopeTarget(String message) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), message,
                "Select an explicit TABLE, SCHEMA, or DATABASE import target", null);
    }

    private <T> T executeExportTransaction(ImportTaskSpec spec, TaskExecutionContext context,
            ExportedSqlStatementReader.Inspection inspection, String databaseType,
            SqlTransactionWork<T> work) throws IOException {
        Connection connection = startExportTransaction();
        T result;
        try {
            preflightTransactionalTargets(connection, context, spec, inspection, databaseType);
            result = work.execute();
            context.checkCancelled();
        } catch (IOException failure) {
            rollbackAndRestore(connection, failure);
            throw failure;
        } catch (RuntimeException failure) {
            rollbackAndRestore(connection, failure);
            throw failure;
        } catch (Error failure) {
            rollbackAndRestore(connection, failure);
            throw failure;
        } catch (SQLException failure) {
            TaskExecutionException wrapped = transactionFailure(
                    "Could not verify transactional SQL import targets", failure);
            rollbackAndRestore(connection, wrapped);
            throw wrapped;
        }

        try {
            context.enterCommitPhase();
        } catch (RuntimeException cancellation) {
            rollbackAndRestore(connection, cancellation);
            throw cancellation;
        }
        try {
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            TaskExecutionException unknown = new TaskExecutionException(COMMIT_OUTCOME_UNKNOWN,
                    "SQL import commit outcome is unknown",
                    "Reconcile imported rows before retrying this task", failure);
            discardConnection(connection, unknown);
            throw unknown;
        }
        restoreAfterSuccessfulCommit(connection, context);
        return result;
    }

    private Connection startExportTransaction() {
        Connection connection = Chat2DBContext.getConnection();
        try {
            if (!connection.getMetaData().supportsTransactions()) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "The target database does not support transactional SQL imports");
            }
        } catch (SQLException failure) {
            TaskExecutionException wrapped = transactionFailure(
                    "Could not verify transactional SQL import support", failure);
            discardConnection(connection, wrapped);
            throw wrapped;
        }
        try {
            if (!connection.getAutoCommit()) {
                TaskExecutionException dirty = new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Third-party SQL import requires a clean auto-commit connection");
                discardConnection(connection, dirty);
                throw dirty;
            }
        } catch (SQLException failure) {
            TaskExecutionException wrapped = transactionFailure(
                    "Could not verify SQL import connection state", failure);
            discardConnection(connection, wrapped);
            throw wrapped;
        }
        try {
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException failure) {
            TaskExecutionException wrapped = transactionFailure(
                    "Could not start transactional SQL import", failure);
            discardConnection(connection, wrapped);
            throw wrapped;
        }
    }

    private void rollbackAndRestore(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            discardConnection(connection, failure);
            return;
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
            discardConnection(connection, failure);
        }
    }

    private void restoreAfterSuccessfulCommit(Connection connection, TaskExecutionContext context) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException restoreFailure) {
            discardConnection(connection, restoreFailure);
            log.warn("SQL import committed but the connection state could not be restored; connection discarded",
                    restoreFailure);
            try {
                context.logWarn("SQL_IMPORT_CONNECTION_DISCARDED",
                        "SQL import committed; the unusable connection was discarded",
                        Map.of("outcome", "COMMITTED", "connectionDiscarded", true));
            } catch (RuntimeException loggingFailure) {
                restoreFailure.addSuppressed(loggingFailure);
                log.warn("Could not persist the post-commit connection warning", loggingFailure);
            }
        }
    }

    private void preflightTransactionalTargets(Connection connection, TaskExecutionContext context,
            ImportTaskSpec spec, ExportedSqlStatementReader.Inspection inspection, String databaseType)
            throws SQLException {
        DatabaseTypeEnum database = DatabaseTypeEnum.from(databaseType);
        Set<ExportedSqlStatementReader.TargetTable> targets = new LinkedHashSet<>(inspection.targetTables());
        if (targets.isEmpty()) {
            return;
        }
        if (database == DatabaseTypeEnum.POSTGRESQL) {
            ActiveNamespace active = verifyActiveNamespace(connection, context, spec,
                    "SELECT current_database(), current_schema()", "PostgreSQL", false);
            preflightPostgresTargets(connection, context, targets, active);
            return;
        }
        if (database == DatabaseTypeEnum.SQLSERVER) {
            ActiveNamespace active = verifyActiveNamespace(connection, context, spec,
                    "SELECT DB_NAME(), SCHEMA_NAME()", "SQL Server", false);
            preflightSqlServerTargets(connection, context, targets, active);
            return;
        }
        if (database == DatabaseTypeEnum.ORACLE) {
            ActiveNamespace active = verifyActiveNamespace(connection, context, spec,
                    "SELECT SYS_CONTEXT('USERENV','DB_NAME'), "
                            + "SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL",
                    "Oracle", true);
            preflightOracleTargets(connection, context, targets, active);
            return;
        }
        if (database == null || !database.isMysqlProtocolFamily()) {
            return;
        }
        preflightMysqlTargets(connection, context, spec, targets, database);
    }

    private ActiveNamespace verifyActiveNamespace(Connection connection, TaskExecutionContext context,
            ImportTaskSpec spec,
            String query, String dialectName, boolean oracleDatabaseContract) throws SQLException {
        TaskTargetSnapshot target = spec == null ? null : spec.getTarget();
        String selectedDatabase = normalizeTargetIdentifier(target == null ? null : target.getDatabaseName());
        String selectedSchema = normalizeTargetIdentifier(target == null ? null : target.getSchemaName());
        if (selectedDatabase == null) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove the selected " + dialectName + " database");
        }

        ActiveNamespace active = queryActiveNamespace(connection, context, query, dialectName);
        if (!Objects.equals(selectedDatabase, active.database())) {
            String message = "Selected and active " + dialectName + " databases do not match";
            if (oracleDatabaseContract) {
                message += "; the selected database must equal "
                        + "SYS_CONTEXT('USERENV','DB_NAME'), not a JDBC service alias";
            }
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), message);
        }
        if (selectedSchema != null && !Objects.equals(selectedSchema, active.schema())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Selected and active " + dialectName + " schemas do not match");
        }
        return active;
    }

    private ActiveNamespace queryActiveNamespace(Connection connection, TaskExecutionContext context,
            String query, String dialectName) throws SQLException {
        PreparedStatement statement = prepare(connection, context, query);
        try {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not read the active " + dialectName + " database and schema");
                }
                String database = normalizeTargetIdentifier(rows.getString(1));
                String schema = normalizeTargetIdentifier(rows.getString(2));
                if (database == null || schema == null) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not prove the active " + dialectName + " database and schema");
                }
                return new ActiveNamespace(database, schema);
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private void preflightPostgresTargets(Connection connection, TaskExecutionContext context,
            Set<ExportedSqlStatementReader.TargetTable> targets, ActiveNamespace active) throws SQLException {
        String metadataSql = "SELECT CAST(c.oid AS VARCHAR), CAST(c.relkind AS VARCHAR), "
                + "(SELECT COUNT(*) FROM pg_catalog.pg_trigger t "
                + "WHERE t.tgrelid=c.oid AND NOT t.tgisinternal AND t.tgenabled <> 'D'), "
                + "(SELECT COUNT(*) FROM pg_catalog.pg_rewrite r "
                + "WHERE r.ev_class=c.oid AND r.rulename <> '_RETURN'), "
                + "(SELECT COUNT(*) FROM pg_catalog.pg_attribute a "
                + "WHERE a.attrelid=c.oid AND a.attnum > 0 AND NOT a.attisdropped "
                + "AND (a.atthasdef OR a.attidentity <> '' OR a.attgenerated <> '')), "
                + "CASE WHEN c.relpersistence='p' THEN 0 ELSE 1 END, "
                + "CASE WHEN c.relrowsecurity OR c.relforcerowsecurity THEN 1 ELSE 0 END, 1 "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=?";
        String columnsSql = "SELECT a.attname FROM pg_catalog.pg_attribute a "
                + "JOIN pg_catalog.pg_class c ON c.oid=a.attrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped "
                + "ORDER BY a.attnum";
        for (ExportedSqlStatementReader.TargetTable target : orderedTargets(targets, active)) {
            if (target.catalog() != null) {
                throw unsafeTarget("PostgreSQL", target, "cross-database targets are not supported");
            }
            String schema = effectiveSchema(target, active);
            TargetMetadata before = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "PostgreSQL");
            requireSafeTarget("PostgreSQL", target, before, "r");
            executeTargetLock(connection, context, "LOCK TABLE "
                    + ansiIdentifier(schema) + "." + ansiIdentifier(target.table())
                    + " IN SHARE ROW EXCLUSIVE MODE NOWAIT");
            TargetMetadata after = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "PostgreSQL");
            requireStableSafeTarget("PostgreSQL", target, before, after, "r",
                    "row-level security configuration", "PostgreSQL catalog");
            requireExactTargetColumns(connection, context, target, schema,
                    DatabaseTypeEnum.POSTGRESQL, columnsSql, "PostgreSQL");
        }
    }

    private void preflightSqlServerTargets(Connection connection, TaskExecutionContext context,
            Set<ExportedSqlStatementReader.TargetTable> targets, ActiveNamespace active) throws SQLException {
        String metadataSql = "SELECT CONVERT(varchar(128),o.object_id), o.type, "
                + "(SELECT COUNT(*) FROM sys.triggers tr "
                + "WHERE tr.parent_id=o.object_id AND tr.is_disabled=0), 0, "
                + "((SELECT COUNT(*) FROM sys.columns c WHERE c.object_id=o.object_id "
                + "AND (c.is_identity=1 OR c.is_computed=1 OR c.default_object_id<>0 "
                + "OR c.system_type_id=189 OR c.is_hidden=1 OR c.is_column_set=1 "
                + "OR c.generated_always_type<>0)) "
                + "+ CASE WHEN ISNULL(t.temporal_type,0)<>0 THEN 1 ELSE 0 END), 0, "
                + "((SELECT COUNT(*) FROM sys.sql_expression_dependencies d "
                + "JOIN sys.views v ON v.object_id=d.referencing_id "
                + "WHERE d.referenced_id=o.object_id AND EXISTS "
                + "(SELECT 1 FROM sys.indexes i WHERE i.object_id=v.object_id "
                + "AND i.index_id>0 AND i.is_hypothetical=0)) "
                + "+ CASE WHEN ISNULL(t.is_tracked_by_cdc,0)=1 THEN 1 ELSE 0 END "
                + "+ (SELECT COUNT(*) FROM sys.change_tracking_tables ct "
                + "WHERE ct.object_id=o.object_id)), "
                + "CASE WHEN IS_SRVROLEMEMBER('sysadmin')=1 THEN 1 ELSE 0 END "
                + "FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "LEFT JOIN sys.tables t ON t.object_id=o.object_id "
                + "WHERE s.name=? AND o.name=? AND o.type IN ('U','V','SN')";
        String columnsSql = "SELECT c.name FROM sys.columns c "
                + "JOIN sys.objects o ON o.object_id=c.object_id "
                + "JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "WHERE s.name=? AND o.name=? AND o.type='U' ORDER BY c.column_id";
        for (ExportedSqlStatementReader.TargetTable target : orderedTargets(targets, active)) {
            if (target.catalog() != null && !Objects.equals(active.database(), target.catalog())) {
                throw unsafeTarget("SQL Server", target, "cross-database targets are not supported");
            }
            String schema = effectiveSchema(target, active);
            TargetMetadata before = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "SQL Server");
            requireSafeTarget("SQL Server", target, before, "U");
            executeTargetLock(connection, context, "SELECT TOP (1) 1 FROM "
                    + sqlServerIdentifier(schema) + "." + sqlServerIdentifier(target.table())
                    + " WITH (TABLOCKX, HOLDLOCK)");
            TargetMetadata after = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "SQL Server");
            requireStableSafeTarget("SQL Server", target, before, after, "U",
                    "indexed view, CDC, or change tracking side effect",
                    "sysadmin-scoped SQL Server catalog");
            requireExactTargetColumns(connection, context, target, schema,
                    DatabaseTypeEnum.SQLSERVER, columnsSql, "SQL Server");
        }
    }

    private void preflightOracleTargets(Connection connection, TaskExecutionContext context,
            Set<ExportedSqlStatementReader.TargetTable> targets, ActiveNamespace active) throws SQLException {
        String metadataSql = "SELECT TO_CHAR(o.object_id), o.object_type, "
                + "(SELECT COUNT(*) FROM dba_triggers tr WHERE tr.table_owner=o.owner "
                + "AND tr.table_name=o.object_name AND tr.status='ENABLED'), 0, "
                + "(SELECT COUNT(*) FROM dba_tab_cols c WHERE c.owner=o.owner "
                + "AND c.table_name=o.object_name "
                + "AND (NVL(c.hidden_column,'NO')='YES' OR NVL(c.identity_column,'NO')='YES' "
                + "OR NVL(c.virtual_column,'NO')='YES' OR NVL(c.default_on_null,'NO')='YES' "
                + "OR NVL(c.default_length,0)>0)), "
                + "CASE WHEN t.owner IS NOT NULL AND NVL(t.temporary,'N')='N' "
                + "AND t.duration IS NULL THEN 0 ELSE 1 END, "
                + "((SELECT COUNT(*) FROM dba_mview_logs ml "
                + "WHERE ml.log_owner=o.owner AND ml.master=o.object_name) "
                + "+ (SELECT COUNT(*) FROM dba_mview_detail_relations d JOIN dba_mviews mv "
                + "ON mv.owner=d.owner AND mv.mview_name=d.mview_name "
                + "WHERE d.detailobj_owner=o.owner AND d.detailobj_name=o.object_name "
                + "AND mv.refresh_mode IN ('COMMIT','STATEMENT')) "
                + "+ (SELECT COUNT(*) FROM dba_dependencies d JOIN dba_mviews mv "
                + "ON mv.owner=d.owner AND mv.mview_name=d.name "
                + "WHERE d.type='MATERIALIZED VIEW' AND d.referenced_type='TABLE' "
                + "AND d.referenced_owner=o.owner AND d.referenced_name=o.object_name "
                + "AND mv.refresh_mode IN ('COMMIT','STATEMENT'))), "
                + "1 "
                + "FROM dba_objects o LEFT JOIN dba_tables t "
                + "ON t.owner=o.owner AND t.table_name=o.object_name "
                + "WHERE o.owner=? AND o.object_name=? "
                + "AND o.object_type IN ('TABLE','VIEW','SYNONYM','MATERIALIZED VIEW')";
        String columnsSql = "SELECT c.column_name FROM dba_tab_cols c "
                + "WHERE c.owner=? AND c.table_name=? "
                + "AND NVL(c.hidden_column,'NO')='NO' ORDER BY c.column_id";
        for (ExportedSqlStatementReader.TargetTable target : orderedTargets(targets, active)) {
            if (target.catalog() != null) {
                throw unsafeTarget("Oracle", target, "database-link and catalog targets are not supported");
            }
            String schema = effectiveSchema(target, active);
            TargetMetadata before = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "Oracle");
            requireSafeTarget("Oracle", target, before, "TABLE");
            executeTargetLock(connection, context, "LOCK TABLE "
                    + ansiIdentifier(schema) + "." + ansiIdentifier(target.table())
                    + " IN EXCLUSIVE MODE NOWAIT");
            TargetMetadata after = queryTargetMetadata(connection, context, metadataSql,
                    schema, target.table(), "Oracle");
            requireStableSafeTarget("Oracle", target, before, after, "TABLE",
                    "materialized view log or COMMIT/STATEMENT materialized view refresh side effect",
                    "Oracle dictionary");
            requireExactTargetColumns(connection, context, target, schema,
                    DatabaseTypeEnum.ORACLE, columnsSql, "Oracle");
        }
    }

    private String effectiveSchema(ExportedSqlStatementReader.TargetTable target, ActiveNamespace active) {
        return StringUtils.defaultIfEmpty(target.schema(), active.schema());
    }

    private List<ExportedSqlStatementReader.TargetTable> orderedTargets(
            Set<ExportedSqlStatementReader.TargetTable> targets, ActiveNamespace active) {
        Comparator<ExportedSqlStatementReader.TargetTable> resolvedOrder = Comparator
                .comparing((ExportedSqlStatementReader.TargetTable target) ->
                                StringUtils.defaultIfEmpty(target.catalog(), active.database()),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(target -> effectiveSchema(target, active), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ExportedSqlStatementReader.TargetTable::table, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(target -> StringUtils.defaultIfEmpty(target.catalog(), active.database()))
                .thenComparing(target -> effectiveSchema(target, active))
                .thenComparing(ExportedSqlStatementReader.TargetTable::table)
                .thenComparing(TARGET_LOCK_ORDER);
        return targets.stream().sorted(resolvedOrder).toList();
    }

    private TargetMetadata queryTargetMetadata(Connection connection, TaskExecutionContext context,
            String query, String schema, String table, String dialectName) throws SQLException {
        PreparedStatement statement = prepare(connection, context, query);
        try {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not prove that SQL import target " + schema + "." + table
                                    + " is a local " + dialectName + " base table");
                }
                TargetMetadata metadata = new TargetMetadata(
                        StringUtils.trimToNull(rows.getString(1)),
                        StringUtils.trimToNull(rows.getString(2)),
                        checkedMetadataCount(rows.getLong(3), dialectName, "trigger"),
                        checkedMetadataCount(rows.getLong(4), dialectName, "rewrite rule"),
                        checkedMetadataCount(rows.getLong(5), dialectName, "generated/default column"),
                        checkedMetadataCount(rows.getLong(6), dialectName, "nonpersistent object"),
                        checkedMetadataCount(rows.getLong(7), dialectName, "database side effect"),
                        rows.getLong(8) == 1L && !rows.wasNull());
                if (metadata.objectId() == null || metadata.objectType() == null || rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Ambiguous or incomplete " + dialectName + " target metadata for "
                                    + schema + "." + table);
                }
                return metadata;
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private int checkedMetadataCount(long count, String dialectName, String kind) {
        if (count < 0L || count > Integer.MAX_VALUE) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Invalid " + dialectName + " " + kind + " metadata count");
        }
        return (int) count;
    }

    private void requireStableSafeTarget(String dialectName,
            ExportedSqlStatementReader.TargetTable target, TargetMetadata before,
            TargetMetadata after, String expectedType, String sideEffectDescription,
            String visibilityDescription) {
        if (!Objects.equals(before.objectId(), after.objectId())) {
            throw unsafeTarget(dialectName, target, "the target object changed during preflight");
        }
        requireSafeTarget(dialectName, target, after, expectedType);
        if (!after.metadataVisibilityProven()) {
            throw unsafeTarget(dialectName, target,
                    "complete " + visibilityDescription + " metadata visibility could not be proven");
        }
        if (after.sideEffectCount() != 0) {
            throw unsafeTarget(dialectName, target,
                    "it has " + after.sideEffectCount() + " " + sideEffectDescription + "(s)");
        }
    }

    private void requireSafeTarget(String dialectName, ExportedSqlStatementReader.TargetTable target,
            TargetMetadata metadata, String expectedType) {
        if (!expectedType.equalsIgnoreCase(metadata.objectType())) {
            throw unsafeTarget(dialectName, target,
                    "object type " + metadata.objectType() + " is not a local base table");
        }
        if (metadata.triggerCount() != 0) {
            throw unsafeTarget(dialectName, target,
                    "it has " + metadata.triggerCount() + " enabled trigger(s)");
        }
        if (metadata.rewriteRuleCount() != 0) {
            throw unsafeTarget(dialectName, target,
                    "it has " + metadata.rewriteRuleCount() + " rewrite rule(s)");
        }
        if (metadata.generatedColumnCount() != 0) {
            throw unsafeTarget(dialectName, target,
                    "it has " + metadata.generatedColumnCount()
                            + " identity, generated, temporal, or defaulted column(s)");
        }
        if (metadata.nonPersistentCount() != 0) {
            throw unsafeTarget(dialectName, target,
                    "it is a temporary, unlogged, or otherwise nonpersistent table");
        }
    }

    private TaskExecutionException unsafeTarget(String dialectName,
            ExportedSqlStatementReader.TargetTable target, String reason) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "Atomic SQL import rejects " + dialectName + " target " + qualifiedName(target)
                        + " because " + reason);
    }

    private void executeTargetLock(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        PreparedStatement statement = prepare(connection, context, sql);
        try {
            statement.setQueryTimeout(TARGET_LOCK_TIMEOUT_SECONDS);
            statement.execute();
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private void requireExactTargetColumns(Connection connection, TaskExecutionContext context,
            ExportedSqlStatementReader.TargetTable target, String schema, DatabaseTypeEnum databaseType,
            String query, String dialectName) throws SQLException {
        if (target.columns().isEmpty()) {
            return;
        }
        Set<String> expected = new LinkedHashSet<>();
        for (ExportedSqlStatementReader.ColumnReference column : target.columns()) {
            String canonical = comparableColumnName(column.name(), databaseType);
            if (!expected.add(canonical)) {
                throw unsafeTarget(dialectName, target,
                        "its explicit column list is ambiguous for the target identifier rules");
            }
        }

        PreparedStatement statement = prepare(connection, context, query);
        try {
            statement.setString(1, schema);
            statement.setString(2, target.table());
            Set<String> actual = new LinkedHashSet<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString(1);
                    if (StringUtils.isEmpty(column)
                            || !actual.add(comparableColumnName(column, databaseType))) {
                        throw unsafeTarget(dialectName, target,
                                "its target column metadata is incomplete or ambiguous");
                    }
                }
            }
            if (actual.isEmpty()) {
                throw unsafeTarget(dialectName, target, "its target columns could not be proven");
            }
            if (!actual.equals(expected)) {
                throw unsafeTarget(dialectName, target,
                        "its explicit " + expected.size() + "-column set does not exactly match all "
                                + actual.size() + " target columns");
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private String comparableColumnName(String column, DatabaseTypeEnum databaseType) {
        if (databaseType != null && databaseType.isMysqlProtocolFamily()) {
            return column.toLowerCase(Locale.ROOT);
        }
        return column;
    }

    private String ansiIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String sqlServerIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    private void preflightMysqlTargets(Connection connection, TaskExecutionContext context, ImportTaskSpec spec,
            Set<ExportedSqlStatementReader.TargetTable> targets, DatabaseTypeEnum databaseType)
            throws SQLException {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        TaskTargetSnapshot selectedTarget = spec == null ? null : spec.getTarget();
        String selectedDatabase = normalizeTargetIdentifier(
                selectedTarget == null ? null : selectedTarget.getDatabaseName());
        String configuredDatabase = normalizeTargetIdentifier(
                connectInfo == null ? null : connectInfo.getDatabaseName());
        String connectionDatabase = normalizeTargetIdentifier(connection.getCatalog());
        if (selectedDatabase == null || configuredDatabase == null || connectionDatabase == null) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove the selected, configured, and active MySQL target database");
        }
        if (!selectedDatabase.equals(configuredDatabase) || !selectedDatabase.equals(connectionDatabase)) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Selected, configured, and active MySQL target databases do not match");
        }
        String currentDatabase = selectedDatabase;

        if (databaseType == DatabaseTypeEnum.MYSQL) {
            requireMysqlGipkMetadataVisibility(connection, context);
        }

        List<ExportedSqlStatementReader.TargetTable> orderedTargets = orderedTargets(
                targets, new ActiveNamespace(currentDatabase, currentDatabase));

        for (ExportedSqlStatementReader.TargetTable target : orderedTargets) {
            requireLocalMysqlTarget(currentDatabase, target);
            lockMysqlTarget(connection, context, currentDatabase, target.table());
            requirePersistentMysqlResolution(connection, context, currentDatabase, target.table());
        }
        boolean requireTriggerProof = databaseType == DatabaseTypeEnum.MYSQL
                || databaseType == DatabaseTypeEnum.MARIADB;
        List<String> grantsBefore = requireTriggerProof ? mysqlCurrentUserGrants(connection, context) : List.of();
        for (ExportedSqlStatementReader.TargetTable target : orderedTargets) {
            MysqlTargetMetadata metadata = mysqlTargetMetadata(
                    connection, context, currentDatabase, target.table());
            if (!"BASE TABLE".equalsIgnoreCase(StringUtils.trimToEmpty(metadata.tableType()))) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Atomic SQL import rejects MySQL target " + currentDatabase + "." + target.table()
                                + " because object type "
                                + StringUtils.defaultIfBlank(metadata.tableType(), "UNKNOWN")
                                + " is not a base table");
            }
            if (!"INNODB".equalsIgnoreCase(StringUtils.trimToEmpty(metadata.engine()))) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Atomic SQL import is not supported for MySQL target "
                                + currentDatabase + "." + target.table()
                                + " with storage engine "
                                + StringUtils.defaultIfBlank(metadata.engine(), "UNKNOWN"));
            }
            if (metadata.generatedColumnCount() != 0) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Atomic SQL import rejects MySQL target " + currentDatabase + "." + target.table()
                                + " because it has " + metadata.generatedColumnCount()
                                + " auto-increment, generated, on-update, or defaulted column(s)");
            }
            if (requireTriggerProof) {
                requireMysqlTriggerVisibility(grantsBefore, currentDatabase, target.table());
                int triggerCount = mysqlTriggerCount(connection, context, currentDatabase, target.table());
                if (triggerCount != 0) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Atomic SQL import rejects MySQL target " + currentDatabase + "." + target.table()
                                    + " because it has " + triggerCount + " trigger(s)");
                }
            }
            requireExactTargetColumns(connection, context, target, currentDatabase, databaseType,
                    "SELECT c.COLUMN_NAME FROM information_schema.COLUMNS c "
                            + "WHERE c.TABLE_SCHEMA=? AND c.TABLE_NAME=? ORDER BY c.ORDINAL_POSITION",
                    "MySQL");
        }
        if (requireTriggerProof) {
            List<String> grantsAfter = mysqlCurrentUserGrants(connection, context);
            for (ExportedSqlStatementReader.TargetTable target : orderedTargets) {
                requireMysqlTriggerVisibility(grantsAfter, currentDatabase, target.table());
            }
        }
    }

    private void requireLocalMysqlTarget(String currentDatabase,
            ExportedSqlStatementReader.TargetTable target) {
        if (target.catalog() != null) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Three-part or remote MySQL targets are not supported in atomic SQL import");
        }
        if (target.schema() != null && !currentDatabase.equals(target.schema())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Cross-database MySQL target is not supported in atomic SQL import");
        }
    }

    private void lockMysqlTarget(Connection connection, TaskExecutionContext context,
            String database, String table) throws SQLException {
        String sql = "SELECT 1 FROM " + mysqlIdentifier(database) + "." + mysqlIdentifier(table)
                + " LIMIT 0 FOR UPDATE";
        PreparedStatement statement = prepare(connection, context, sql);
        try {
            statement.setQueryTimeout(TARGET_LOCK_TIMEOUT_SECONDS);
            try (ResultSet ignored = statement.executeQuery()) {
                // A locking read holds the metadata lock until commit, closing the engine-check race.
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private MysqlTargetMetadata mysqlTargetMetadata(Connection connection, TaskExecutionContext context,
            String database, String table) throws SQLException {
        PreparedStatement statement = prepare(connection, context,
                "SELECT t.TABLE_TYPE, t.ENGINE, "
                        + "(SELECT COUNT(*) FROM information_schema.COLUMNS c "
                        + "WHERE c.TABLE_SCHEMA=t.TABLE_SCHEMA AND c.TABLE_NAME=t.TABLE_NAME "
                        + "AND (c.COLUMN_DEFAULT IS NOT NULL OR COALESCE(c.EXTRA,'')<>'' "
                        + "OR COALESCE(c.GENERATION_EXPRESSION,'')<>'')) "
                        + "FROM information_schema.TABLES t WHERE t.TABLE_SCHEMA=? AND t.TABLE_NAME=?");
        try {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not verify MySQL base-table metadata for " + database + "." + table);
                }
                MysqlTargetMetadata metadata = new MysqlTargetMetadata(
                        rows.getString(1), rows.getString(2),
                        checkedMetadataCount(rows.getLong(3), "MySQL", "generated/default column"));
                if (rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Ambiguous MySQL target metadata for " + database + "." + table);
                }
                return metadata;
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private List<String> mysqlCurrentUserGrants(Connection connection, TaskExecutionContext context)
            throws SQLException {
        PreparedStatement statement = prepare(connection, context, "SHOW GRANTS FOR CURRENT_USER");
        try {
            List<String> grants = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String grant = StringUtils.trimToNull(rows.getString(1));
                    if (grant != null) {
                        grants.add(grant);
                    }
                }
            }
            if (grants.isEmpty()) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Could not prove MySQL trigger metadata visibility for the current user");
            }
            return List.copyOf(grants);
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private void requireMysqlTriggerVisibility(List<String> grants, String database, String table) {
        boolean visibilityGrant = false;
        for (String grant : grants) {
            String normalized = StringUtils.trimToEmpty(grant);
            if (StringUtils.startsWithIgnoreCase(normalized, "REVOKE")) {
                Matcher revoke = MYSQL_REVOKE.matcher(normalized);
                if (!revoke.find()) {
                    throw unprovenMysqlTriggerVisibility(database, table,
                            "an unrecognized partial revoke was returned by SHOW GRANTS");
                }
                if (mysqlPrivilegeListIncludesTrigger(revoke.group(1))
                        && mysqlPrivilegeScopeCoversTarget(revoke.group(2), database, table)) {
                    throw unprovenMysqlTriggerVisibility(database, table,
                            "TRIGGER is partially revoked for the target");
                }
                continue;
            }
            visibilityGrant |= mysqlGrantProvesTriggerVisibility(normalized, database, table);
        }
        if (!visibilityGrant) {
            throw unprovenMysqlTriggerVisibility(database, table,
                    "a direct TRIGGER or ALL PRIVILEGES grant is required");
        }
    }

    private TaskExecutionException unprovenMysqlTriggerVisibility(String database, String table, String reason) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "Could not prove complete MySQL trigger metadata visibility for " + database + "." + table
                        + "; " + reason);
    }

    private boolean mysqlPrivilegeListIncludesTrigger(String privilegeList) {
        for (String privilege : StringUtils.defaultString(privilegeList).split(",")) {
            String normalized = privilege.trim().toUpperCase(Locale.ROOT);
            if ("TRIGGER".equals(normalized) || "ALL".equals(normalized)
                    || "ALL PRIVILEGES".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean mysqlPrivilegeScopeCoversTarget(String rawScope, String database, String table) {
        String scope = StringUtils.trimToEmpty(rawScope).replaceAll("\\s*\\.\\s*", ".");
        return "*.*".equals(scope)
                || (mysqlIdentifier(database) + ".*").equals(scope)
                || (mysqlIdentifier(database) + "." + mysqlIdentifier(table)).equals(scope)
                || (database + ".*").equals(scope)
                || (database + "." + table).equals(scope);
    }

    private boolean mysqlGrantProvesTriggerVisibility(String grant, String database, String table) {
        Matcher matcher = MYSQL_GRANT.matcher(StringUtils.trimToEmpty(grant));
        if (!matcher.find() || !mysqlPrivilegeListIncludesTrigger(matcher.group(1))) {
            return false;
        }
        return mysqlPrivilegeScopeCoversTarget(matcher.group(2), database, table);
    }

    private int mysqlTriggerCount(Connection connection, TaskExecutionContext context,
            String database, String table) throws SQLException {
        PreparedStatement statement = prepare(connection, context,
                "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                        + "WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?");
        try {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not verify MySQL triggers for " + database + "." + table);
                }
                long count = rows.getLong(1);
                if (count < 0L || count > Integer.MAX_VALUE) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Invalid MySQL trigger metadata for " + database + "." + table);
                }
                return (int) count;
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private void requirePersistentMysqlResolution(Connection connection, TaskExecutionContext context,
            String database, String table) throws SQLException {
        PreparedStatement statement = prepare(connection, context,
                "SHOW CREATE TABLE " + mysqlIdentifier(database) + "." + mysqlIdentifier(table));
        try {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Could not prove the resolved MySQL table for " + database + "." + table);
                }
                String ddl = StringUtils.trimToNull(rows.getString(2));
                if (ddl == null || rows.next()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Ambiguous MySQL resolved-table metadata for " + database + "." + table);
                }
                if (MYSQL_TEMPORARY_TABLE_DDL.matcher(ddl).find()) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "Atomic SQL import rejects MySQL target " + database + "." + table
                                    + " because a session temporary table shadows the persistent target");
                }
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private void requireMysqlGipkMetadataVisibility(Connection connection, TaskExecutionContext context)
            throws SQLException {
        DatabaseMetaData databaseMetaData = connection.getMetaData();
        String productName = StringUtils.trimToNull(databaseMetaData.getDatabaseProductName());
        if (productName == null) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove the MySQL or MariaDB server product");
        }
        String normalizedProduct = productName.toUpperCase(Locale.ROOT);
        if (normalizedProduct.contains("MARIADB")) {
            return;
        }
        if (!normalizedProduct.equals("MYSQL") && !normalizedProduct.startsWith("MYSQL ")) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove whether the target server supports MySQL GIPK metadata visibility");
        }

        int major = databaseMetaData.getDatabaseMajorVersion();
        int minor = databaseMetaData.getDatabaseMinorVersion();
        if (major <= 0 || minor < 0) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove the MySQL server version for GIPK metadata visibility");
        }
        if (major < 8) {
            return;
        }
        if (major == 8 && minor == 0) {
            int patch = mysqlPatchVersion(databaseMetaData.getDatabaseProductVersion(), major, minor);
            if (patch < 30) {
                return;
            }
        }

        PreparedStatement statement;
        try {
            statement = prepare(connection, context,
                    "SELECT @@SESSION.show_gipk_in_create_table_and_information_schema");
        } catch (SQLException failure) {
            throw mysqlGipkVisibilityFailure(failure);
        }
        try {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw mysqlGipkVisibilityFailure(null);
                }
                String value = StringUtils.trimToEmpty(rows.getString(1));
                if (!("ON".equalsIgnoreCase(value) || "1".equals(value)) || rows.next()) {
                    throw mysqlGipkVisibilityFailure(null);
                }
            } catch (SQLException failure) {
                throw mysqlGipkVisibilityFailure(failure);
            }
        } finally {
            closePreparedStatement(context, statement);
        }
    }

    private int mysqlPatchVersion(String productVersion, int expectedMajor, int expectedMinor) {
        Matcher matcher = MYSQL_PATCH_VERSION.matcher(StringUtils.trimToEmpty(productVersion));
        if (!matcher.matches()) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not prove the MySQL 8.0 patch version for GIPK metadata visibility");
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            if (major != expectedMajor || minor != expectedMinor) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Inconsistent MySQL server version metadata for GIPK visibility");
            }
            return patch;
        } catch (NumberFormatException invalidVersion) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Invalid MySQL server version metadata for GIPK visibility", invalidVersion);
        }
    }

    private TaskExecutionException mysqlGipkVisibilityFailure(Throwable cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "MySQL 8.0.30+ SQL import requires "
                        + "@@SESSION.show_gipk_in_create_table_and_information_schema=ON",
                cause);
    }

    private String mysqlIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private record TargetMetadata(String objectId, String objectType, int triggerCount,
                                  int rewriteRuleCount, int generatedColumnCount, int nonPersistentCount,
                                  int sideEffectCount, boolean metadataVisibilityProven) {
    }

    private record MysqlTargetMetadata(String tableType, String engine, int generatedColumnCount) {
    }

    private record ActiveNamespace(String database, String schema) {
    }

    private PreparedStatement prepare(Connection connection, TaskExecutionContext context, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        context.onStatementCreated(statement);
        return statement;
    }

    private void closePreparedStatement(TaskExecutionContext context, PreparedStatement statement) {
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

    private void discardConnection(Connection fallback, Throwable failure) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        Connection connection = connectInfo == null || connectInfo.getConnection() == null
                ? fallback : connectInfo.getConnection();
        if (connectInfo != null) {
            connectInfo.setConnection(null);
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private TaskExecutionException transactionFailure(String message, SQLException cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), message, cause);
    }

    private void flushExportedSqlBatch(TaskExecutionContext context, ImportSqlExecutor sqlExecutor,
            List<String> batch, AtomicLong batchChars) {
        if (batch.isEmpty()) {
            return;
        }
        context.checkCancelled();
        sqlExecutor.executeBatch(batch);
        batch.clear();
        batchChars.set(0L);
    }

    private void rejectUnsupportedDirective(TaskExecutionContext context,
            ExportedSqlStatementReader.Inspection inspection) {
        ExportedSqlStatementReader.UnsupportedDirective directive = inspection.unsupported();
        if (directive == null) {
            return;
        }
        context.logError("SQL_EXPORT_DIRECTIVE_UNSUPPORTED",
                "SQL export contains an unsupported directive",
                Map.of("exporterProfile", directive.profile().name(),
                        "directive", directive.code(),
                        "line", directive.line(),
                        "statementNumber", directive.statementNumber(),
                        "recommendation", directive.recommendation()));
        throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "Unsupported SQL export directive " + directive.code() + " at line " + directive.line(),
                directive.recommendation(), null);
    }

    private void ensureSourceUnchanged(File sourceFile, long expectedSize, long expectedModifiedAt)
            throws IOException {
        if (Files.size(sourceFile.toPath()) != expectedSize
                || Files.getLastModifiedTime(sourceFile.toPath()).toMillis() != expectedModifiedAt) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "SQL export changed while it was being validated");
        }
    }

    private void ensureInspectionMatches(ExportedSqlStatementReader.Inspection inspected,
            ExportedSqlStatementReader.Inspection streamed) {
        if (inspected.statementCount() != streamed.statementCount()
                || inspected.dataStatementCount() != streamed.dataStatementCount()
                || inspected.filteredStatementCount() != streamed.filteredStatementCount()
                || !Objects.equals(inspected.targetTables(), streamed.targetTables())
                || !Objects.equals(inspected.sourceSha256(), streamed.sourceSha256())) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "SQL export changed after validation");
        }
    }

    @FunctionalInterface
    private interface SqlTransactionWork<T> {
        T execute() throws IOException;
    }

    private TaskExecutionException invalidExport(IllegalArgumentException cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                "Could not parse third-party SQL export", cause.getMessage(), cause);
    }

    private void setProgress(TaskExecutionContext context, long i, long size, StringBuilder processStr) {
        long progress = size <= 0 ? 99 : i * 100 / size;
        Integer p = Integer.valueOf(progress + "");
        if (p >= 100) {
            p = 99;
        }
        if (!processStr.toString().equals(p.toString())) {
            processStr.setLength(0);
            processStr.append(p);
            context.reportProgress(p, TaskStage.IMPORTING.name(),
                    DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + " all bytes:" + size
                            + ",current bytes:" + i + ",progress:" + progress + "%");
        }

    }
}
