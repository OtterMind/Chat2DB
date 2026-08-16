package ai.chat2db.community.web.api.adapter.db.execution;

import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.model.request.operation.OpsSqlOperationLogListResultRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.ops.IOpsSqlOperationLogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlExecutionLogConsumerTest {

    @Test
    void usesExecutionMetricsWhenStatementCallbackHasNoDuration() {
        CapturingRecorder recorder = new CapturingRecorder();
        SqlExecutionLogConsumer consumer = consumer(recorder);
        consumer.statementStarted("select 1", "select 1", null);
        consumer.resultFinished(ExecuteResponse.builder()
                .sql("select 1")
                .success(Boolean.TRUE)
                .executionMetrics(ExecutionMetrics.builder().totalDurationMs(19L).build())
                .build());

        consumer.finishSuccess();

        assertEquals(19L, recorder.record.getUseTime());
    }

    @Test
    void keepsStatementCallbackDurationAuthoritative() {
        CapturingRecorder recorder = new CapturingRecorder();
        SqlExecutionLogConsumer consumer = consumer(recorder);
        consumer.statementStarted("select 1", "select 1", null);
        consumer.resultFinished(ExecuteResponse.builder()
                .sql("select 1")
                .success(Boolean.TRUE)
                .executionMetrics(ExecutionMetrics.builder().totalDurationMs(19L).build())
                .build());
        consumer.statementFinished("select 1", 23L);

        consumer.finishSuccess();

        assertEquals(23L, recorder.record.getUseTime());
    }

    @Test
    void keepsStatementDurationWithProductionCallbackOrderAndMultipleResults() {
        CapturingRecorder recorder = new CapturingRecorder();
        SqlExecutionLogConsumer consumer = consumer(recorder);
        consumer.statementStarted("call multi_result_procedure()", "call multi_result_procedure()", null);
        consumer.statementFinished("call multi_result_procedure()", 25L);
        consumer.resultFinished(resultWithDuration(10L));
        consumer.resultFinished(resultWithDuration(15L));

        consumer.finishSuccess();

        assertEquals(25L, recorder.record.getUseTime());
    }

    @Test
    void addsIndependentResultMetricsWhenStatementCallbackHasNoDuration() {
        CapturingRecorder recorder = new CapturingRecorder();
        SqlExecutionLogConsumer consumer = consumer(recorder);
        consumer.statementStarted("call multi_result_procedure()", "call multi_result_procedure()", null);
        consumer.resultFinished(resultWithDuration(10L));
        consumer.resultFinished(resultWithDuration(15L));

        consumer.finishSuccess();

        assertEquals(25L, recorder.record.getUseTime());
    }

    private ExecuteResponse resultWithDuration(long durationMs) {
        return ExecuteResponse.builder()
                .sql("call multi_result_procedure()")
                .success(Boolean.TRUE)
                .executionMetrics(ExecutionMetrics.builder().totalDurationMs(durationMs).build())
                .build();
    }

    private SqlExecutionLogConsumer consumer(CapturingRecorder recorder) {
        return new SqlExecutionLogConsumer(new NoOpConsumer(),
                SqlExecutionRequest.builder().executionId("execution-1").build(), recorder);
    }

    private static class NoOpConsumer implements ISqlExecutionResultConsumer {

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
        }

        @Override
        public void resultStarted(ExecuteResponse result) {
        }

        @Override
        public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
        }

        @Override
        public void resultFinished(ExecuteResponse result) {
        }

        @Override
        public void updateCount(ExecuteResponse result) {
        }

        @Override
        public void statementFinished(String sql, long duration) {
        }
    }

    private static class CapturingRecorder implements IOpsSqlOperationLogService {

        private SqlOperationLogRecord record;

        @Override
        public void recordResultsAsync(List<ExecuteResponse> results, String source) {
        }

        @Override
        public void recordListResultAsync(OpsSqlOperationLogListResultRequest request) {
        }

        @Override
        public void recordResultAsync(ExecuteResponse result, String source) {
        }

        @Override
        public void recordFailureAsync(String sql, String source, String errorMessage) {
        }

        @Override
        public void recordAsync(SqlOperationLogRecord record) {
            this.record = record;
        }

        @Override
        public void recordAsync(List<SqlOperationLogRecord> records) {
        }
    }
}
