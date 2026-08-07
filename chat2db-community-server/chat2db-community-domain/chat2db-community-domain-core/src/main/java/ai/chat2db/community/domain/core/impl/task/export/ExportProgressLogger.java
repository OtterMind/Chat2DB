package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ExportProgressLogger {

    private final TaskExecutionContext context;

    private final String format;

    private final String tableName;

    private final long logIntervalRows;

    private long exportedRows;

    private long lastLoggedRows;

    public ExportProgressLogger(TaskExecutionContext context, String format) {
        this(context, format, null, TaskConstants.EXPORT_LOG_ROW_INTERVAL);
    }

    public ExportProgressLogger(TaskExecutionContext context, String format, String tableName) {
        this(context, format, tableName, TaskConstants.EXPORT_LOG_ROW_INTERVAL);
    }

    ExportProgressLogger(TaskExecutionContext context, String format, String tableName, long logIntervalRows) {
        if (logIntervalRows <= 0) {
            throw new IllegalArgumentException("logIntervalRows must be positive");
        }
        this.context = context;
        this.format = format;
        this.tableName = tableName;
        this.logIntervalRows = logIntervalRows;
    }

    public void queryStarted(String message) {
        context.logInfo(TaskEventCode.QUERY_STARTED.name(), message, details());
    }

    public void recordExportedRow() {
        recordExportedRows(1L);
    }

    public void recordExportedRows(long rowCount) {
        if (rowCount <= 0) {
            return;
        }
        exportedRows += rowCount;
        if (exportedRows - lastLoggedRows < logIntervalRows) {
            return;
        }
        context.logInfo(TaskEventCode.ROWS_EXPORTED.name(), rowMessage(), details());
        lastLoggedRows = exportedRows;
    }

    public void queryCompleted(String message) {
        context.logInfo(TaskEventCode.QUERY_COMPLETED.name(),
                message + ": " + exportedRows + " rows" + tableSuffix(), details());
    }

    public void fileFinalizing() {
        context.logInfo(TaskEventCode.FILE_FINALIZING.name(),
                "Finalizing " + format + " export file" + tableSuffix(), details());
    }

    public long exportedRows() {
        return exportedRows;
    }

    private String rowMessage() {
        return "Exported " + exportedRows + " rows" + tableSuffix();
    }

    private String tableSuffix() {
        return StringUtils.isBlank(tableName) ? "" : " from " + tableName;
    }

    private Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        if (StringUtils.isNotBlank(format)) {
            details.put(TaskConstants.FILE_FORMAT_DETAIL_KEY, format);
        }
        if (StringUtils.isNotBlank(tableName)) {
            details.put(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName);
        }
        details.put(TaskConstants.EXPORTED_ROWS_DETAIL_KEY, exportedRows);
        return details;
    }
}
