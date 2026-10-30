package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Runs one parameterized batch, optionally in a transaction of its own.
 *
 * <p>The historical import path keeps the connection's auto-commit mode: the driver applies the
 * statements it can and reports the rejected one afterwards, and the rows already applied stay in
 * the table (locked in place by {@code CsvImportPipelineTest}). That is exactly what breaks a
 * row-by-row retry, because those rows would be written twice, so batch execution switches to its
 * own transaction when, and only when, the failure policy retries rejected batches row by row: a
 * rejected batch is rolled back in full and the retry then sees each row exactly once.
 *
 * <p>The transaction is only taken over when the caller already owns the connection and it is in
 * auto-commit mode. In every other case the historical behavior is kept rather than joining or
 * committing a transaction the caller owns.
 */
@Slf4j
public final class ImportBatchTransactions {

    private ImportBatchTransactions() {
    }

    public static long executeParameterizedBatch(boolean transactional, Connection connection, String sql,
            List<List<Object>> parameterRows, ISqlExecutionStatementListener statementListener,
            Runnable checkCancelled) {
        if (!transactional || !canOwnTransaction(connection)) {
            return executeDirect(connection, sql, parameterRows, statementListener, checkCancelled);
        }
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not start the import batch transaction", e);
        }
        try {
            long stored = executeDirect(connection, sql, parameterRows, statementListener, checkCancelled);
            connection.commit();
            return stored;
        } catch (RuntimeException | Error failure) {
            rollback(connection, failure);
            throw failure;
        } catch (SQLException e) {
            rollback(connection, e);
            throw new IllegalStateException("Could not commit the import batch transaction", e);
        } finally {
            restoreAutoCommit(connection);
        }
    }

    private static long executeDirect(Connection connection, String sql, List<List<Object>> parameterRows,
            ISqlExecutionStatementListener statementListener, Runnable checkCancelled) {
        return DefaultSQLExecutor.getInstance().executeParameterizedBatchWithCounts(connection, sql, parameterRows,
                statementListener, checkCancelled);
    }

    private static boolean canOwnTransaction(Connection connection) {
        if (connection == null) {
            return false;
        }
        try {
            return !connection.isClosed() && connection.getAutoCommit();
        } catch (SQLException e) {
            return false;
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            // The connection belongs to the running task; a failed restore is reported by the next
            // statement instead of replacing the batch result.
            log.error("Could not restore auto-commit after an import batch", e);
        }
    }
}
