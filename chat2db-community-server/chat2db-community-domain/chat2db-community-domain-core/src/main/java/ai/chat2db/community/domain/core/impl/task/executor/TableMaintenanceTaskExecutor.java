package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.task.TableMaintenanceTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TableMaintenanceTaskExecutor implements TaskExecutor<TableMaintenanceTaskSpec> {

    private static final int PREPARED_PROGRESS = 5;
    private static final int COMPLETED_PROGRESS = 85;
    private static final int REFRESH_PROGRESS = 95;

    private final IDbTableService tableService;

    public TableMaintenanceTaskExecutor(IDbTableService tableService) {
        this.tableService = tableService;
    }

    @Override
    public String taskType() {
        return TaskType.TABLE_MAINTENANCE.name();
    }

    @Override
    public Class<TableMaintenanceTaskSpec> specType() {
        return TableMaintenanceTaskSpec.class;
    }

    @Override
    public void execute(TableMaintenanceTaskSpec spec, TaskExecutionContext context) {
        TaskTargetSnapshot target = requireTarget(spec);
        String operationType = requireOperationType(spec);
        String operationLabel = operationType + " TABLE";
        String tableName = target.getTableName();
        try {
            context.reportProgress(PREPARED_PROGRESS, TaskStage.QUERYING.name(),
                    "Preparing " + operationLabel + " for " + tableName);
            context.logInfo(TaskEventCode.TABLE_MAINTENANCE_STARTED.name(),
                    "Running " + operationLabel + " for " + qualifiedTableName(target),
                    taskDetails(target, operationType));
            List<ExecuteResponse> results = tableService.executeMaintenance(request(target), operationType, context);
            if (logResults(operationLabel, target, results, context)) {
                throw new TaskExecutionException(TaskErrorCode.TABLE_MAINTENANCE_FAILED.name(),
                        "Could not execute " + operationLabel,
                        "MySQL reported an error for " + qualifiedTableName(target), null);
            }
            context.reportProgress(COMPLETED_PROGRESS, TaskStage.QUERYING.name(),
                    operationLabel + " completed for " + tableName);
            context.reportProgress(REFRESH_PROGRESS, TaskStage.FINALIZING.name(),
                    "Refreshing metadata for " + tableName);
            context.logInfo(TaskEventCode.TABLE_MAINTENANCE_REFRESH_REQUESTED.name(),
                    "Refresh table metadata for " + qualifiedTableName(target), taskDetails(target, operationType));
            context.logInfo(TaskEventCode.TABLE_MAINTENANCE_COMPLETED.name(),
                    operationLabel + " completed for " + qualifiedTableName(target), taskDetails(target, operationType));
        } catch (TaskExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TaskExecutionException(TaskErrorCode.TABLE_MAINTENANCE_FAILED.name(),
                    "Could not execute " + operationLabel, e.getMessage(), e);
        }
    }

    private boolean logResults(String operationLabel, TaskTargetSnapshot target, List<ExecuteResponse> results,
            TaskExecutionContext context) {
        if (CollectionUtils.isEmpty(results)) {
            context.logWarn(TaskEventCode.TABLE_MAINTENANCE_RESULT.name(),
                    operationLabel + " returned no server result rows for " + qualifiedTableName(target),
                    taskDetails(target, operationLabel));
            return false;
        }
        boolean failed = false;
        for (ExecuteResponse result : results) {
            if (CollectionUtils.isEmpty(result.getDataList())) {
                Map<String, Object> details = taskDetails(target, operationLabel);
                details.put("success", Boolean.TRUE.equals(result.getSuccess()));
                details.put("message", result.getMessage());
                failed |= logResultEvent(context, result, operationLabel + " result for " + qualifiedTableName(target)
                        + ": " + StringUtils.defaultIfBlank(result.getMessage(), "no rows"), details);
                continue;
            }
            for (List<ResultCell> row : result.getDataList()) {
                Map<String, Object> details = rowDetails(result.getHeaderList(), row);
                String msgType = value(details, "Msg_type");
                String msgText = value(details, "Msg_text");
                failed |= logResultEvent(context, result, operationLabel + " result for " + qualifiedTableName(target)
                        + ": " + StringUtils.defaultIfBlank(msgType, "message")
                        + " " + StringUtils.defaultString(msgText), details);
            }
        }
        return failed;
    }

    private boolean logResultEvent(TaskExecutionContext context, ExecuteResponse result, String message,
            Map<String, Object> details) {
        String msgType = value(details, "Msg_type");
        if (!Boolean.TRUE.equals(result.getSuccess()) || StringUtils.equalsAnyIgnoreCase(msgType, "error", "fatal")) {
            context.logError(TaskEventCode.TABLE_MAINTENANCE_RESULT.name(), message.trim(), details);
            return true;
        }
        if (StringUtils.equalsAnyIgnoreCase(msgType, "warning", "note")) {
            context.logWarn(TaskEventCode.TABLE_MAINTENANCE_RESULT.name(), message.trim(), details);
            return false;
        }
        context.logInfo(TaskEventCode.TABLE_MAINTENANCE_RESULT.name(), message.trim(), details);
        return false;
    }

    private Map<String, Object> rowDetails(List<Header> headers, List<ResultCell> row) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(row)) {
            return details;
        }
        for (int i = 0; i < row.size(); i++) {
            String name = headerName(headers, i);
            ResultCell cell = row.get(i);
            details.put(name, cell == null ? null : cell.getValue());
        }
        return details;
    }

    private String headerName(List<Header> headers, int index) {
        if (CollectionUtils.isEmpty(headers) || index >= headers.size()) {
            return "column" + (index + 1);
        }
        Header header = headers.get(index);
        return StringUtils.firstNonBlank(header.getName(), header.getColumnName(), "column" + (index + 1));
    }

    private Map<String, Object> taskDetails(TaskTargetSnapshot target, String operationType) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("operationType", operationType);
        details.put("dataSourceId", target.getDataSourceId());
        details.put("databaseName", target.getDatabaseName());
        details.put("schemaName", target.getSchemaName());
        details.put("tableName", target.getTableName());
        return details;
    }

    private DbTableQueryRequest request(TaskTargetSnapshot target) {
        return DbTableQueryRequest.builder()
                .dataSourceId(target.getDataSourceId())
                .databaseName(target.getDatabaseName())
                .schemaName(target.getSchemaName())
                .tableName(target.getTableName())
                .build();
    }

    private String qualifiedTableName(TaskTargetSnapshot target) {
        return StringUtils.isBlank(target.getDatabaseName())
                ? target.getTableName() : target.getDatabaseName() + "." + target.getTableName();
    }

    private TaskTargetSnapshot requireTarget(TableMaintenanceTaskSpec spec) {
        if (spec == null || spec.getTarget() == null || StringUtils.isBlank(spec.getTarget().getTableName())) {
            throw new TaskExecutionException(TaskErrorCode.TABLE_MAINTENANCE_FAILED.name(),
                    "Could not execute table maintenance", "Task target table is required", null);
        }
        return spec.getTarget();
    }

    private String requireOperationType(TableMaintenanceTaskSpec spec) {
        if (StringUtils.isBlank(spec.getOperationType())) {
            throw new TaskExecutionException(TaskErrorCode.TABLE_MAINTENANCE_FAILED.name(),
                    "Could not execute table maintenance", "Operation type is required", null);
        }
        return spec.getOperationType().trim().toUpperCase();
    }

    private String value(Map<String, Object> details, String key) {
        Object value = details.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
