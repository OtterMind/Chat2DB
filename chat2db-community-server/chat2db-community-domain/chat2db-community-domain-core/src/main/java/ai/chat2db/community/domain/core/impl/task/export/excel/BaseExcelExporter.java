package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * XLSX/XLS export through {@link ExcelSink}. Text cells longer than the spreadsheet limit are
 * truncated (the previous behaviour silently removed POI's cap through reflection); the number of
 * truncated cells is reported as a warning event.
 */
@Slf4j
public abstract class BaseExcelExporter extends BaseExporter {

    /** JDBC fetch size used by Excel exports; not a sink flush size. */
    public static final int EXCEL_FETCH_ROWS = 500;

    protected BaseExcelExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        AtomicReference<ExcelSink> created = new AtomicReference<>();
        streamTable(spec, tableName, context, output, (stream, effectiveSpec, effectiveTable, resume) -> {
            ExcelSink sink = new ExcelSink(stream, getExcelType(),
                    Boolean.TRUE.equals(effectiveSpec.getContainsHeader()), effectiveTable);
            created.set(sink);
            return sink;
        }, ExportValueMode.NATIVE, EXCEL_FETCH_ROWS,
                new ExportProgressLogger(context, getExcelType().name(), tableName), resuming);
        ExcelSink sink = created.get();
        if (sink != null && sink.truncatedCells() > 0) {
            context.logWarn(TaskEventCode.EXCEL_TEXT_TRUNCATED.name(),
                    sink.truncatedCells() + " text cells were truncated to the " + getExcelType() + " limit",
                    Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                            "truncatedCells", sink.truncatedCells()));
        }
    }

    protected abstract ExcelTypeEnum getExcelType();
}
