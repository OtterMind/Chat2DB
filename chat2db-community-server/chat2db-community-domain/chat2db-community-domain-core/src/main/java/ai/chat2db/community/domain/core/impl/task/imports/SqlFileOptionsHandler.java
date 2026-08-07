package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.domain.api.service.db.ISqlBatchHandler;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement as JdbcStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL-file execution with encoding/error/commit options (MYSQL-IMPORT-004).
 *
 * <p>Commit modes:
 * <ul>
 *   <li>{@code SCRIPT} — follow transaction statements in the file; other statements are
 *       committed individually (autocommit).</li>
 *   <li>{@code BATCH} — autocommit off, commit after {@code batchSize} successful
 *       statements; stop/cancel rolls back the current batch and preserves earlier
 *       batches; continue commits only statements confirmed successful.</li>
 *   <li>{@code SINGLE_TRANSACTION} — autocommit off, one commit after complete success;
 *       any error or cancellation rolls everything back.</li>
 * </ul>
 * BATCH and SINGLE_TRANSACTION reject scripts containing transaction-control statements
 * (START TRANSACTION / BEGIN / COMMIT / ROLLBACK / SET autocommit) and DDL /
 * implicit-commit statements before execution, with guidance to use SCRIPT mode.
 */
@Slf4j
public class SqlFileOptionsHandler implements ISqlBatchHandler {

    public static final String MODE_SCRIPT = "SCRIPT";
    public static final String MODE_BATCH = "BATCH";
    public static final String MODE_SINGLE = "SINGLE_TRANSACTION";
    public static final String POLICY_STOP = "STOP";
    public static final String POLICY_CONTINUE = "CONTINUE";

    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final ImportAsyncContext context;
    private final String commitMode;
    private final String errorPolicy;
    private final int batchSize;

    private final Connection connection;
    private boolean transactionMode = false;
    private int statementsInBatch = 0;
    private int statementNumber = 0;

    private int committed = 0;
    private int rolledBack = 0;
    private int failed = 0;
    private int unexecuted = 0;
    private boolean cancelled = false;
    private final List<Map<String, Object>> errors = new ArrayList<>();

    public SqlFileOptionsHandler(ImportAsyncContext context) {
        this.context = context;
        this.commitMode = normalize(StringUtils.defaultIfBlank(context.getCommitMode(), MODE_SCRIPT));
        this.errorPolicy = normalize(StringUtils.defaultIfBlank(context.getErrorPolicy(), POLICY_STOP));
        this.batchSize = context.getBatchSize() == null || context.getBatchSize() < 1
                ? DEFAULT_BATCH_SIZE : context.getBatchSize();
        this.connection = Chat2DBContext.getConnection();
        if (!MODE_SCRIPT.equals(this.commitMode)
                && !MODE_BATCH.equals(this.commitMode)
                && !MODE_SINGLE.equals(this.commitMode)) {
            throw new BusinessException("import.sql.unsupportedCommitMode");
        }
        if (!POLICY_STOP.equals(this.errorPolicy) && !POLICY_CONTINUE.equals(this.errorPolicy)) {
            throw new BusinessException("import.sql.unsupportedErrorPolicy");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public void handle(Statement statement) {
        context.checkCancelled();
        statementNumber++;
        String sql = statement.getSql() == null ? "" : statement.getSql().trim();
        if (StringUtils.isBlank(sql) || ";".equals(sql)) {
            return;
        }
        if (!MODE_SCRIPT.equals(commitMode)) {
            validateForTransactionMode(sql, statementNumber);
        }
        try {
            if (!transactionMode && !MODE_SCRIPT.equals(commitMode)) {
                beginTransaction();
            }
            try (JdbcStatement jdbcStatement = connection.createStatement()) {
                jdbcStatement.execute(sql);
            }
            committed++;
            statementsInBatch++;
            if (MODE_BATCH.equals(commitMode) && statementsInBatch >= batchSize) {
                connection.commit();
                statementsInBatch = 0;
            }
        } catch (SQLException e) {
            failed++;
            recordError(statementNumber, sql, e);
            if (POLICY_STOP.equals(errorPolicy) || MODE_SINGLE.equals(commitMode)) {
                rollbackQuietly();
                context.error(buildSummaryLine());
                throw new BusinessException("import.sql.failedAt",
                        new Object[]{statementNumber, e.getErrorCode(), e.getSQLState(), e.getMessage()}, e);
            }
            // CONTINUE: the failed statement is skipped; earlier statements in this batch
            // stay open and commit at the batch boundary.
        }
    }

    @Override
    public void flush() {
        try {
            context.checkCancelled();
        } catch (RuntimeException e) {
            cancelled = true;
            rollbackQuietly();
            context.error("Import cancelled; " + buildSummaryLine());
            restoreAutoCommit();
            throw e;
        }
        try {
            if (transactionMode) {
                connection.commit();
                if (MODE_SINGLE.equals(commitMode)) {
                    committed = statementNumber - failed - unexecuted;
                }
                transactionMode = false;
            }
        } catch (SQLException e) {
            rollbackQuietly();
            context.error("Final commit failed; " + buildSummaryLine());
            throw new BusinessException("import.sql.finalCommitFailed", new Object[]{e.getMessage()}, e);
        } finally {
            restoreAutoCommit();
        }
        context.info(buildSummaryLine());
        for (Map<String, Object> error : errors) {
            context.error("statement " + error.get("statement") + ": " + error.get("message"));
        }
    }

    private void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
        transactionMode = true;
    }

    private void rollbackQuietly() {
        try {
            if (transactionMode) {
                connection.rollback();
                rolledBack += Math.max(1, statementsInBatch);
                statementsInBatch = 0;
                transactionMode = false;
            }
        } catch (SQLException rollbackEx) {
            log.warn("rollback failed during SQL import", rollbackEx);
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("failed to restore autocommit after SQL import", e);
        }
    }

    private void recordError(int statement, String sql, SQLException e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("statement", statement);
        entry.put("sql", truncateSql(sql));
        entry.put("errorCode", e.getErrorCode());
        entry.put("sqlState", e.getSQLState());
        entry.put("message", e.getMessage());
        errors.add(entry);
    }

    private static String truncateSql(String sql) {
        return sql.length() <= 200 ? sql : sql.substring(0, 200) + "...";
    }

    private String buildSummaryLine() {
        return "SQL import summary: committed=" + committed + ", rolledBack=" + rolledBack
                + ", failed=" + failed + ", unexecuted=" + unexecuted
                + (cancelled ? ", cancelled=true" : "");
    }

    /**
     * Rejects transaction-control and DDL/implicit-commit statements for BATCH and
     * SINGLE_TRANSACTION modes before any write happens for them.
     */
    private void validateForTransactionMode(String sql, int statement) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.startsWith("START TRANSACTION") || upper.startsWith("BEGIN")
                || upper.equals("COMMIT") || upper.equals("ROLLBACK")
                || upper.startsWith("COMMIT ") || upper.startsWith("ROLLBACK ")
                || upper.startsWith("SET AUTOCOMMIT")) {
            throw new BusinessException("import.sql.transactionControlRejected",
                    new Object[]{statement, commitMode});
        }
        if (upper.startsWith("CREATE") || upper.startsWith("ALTER") || upper.startsWith("DROP")
                || upper.startsWith("TRUNCATE") || upper.startsWith("RENAME")
                || upper.startsWith("GRANT") || upper.startsWith("REVOKE")
                || upper.startsWith("FLUSH") || upper.startsWith("LOCK")
                || upper.startsWith("UNLOCK") || upper.startsWith("OPTIMIZE")
                || upper.startsWith("ANALYZE") || upper.startsWith("CHECK ")
                || upper.startsWith("REPAIR") || upper.startsWith("LOAD ")
                || upper.startsWith("SET ")) {
            throw new BusinessException("import.sql.ddlRejected",
                    new Object[]{statement, commitMode});
        }
    }
}
