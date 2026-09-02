package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.export.sink.CsvSink;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

/**
 * CSV export through {@link CsvSink}: the writer no longer rides on EasyExcel's sheet machinery,
 * which chunked rows at the Excel sheet limit and paid header-per-sheet costs for a flat file.
 */
@Component
public class CsvDataExporter extends BaseExporter {

    public CsvDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.contentType = "text/csv";
        this.suffix = ExportFileSuffixEnum.CSV.getSuffix();
    }

    @Override
    public String type() {
        return "csv";
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        streamTable(spec, tableName, context, output,
                (stream, effectiveSpec, effectiveTable, resume) -> new CsvSink(stream,
                        Boolean.TRUE.equals(effectiveSpec.getContainsHeader()), resume),
                ExportValueMode.NATIVE, EXPORT_BATCH_ROWS,
                new ExportProgressLogger(context, "CSV", tableName), resuming);
    }
}
