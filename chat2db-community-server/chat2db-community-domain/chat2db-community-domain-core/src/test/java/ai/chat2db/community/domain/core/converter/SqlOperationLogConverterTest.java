package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.tools.model.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlOperationLogConverterTest {

    @Test
    void mapsUseTimeFromExecutionMetrics() {
        ExecuteResponse result = ExecuteResponse.builder()
                .sql("select 1")
                .success(Boolean.TRUE)
                .executionMetrics(ExecutionMetrics.builder().totalDurationMs(17L).build())
                .build();

        SqlOperationLogRecord record = new SqlOperationLogConverter().executeResult2record(
                result, "test", new ConnectionProfile(), new Context());

        assertEquals(17L, record.getUseTime());
    }

    @Test
    void tagsRecordedExecutionsAsSqlExecute() {
        SqlOperationLogRecord record = SqlOperationLogRecord.builder()
                .sql("select 1")
                .status("success")
                .connectionProfile(new ConnectionProfile())
                .context(new Context())
                .build();

        OperationLog operationLog = new SqlOperationLogConverter().sqlRecord2operationLog(record);

        assertEquals("SQL_EXECUTE", operationLog.getOperationType());
    }
}
