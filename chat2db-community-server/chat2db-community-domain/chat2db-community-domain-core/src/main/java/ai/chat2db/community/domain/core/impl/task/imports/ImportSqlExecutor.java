package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.TaskEventThrottle;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.community.tools.util.I18nUtils;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class ImportSqlExecutor {

    private final TaskExecutionContext context;

    /** Applies the failure policy of the import; {@code null} keeps the historical fail-fast path. */
    private final ImportErrorRecorder errorRecorder;

    private final AtomicInteger batchSequence = new AtomicInteger();

    /** Rows the driver stored, so the summary does not count rows ignored by a duplicate policy. */
    private final LongAdder importedCount = new LongAdder();

    /**
     * One batch event per second is enough for the log panel; without this the standard and JSON
     * importers write one event per 1000 rows.
     */
    private final TaskEventThrottle batchEventThrottle = new TaskEventThrottle();

    public ImportSqlExecutor(TaskExecutionContext context) {
        this(context, null);
    }

    public ImportSqlExecutor(TaskExecutionContext context, ImportErrorRecorder errorRecorder) {
        this.context = context;
        this.errorRecorder = errorRecorder;
    }

    public void executeBatch(List<String> sqls) {
        if (CollectionUtils.isEmpty(sqls)) {
            return;
        }
        int batch = batchSequence.incrementAndGet();
        List<String> inserts = new ArrayList<>();
        int statementCount = 0;
        try {
            for (String sql : sqls) {
                context.checkCancelled();
                if (StringUtils.isBlank(sql)) {
                    continue;
                }
                statementCount++;
                if (sql.trim().toUpperCase().startsWith("INSERT")) {
                    inserts.add(sql);
                    continue;
                }
                flushInserts(inserts);
                executeStatement(sql);
            }
            flushInserts(inserts);
            logBatchExecuted(batch, statementCount);
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw importFailure(e);
        }
    }

    /**
     * Executes one prepared INSERT shape with many parameter rows.
     * <p>
     * Shares the batch sequence, the event throttle and the error mapping with
     * {@link #executeBatch(List)} so both write paths report identically; {@code statementCount}
     * stays the row count.
     */
    public void executeParameterizedBatch(String sqlTemplate, List<List<Object>> parameterRows) {
        executeParameterizedBatch(sqlTemplate, null, parameterRows);
    }

    /**
     * Executes one prepared INSERT shape with many parameter rows and applies the import's failure
     * policy: {@code ABORT} lists the rejected batch in the error artifact and rethrows, while
     * {@code SKIP_ERROR_ROWS} retries the batch row by row, records the rows that still fail and
     * keeps the import running.
     *
     * @param rowNumbers source row number of each parameter row, for the error artifact; may be
     *                   {@code null} when the caller does not track them
     * @return number of rows stored by this call
     */
    public int executeParameterizedBatch(String sqlTemplate, List<Long> rowNumbers,
            List<List<Object>> parameterRows) {
        return executeParameterizedBatch(sqlTemplate, rowNumbers, parameterRows, null);
    }

    /**
     * Same as {@link #executeParameterizedBatch(String, List, List)} but notifies the caller as soon
     * as the batch is committed and before the batch event is reported.
     *
     * <p>The order matters: the durable resume position has to be in place before anything else can
     * observe the commit, because a task that is cancelled in between can no longer write it.
     *
     * @param onCommitted hook invoked with the committed row count, or {@code null}
     */
    public int executeParameterizedBatch(String sqlTemplate, List<Long> rowNumbers,
            List<List<Object>> parameterRows, java.util.function.LongConsumer onCommitted) {
        if (StringUtils.isBlank(sqlTemplate) || CollectionUtils.isEmpty(parameterRows)) {
            return 0;
        }
        int batch = batchSequence.incrementAndGet();
        int statementCount = parameterRows.size();
        boolean retryRows = retriesRejectedRows();
        try {
            context.checkCancelled();
            long stored = ImportBatchTransactions.executeParameterizedBatch(retryRows, Chat2DBContext.getConnection(),
                    sqlTemplate, parameterRows, context, context::checkCancelled);
            importedCount.add(stored);
            // The commit position counts consumed source rows, including rows INSERT IGNORE skipped.
            commit(onCommitted, statementCount);
            logBatchExecuted(batch, statementCount, stored);
            return (int) stored;
        } catch (TaskCancelledException e) {
            throw e;
        } catch (TaskExecutionException e) {
            if (!retryRows) {
                recordRejectedBatch(rowNumbers, parameterRows, e);
                throw e;
            }
            return commitRetriedRows(batch, sqlTemplate, rowNumbers, parameterRows, e, onCommitted);
        } catch (Exception e) {
            TaskExecutionException failure = importFailure(e);
            if (!retryRows) {
                recordRejectedBatch(rowNumbers, parameterRows, failure);
                throw failure;
            }
            return commitRetriedRows(batch, sqlTemplate, rowNumbers, parameterRows, failure, onCommitted);
        }
    }

    private int commitRetriedRows(int batch, String sqlTemplate, List<Long> rowNumbers,
            List<List<Object>> parameterRows, Exception batchFailure,
            java.util.function.LongConsumer onCommitted) {
        int stored = retryRejectedRows(sqlTemplate, rowNumbers, parameterRows, batchFailure);
        importedCount.add(stored);
        // The rejected rows of this batch are recorded, so the position may move past the batch.
        commit(onCommitted, stored);
        logBatchExecuted(batch, parameterRows.size(), stored);
        return stored;
    }

    private static void commit(java.util.function.LongConsumer onCommitted, int stored) {
        if (onCommitted != null) {
            onCommitted.accept(stored);
        }
    }

    private boolean retriesRejectedRows() {
        return errorRecorder != null && errorRecorder.toleratesRowFailures();
    }

    private void recordRejectedBatch(List<Long> rowNumbers, List<List<Object>> parameterRows, Exception failure) {
        if (errorRecorder != null) {
            errorRecorder.recordRejectedBatch(rowNumbers, parameterRows, reason(failure));
        }
    }

    /**
     * Re-executes a rejected batch one row at a time. The batch itself ran in its own transaction
     * (see {@link ImportBatchTransactions}), so no row of it is stored twice, and every row that
     * fails on its own lands in the error artifact with its own reason.
     */
    private int retryRejectedRows(String sqlTemplate, List<Long> rowNumbers, List<List<Object>> parameterRows,
            Exception batchFailure) {
        String batchReason = reason(batchFailure);
        context.logWarn(TaskEventCode.IMPORT_BATCH_RETRY.name(), "Retrying a rejected batch row by row",
                Map.of("statementCount", parameterRows.size(), "message", batchReason));
        int stored = 0;
        for (int index = 0; index < parameterRows.size(); index++) {
            context.checkCancelled();
            List<Object> row = parameterRows.get(index);
            try {
                ImportBatchTransactions.executeParameterizedBatch(false, Chat2DBContext.getConnection(),
                        sqlTemplate, List.of(row), context, context::checkCancelled);
                stored++;
            } catch (TaskCancelledException e) {
                throw e;
            } catch (Exception rowFailure) {
                errorRecorder.recordRejectedRow(rowNumber(rowNumbers, index), row, reason(rowFailure));
            }
        }
        return stored;
    }

    private static long rowNumber(List<Long> rowNumbers, int index) {
        if (rowNumbers == null || index >= rowNumbers.size() || rowNumbers.get(index) == null) {
            return -1L;
        }
        return rowNumbers.get(index);
    }

    /** Public, localized reason of a rejection; the SQL text itself is never exposed. */
    private static String reason(Exception failure) {
        TaskExecutionException mapped = failure instanceof TaskExecutionException taskFailure
                ? taskFailure : importFailure(failure);
        return StringUtils.defaultIfBlank(mapped.publicMessage(), mapped.getCode());
    }

    public void executeSql(String sql) {
        if (StringUtils.isBlank(sql)) {
            return;
        }
        int batch = batchSequence.incrementAndGet();
        try {
            context.checkCancelled();
            executeStatement(sql);
            logBatchExecuted(batch, 1);
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw importFailure(e);
        }
    }

    private void logBatchExecuted(int batch, int statementCount) {
        if (!batchEventThrottle.tryAcquire(TaskEventCode.BATCH_EXECUTED.name())) {
            return;
        }
        context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "SQL batch executed",
                Map.of("batch", batch, "statementCount", statementCount));
    }

    private void logBatchExecuted(int batch, int statementCount, long stored) {
        if (!batchEventThrottle.tryAcquire(TaskEventCode.BATCH_EXECUTED.name())) {
            return;
        }
        context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "SQL batch executed",
                Map.of("batch", batch, "statementCount", statementCount,
                        "batchImportedRows", stored, "importedRows", importedCount.sum()));
    }

    static TaskExecutionException importFailure(Exception error) {
        SQLException firstSqlError = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String code = errorCode(sqlException);
                if (!"import.sql.executionFailed".equals(code)) {
                    return failure(code, sqlException, error);
                }
                if (firstSqlError == null) {
                    firstSqlError = sqlException;
                }
            }
        }
        return firstSqlError == null
                ? new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        I18nUtils.getMessage("import.sql.executionFailed"), error)
                : failure("import.sql.executionFailed", firstSqlError, error);
    }

    private static TaskExecutionException failure(String code, SQLException sqlException, Exception error) {
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), I18nUtils.getMessage(code),
                "SQLState=" + StringUtils.defaultString(sqlException.getSQLState())
                        + ", code=" + sqlException.getErrorCode(), error);
    }

    private static String errorCode(SQLException sqlException) {
        String state = StringUtils.defaultString(sqlException.getSQLState());
        if (state.startsWith("23")) return "import.sql.constraintViolation";
        if (state.startsWith("22")) return "import.sql.invalidValue";
        if (state.startsWith("42")) return "import.sql.invalidStatement";
        if (state.startsWith("08")) return "import.sql.connectionFailed";
        return "import.sql.executionFailed";
    }

    private void flushInserts(List<String> inserts) {
        if (inserts.isEmpty()) {
            return;
        }
        context.checkCancelled();
        // One JDBC batch per chunk instead of one prepare+execute per row.
        DefaultSQLExecutor.getInstance().executeJdbcBatchInsert(
                Chat2DBContext.getConnection(), List.copyOf(inserts), context, context::checkCancelled);
        inserts.clear();
    }

    private void executeStatement(String sql) throws SQLException {
        context.checkCancelled();
        DefaultSQLExecutor.getInstance().execute(
                Chat2DBContext.getConnection(), sql, context, context::checkCancelled);
        context.checkCancelled();
    }
}
