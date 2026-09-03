package ai.chat2db.community.domain.core.converter;

import java.util.Map;

import ai.chat2db.community.domain.api.enums.operation.OperationTypeEnum;
import ai.chat2db.community.domain.api.enums.operation.SqlOperationLogStatusEnum;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.operation.SqlOperationLogRecord;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.tools.model.Context;
import com.alibaba.fastjson2.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class SqlOperationLogConverter {

    public SqlOperationLogRecord executeResult2record(ExecuteResponse result, String source,
            ConnectionProfile connectionProfile, Context context) {
        if (result == null) {
            return null;
        }
        String sql = StringUtils.defaultIfBlank(result.getOriginalSql(), result.getSql());
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        Long operationRows = result.getUpdateCount() == null ? null : Long.valueOf(result.getUpdateCount());
        return SqlOperationLogRecord.builder()
                .sql(sql)
                .status(Boolean.FALSE.equals(result.getSuccess())
                        ? SqlOperationLogStatusEnum.FAIL.getCode()
                        : SqlOperationLogStatusEnum.SUCCESS.getCode())
                .useTime(result.getExecutionMetrics() == null
                        ? null
                        : result.getExecutionMetrics().getTotalDurationMs())
                .operationRows(operationRows)
                .sqlType(result.getSqlType())
                .errorMessage(result.getMessage())
                .source(source)
                .connectionProfile(connectionProfile)
                .context(context)
                .build();
    }

    public OperationLog sqlRecord2operationLog(SqlOperationLogRecord record) {
        ConnectionProfile connectionProfile = record.getConnectionProfile();
        OperationLog operationLog = new OperationLog();
        Context context = record.getContext();
        operationLog.setOrganizationId(context == null ? null : context.getOrganizationId());
        operationLog.setDdl(record.getSql());
        operationLog.setStatus(StringUtils.defaultIfBlank(record.getStatus(), SqlOperationLogStatusEnum.SUCCESS.getCode()));
        operationLog.setDatabaseName(connectionProfile.getDatabaseName());
        operationLog.setDataSourceId(connectionProfile.getDataSourceId());
        operationLog.setDataSourceName(connectionProfile.getAlias());
        operationLog.setSchemaName(connectionProfile.getSchemaName());
        operationLog.setUseTime(record.getUseTime());
        operationLog.setType(connectionProfile.getDbType());
        // The recording pipeline only logs executions; audit entries enter through the create endpoint.
        operationLog.setOperationType(OperationTypeEnum.SQL_EXECUTE.name());
        operationLog.setOperationRows(record.getOperationRows());
        operationLog.setExtendInfo(JSON.toJSONString(Map.of(
                "source", StringUtils.defaultString(record.getSource()),
                "sqlType", StringUtils.defaultString(record.getSqlType()),
                "executionId", StringUtils.defaultString(record.getExecutionId()),
                "statementSequence", record.getStatementSequence() == null ? "" : record.getStatementSequence(),
                "message", StringUtils.defaultString(record.getErrorMessage())
        )));
        return operationLog;
    }
}
