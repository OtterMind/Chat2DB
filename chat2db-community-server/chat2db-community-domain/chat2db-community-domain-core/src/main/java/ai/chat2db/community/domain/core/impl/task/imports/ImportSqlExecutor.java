package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
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

public final class ImportSqlExecutor {

    private final TaskExecutionContext context;

    private final AtomicInteger batchSequence = new AtomicInteger();

    public ImportSqlExecutor(TaskExecutionContext context) {
        this.context = context;
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
            context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "SQL batch executed",
                    Map.of("batch", batch, "statementCount", statementCount));
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw importFailure(e);
        }
    }

    public void executeSql(String sql) {
        if (StringUtils.isBlank(sql)) {
            return;
        }
        int batch = batchSequence.incrementAndGet();
        try {
            context.checkCancelled();
            executeStatement(sql);
            context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "SQL statement executed",
                    Map.of("batch", batch, "statementCount", 1));
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw importFailure(e);
        }
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
        DefaultSQLExecutor.getInstance().executeBatchInsert(
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
