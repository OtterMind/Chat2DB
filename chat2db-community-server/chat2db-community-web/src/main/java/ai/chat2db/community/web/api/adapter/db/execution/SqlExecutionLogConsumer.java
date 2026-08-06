package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogStatusEnum;
import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogSourceEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class SqlExecutionLogConsumer implements ISqlExecutionResultConsumer {

    private final ISqlExecutionResultConsumer delegate;
    private final SqlExecutionRequest request;
    private final IOpsSqlOperationLogService recorder;
    private StatementState currentStatement;
    private int statementSequence = 0;

    public SqlExecutionLogConsumer(ISqlExecutionResultConsumer delegate, SqlExecutionRequest request,
                                   IOpsSqlOperationLogService recorder) {
        this.delegate = delegate;
        this.request = request;
        this.recorder = recorder;
    }

    @Override
    public void statementStarted(String sql, String originalSql, String comment) {
        flush(null, null);
        statementSequence++;
        currentStatement = new StatementState(sql, originalSql, statementSequence);
        delegate.statementStarted(sql, originalSql, comment);
    }

    @Override
    public void resultStarted(ExecuteResponse result) {
        delegate.resultStarted(result);
    }

    @Override
    public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
        delegate.rows(result, rows);
    }

    @Override
    public void resultFinished(ExecuteResponse result) {
        if (currentStatement != null && result != null) {
            currentStatement.results.add(result);
            currentStatement.pendingUpdateResult = null;
        }
        delegate.resultFinished(result);
    }

    @Override
    public void updateCount(ExecuteResponse result) {
        if (currentStatement != null) {
            currentStatement.pendingUpdateResult = result;
        }
        delegate.updateCount(result);
    }

    @Override
    public void statementFinished(String sql, long duration) {
        if (currentStatement != null) {
            currentStatement.duration = duration;
        }
        delegate.statementFinished(sql, duration);
    }

    public void finishSuccess() {
        flush(null, null);
    }

    public void finishFailed(String fallbackSql, String message) {
        if (currentStatement == null) {
            recordFallback(fallbackSql, SqlOperationLogStatusEnum.FAIL.getCode(), message);
            return;
        }
        flush(SqlOperationLogStatusEnum.FAIL.getCode(), message);
    }

    public void finishCancelled(String fallbackSql, String message) {
        if (currentStatement == null) {
            recordFallback(fallbackSql, SqlOperationLogStatusEnum.CANCELLED.getCode(), message);
            return;
        }
        flush(SqlOperationLogStatusEnum.CANCELLED.getCode(), message);
    }

    private void flush(String statusOverride, String messageOverride) {
        if (currentStatement == null) {
            return;
        }
        StatementState state = currentStatement;
        currentStatement = null;
        List<ExecuteResponse> results = new ArrayList<>(state.results);
        if (results.isEmpty() && state.pendingUpdateResult != null) {
            results.add(state.pendingUpdateResult);
        }
        SqlOperationLogRecord.SqlOperationLogRecordBuilder builder = SqlOperationLogRecord.builder()
                .sql(firstSql(state, results))
                .status(resolveStatus(statusOverride, results))
                .useTime(state.duration == null ? resolveDuration(results) : state.duration)
                .operationRows(resolveOperationRows(results))
                .sqlType(resolveSqlType(results))
                .errorMessage(StringUtils.defaultIfBlank(messageOverride, resolveErrorMessage(results)))
                .executionId(request.getExecutionId())
                .statementSequence(state.sequence)
                .source(SqlOperationLogSourceEnum.SQL_EDITOR_JCEF.name())
                .connectionProfile(request.getConnectionProfile())
                .context(request.getContext());
        recorder.recordAsync(builder.build());
    }

    private void recordFallback(String fallbackSql, String status, String message) {
        recorder.recordAsync(SqlOperationLogRecord.builder()
                .sql(fallbackSql)
                .status(status)
                .errorMessage(message)
                .executionId(request.getExecutionId())
                .source(SqlOperationLogSourceEnum.SQL_EDITOR_JCEF.name())
                .connectionProfile(request.getConnectionProfile())
                .context(request.getContext())
                .build());
    }

    private String firstSql(StatementState state, List<ExecuteResponse> results) {
        for (ExecuteResponse result : results) {
            String sql = StringUtils.defaultIfBlank(result.getOriginalSql(), result.getSql());
            if (StringUtils.isNotBlank(sql)) {
                return sql;
            }
        }
        return StringUtils.defaultIfBlank(state.originalSql, state.sql);
    }

    private String resolveStatus(String statusOverride, List<ExecuteResponse> results) {
        if (StringUtils.isNotBlank(statusOverride)) {
            return statusOverride;
        }
        for (ExecuteResponse result : results) {
            if (Boolean.FALSE.equals(result.getSuccess())) {
                return SqlOperationLogStatusEnum.FAIL.getCode();
            }
        }
        return SqlOperationLogStatusEnum.SUCCESS.getCode();
    }

    private Long resolveDuration(List<ExecuteResponse> results) {
        long duration = 0L;
        boolean hasDuration = false;
        for (ExecuteResponse result : results) {
            ExecutionMetrics executionMetrics = result.getExecutionMetrics();
            if (executionMetrics != null && executionMetrics.getTotalDurationMs() != null) {
                duration += executionMetrics.getTotalDurationMs();
                hasDuration = true;
            }
        }
        return hasDuration ? duration : null;
    }

    private Long resolveOperationRows(List<ExecuteResponse> results) {
        long rows = 0L;
        boolean hasRows = false;
        for (ExecuteResponse result : results) {
            if (result.getUpdateCount() != null && result.getUpdateCount() >= 0) {
                rows += result.getUpdateCount();
                hasRows = true;
            }
        }
        return hasRows ? rows : null;
    }

    private String resolveSqlType(List<ExecuteResponse> results) {
        for (ExecuteResponse result : results) {
            if (StringUtils.isNotBlank(result.getSqlType())) {
                return result.getSqlType();
            }
        }
        return null;
    }

    private String resolveErrorMessage(List<ExecuteResponse> results) {
        for (ExecuteResponse result : results) {
            if (Boolean.FALSE.equals(result.getSuccess()) && StringUtils.isNotBlank(result.getMessage())) {
                return result.getMessage();
            }
        }
        return null;
    }

    private static class StatementState {

        private final String sql;
        private final String originalSql;
        private final int sequence;
        private final List<ExecuteResponse> results = new ArrayList<>();
        private ExecuteResponse pendingUpdateResult;
        private Long duration;

        private StatementState(String sql, String originalSql, int sequence) {
            this.sql = sql;
            this.originalSql = originalSql;
            this.sequence = sequence;
        }
    }
}
