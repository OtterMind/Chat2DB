package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.sink.MarkdownSink;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

/**
 * Markdown table export, primarily for documentation and quick eyeballing of small tables.
 */
@Component
public class MarkdownDataExporter extends BaseExporter {

    public MarkdownDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ".md";
        this.contentType = "text/markdown";
    }

    @Override
    public String type() {
        return "markdown";
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        streamTable(spec, tableName, context, output,
                (stream, effectiveSpec, effectiveTable, resume) -> new MarkdownSink(stream, resume),
                ExportValueMode.NATIVE, EXPORT_BATCH_ROWS,
                new ExportProgressLogger(context, "MARKDOWN", tableName), resuming);
    }
}
