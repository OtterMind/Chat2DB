package ai.chat2db.community.domain.core.impl.task.export.json;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.export.sink.NdjsonSink;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

/**
 * NDJSON export: one JSON object per line, the streaming-parseable counterpart of
 * {@link JsonDataExporter}.
 */
@Component
public class NdjsonDataExporter extends BaseExporter {

    public NdjsonDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ".ndjson";
        this.contentType = "application/x-ndjson";
    }

    @Override
    public String type() {
        return "ndjson";
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        streamTable(spec, tableName, context, output,
                (stream, effectiveSpec, effectiveTable, resume) -> new NdjsonSink(stream),
                ExportValueMode.NATIVE, EXPORT_BATCH_ROWS,
                new ExportProgressLogger(context, "NDJSON", tableName), resuming);
    }
}
