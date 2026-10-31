package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Isolates retryable batches without committing work owned by the caller. */
public final class ImportBatchTransactions {
    public static final String UNSAFE_REPLAY_CODE = "IMPORT_TRANSACTION_UNSAFE";
    private ImportBatchTransactions() { }

    public static long executeParameterizedBatch(boolean transactional, Connection connection, String sql,
            List<List<Object>> parameterRows, ISqlExecutionStatementListener statementListener,
            Runnable checkCancelled) {
        if (!transactional) return executeDirect(connection, sql, parameterRows, statementListener, checkCancelled);
        boolean ownsTransaction;
        Savepoint savepoint = null;
        try {
            if (checkCancelled != null) checkCancelled.run();
            ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction && Chat2DBContext.getConnectInfo() != null) {
                ownsTransaction = !Chat2DBContext.getDbMetaData()
                        .hasOpenTransaction(connection, statementListener).orElse(false);
            }
            if (checkCancelled != null) checkCancelled.run();
        } catch (SQLException failure) {
            throw new UnsafeReplayException("Could not inspect the import transaction", failure);
        }
        try {
            if (ownsTransaction) connection.setAutoCommit(false);
            else savepoint = connection.setSavepoint();
        } catch (SQLException | RuntimeException failure) {
            if (ownsTransaction) discard(connection, failure);
            throw new UnsafeReplayException("Could not isolate the import batch for retry", failure);
        }

        long stored;
        try {
            stored = executeDirect(connection, sql, parameterRows, statementListener, checkCancelled);
            if (checkCancelled != null) checkCancelled.run();
        } catch (RuntimeException | Error failure) {
            try {
                if (ownsTransaction) connection.rollback();
                else connection.rollback(savepoint);
            } catch (SQLException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                discard(connection, failure);
                throw new UnsafeReplayException("Could not roll back the import batch; retry stopped", failure);
            }
            if (ownsTransaction) restore(connection, failure);
            throw failure;
        }
        if (ownsTransaction) {
            try {
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                // A driver may throw after the server committed. Closing the lease is cleanup,
                // never evidence that replaying this batch would be safe.
                discard(connection, failure);
                throw new UnsafeReplayException("Import commit outcome is unknown; retry stopped", failure);
            }
            restore(connection, null);
        } else {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException failure) {
                throw new UnsafeReplayException("Could not release the import savepoint; retry stopped", failure);
            }
        }
        return stored;
    }

    public static long executeSequentialParameterizedBatch(Connection connection, String sql,
            List<List<Object>> rows, ISqlExecutionStatementListener listener, Runnable checkCancelled) {
        try {
            return DefaultSQLExecutor.getInstance().executeParameterizedSequentialWithCounts(
                    connection, sql, rows, listener, checkCancelled);
        } catch (RuntimeException failure) {
            throw classifyConnectionFailure(connection, failure);
        }
    }

    /** Only an ordinary row rejection after a confirmed rollback may enter row-by-row replay. */
    public static boolean canRetry(Throwable failure) {
        if (failure instanceof Error) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnsafeReplayException || cause instanceof TaskCancelledException
                    || cause instanceof CancellationException) return false;
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) return false;
        }
        return true;
    }

    private static long executeDirect(Connection connection, String sql, List<List<Object>> rows,
            ISqlExecutionStatementListener listener, Runnable checkCancelled) {
        try {
            return DefaultSQLExecutor.getInstance().executeParameterizedBatchWithCounts(
                    connection, sql, rows, listener, checkCancelled);
        } catch (RuntimeException failure) {
            throw classifyConnectionFailure(connection, failure);
        }
    }

    private static RuntimeException classifyConnectionFailure(Connection connection, RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("08")) {
                discard(connection, failure);
                return new UnsafeReplayException("Import execution outcome is unknown; retry stopped", failure);
            }
        }
        return failure;
    }

    private static void restore(Connection connection, Throwable precedingFailure) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException failure) {
            if (precedingFailure != null) failure.addSuppressed(precedingFailure);
            discard(connection, failure);
            throw new UnsafeReplayException("Could not restore the import connection; retry stopped", failure);
        }
    }

    private static void discard(Connection connection, Throwable failure) {
        var info = Chat2DBContext.getConnectInfo();
        if (info != null && info.getConnection() == connection) info.setConnection(null);
        try {
            connection.close();
        } catch (SQLException | RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static final class UnsafeReplayException extends TaskExecutionException {
        private UnsafeReplayException(String message, Throwable cause) {
            super(UNSAFE_REPLAY_CODE, I18nUtils.getMessage("import.sql.executionFailed"), message, cause);
        }
    }
}
