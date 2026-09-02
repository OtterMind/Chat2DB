package ai.chat2db.community.domain.core.impl.task.export.json;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.export.sink.JsonSink;
import org.springframework.stereotype.Component;

import java.io.OutputStream;

@Component
public class JsonDataExporter extends BaseExporter {

    public JsonDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ExportFileSuffixEnum.JSON.getSuffix();
        this.contentType = "application/json";
    }

    @Override
    public String type() {
        return "json";
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        streamTable(spec, tableName, context, output, (stream, effectiveSpec, effectiveTable, resume) ->
                        new JsonSink(stream),
                ExportValueMode.NATIVE, EXPORT_BATCH_ROWS,
                new ExportProgressLogger(context, "JSON", tableName), resuming);
    }
}
