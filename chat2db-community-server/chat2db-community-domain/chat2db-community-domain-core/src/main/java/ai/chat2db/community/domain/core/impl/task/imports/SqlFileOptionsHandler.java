package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Executes SQL import statements with explicit error and commit policies. */
public final class SqlFileOptionsHandler implements ISqlBatchHandler {

    public static final String MODE_SCRIPT = "SCRIPT";
    public static final String MODE_BATCH = "BATCH";
    public static final String MODE_SINGLE_TRANSACTION = "SINGLE_TRANSACTION";
    public static final String POLICY_STOP = "STOP";
    public static final String POLICY_CONTINUE = "CONTINUE";
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final TaskExecutionContext context;
    private final Connection connection;
    private final String commitMode;
    private final String errorPolicy;
    private final int batchSize;
    private int statementNumber;
    private int successfulStatements;
    private int failedStatements;
    private int statementsInTransaction;
    private boolean transactionStarted;
    private Boolean originalAutoCommit;

    public static boolean supportsOptions(ImportTaskSpec spec) {
        return !MODE_SCRIPT.equals(normalize(StringUtils.defaultIfBlank(spec.getCommitMode(), MODE_SCRIPT)))
                || POLICY_CONTINUE.equals(normalize(StringUtils.defaultIfBlank(spec.getErrorPolicy(), POLICY_STOP)));
    }

    public SqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context) {
        this(spec, context, Chat2DBContext.getConnection());
    }

    SqlFileOptionsHandler(ImportTaskSpec spec, TaskExecutionContext context, Connection connection) {
        this.context = context;
        this.connection = connection;
        this.commitMode = normalize(StringUtils.defaultIfBlank(spec.getCommitMode(), MODE_SCRIPT));
        this.errorPolicy = normalize(StringUtils.defaultIfBlank(spec.getErrorPolicy(), POLICY_STOP));
        this.batchSize = spec.getBatchSize() == null || spec.getBatchSize() < 1
                ? DEFAULT_BATCH_SIZE : spec.getBatchSize();
        if (!MODE_SCRIPT.equals(commitMode) && !MODE_BATCH.equals(commitMode)
                && !MODE_SINGLE_TRANSACTION.equals(commitMode)) {
            throw fail("Unsupported SQL import commit mode: " + commitMode, null);
        }
        if (!POLICY_STOP.equals(errorPolicy) && !POLICY_CONTINUE.equals(errorPolicy)) {
            throw fail("Unsupported SQL import error policy: " + errorPolicy, null);
        }
    }

    @Override
    public void handle(Statement statement) {
        context.checkCancelled();
        String sql = statement.getSql() == null ? "" : statement.getSql().trim();
        if (StringUtils.isBlank(sql) || ";".equals(sql)) {
            return;
        }
        statementNumber++;
        if (!MODE_SCRIPT.equals(commitMode)) {
            validateTransactionSafe(sql);
            beginTransaction();
        }
        java.sql.Statement jdbcStatement = null;
        try {
            jdbcStatement = connection.createStatement();
            context.onStatementCreated(jdbcStatement);
            jdbcStatement.execute(sql);
            successfulStatements++;
            statementsInTransaction++;
            if (MODE_BATCH.equals(commitMode) && statementsInTransaction >= batchSize) {
                commit();
            }
        } catch (SQLException e) {
            failedStatements++;
            logFailure(sql, e);
            if (POLICY_STOP.equals(errorPolicy) || MODE_SINGLE_TRANSACTION.equals(commitMode)) {
                rollback();
                throw fail("SQL import failed at statement " + statementNumber, e);
            }
        } finally {
            if (jdbcStatement != null) {
                try {
                    jdbcStatement.close();
                } catch (SQLException ignored) {
                    // The connection lifecycle owns cleanup after a statement close failure.
                }
                context.onStatementClosed(jdbcStatement);
            }
        }
    }

    @Override
    public void flush() {
        try {
            context.checkCancelled();
            if (transactionStarted) {
                commit();
            }
            context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "SQL file execution completed", summary());
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } finally {
            restoreAutoCommit();
        }
    }

    private void beginTransaction() {
        if (transactionStarted) {
            return;
        }
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            transactionStarted = true;
        } catch (SQLException e) {
            throw fail("Could not start SQL import transaction", e);
        }
    }

    private void commit() {
        try {
            connection.commit();
            statementsInTransaction = 0;
            transactionStarted = false;
        } catch (SQLException e) {
            throw fail("Could not commit SQL import transaction", e);
        }
    }

    private void rollback() {
        if (!transactionStarted) {
            return;
        }
        try {
            connection.rollback();
            statementsInTransaction = 0;
            transactionStarted = false;
        } catch (SQLException ignored) {
            // The original failure is more useful to the task result than a rollback failure.
        }
    }

    private void restoreAutoCommit() {
        if (originalAutoCommit == null) {
            return;
        }
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ignored) {
            // The connection is task-scoped and will be closed by task cleanup.
        }
    }

    private void validateTransactionSafe(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.startsWith("START TRANSACTION") || upper.startsWith("BEGIN") || upper.startsWith("COMMIT")
                || upper.startsWith("ROLLBACK") || upper.startsWith("SET AUTOCOMMIT")) {
            throw fail("Transaction-control SQL requires SCRIPT commit mode", null);
        }
        if (upper.startsWith("CREATE") || upper.startsWith("ALTER") || upper.startsWith("DROP")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME") || upper.startsWith("GRANT")
                || upper.startsWith("REVOKE") || upper.startsWith("FLUSH") || upper.startsWith("LOCK")
                || upper.startsWith("UNLOCK") || upper.startsWith("OPTIMIZE") || upper.startsWith("ANALYZE")
                || upper.startsWith("CHECK ") || upper.startsWith("REPAIR") || upper.startsWith("LOAD ")
                || upper.startsWith("SET ")) {
            throw fail("DDL or implicit-commit SQL requires SCRIPT commit mode", null);
        }
    }

    private void logFailure(String sql, SQLException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("statement", statementNumber);
        details.put("sql", sql.length() <= 200 ? sql : sql.substring(0, 200) + "...");
        details.put("errorCode", e.getErrorCode());
        details.put("sqlState", e.getSQLState());
        details.put("message", e.getMessage());
        context.logError(TaskEventCode.OBJECT_SKIPPED.name(), "SQL statement failed", details);
    }

    private Map<String, Object> summary() {
        return Map.of("successfulStatements", successfulStatements, "failedStatements", failedStatements,
                "commitMode", commitMode, "errorPolicy", errorPolicy);
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static TaskExecutionException fail(String message, Throwable cause) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), message, cause);
    }
}
