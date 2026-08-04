package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;

import java.util.List;

public interface ITaskImportSqlExecutor {

    /**
     * Executes a batch of SQL statements during task import.
     *
     * @param batch batch index.
     * @param sqls SQL statements in execution order.
     * @return execution summary or error message.
     */
    String executeBatch(int batch, List<String> sqls);

    default String executeBatch(int batch, List<String> sqls,
                                ISqlExecutionStatementListener statementListener,
                                Runnable cancellationChecker) {
        return executeBatch(batch, sqls);
    }

    /**
     * Executes one SQL statement during task import.
     *
     * @param batch batch index.
     * @param sql SQL statement to execute.
     * @return execution summary or error message.
     */
    String executeSql(int batch, String sql);

    default String executeSql(int batch, String sql,
                              ISqlExecutionStatementListener statementListener,
                              Runnable cancellationChecker) {
        return executeSql(batch, sql);
    }
}
