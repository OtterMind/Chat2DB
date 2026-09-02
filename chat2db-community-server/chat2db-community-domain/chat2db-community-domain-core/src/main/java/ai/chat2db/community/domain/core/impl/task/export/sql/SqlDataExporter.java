package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.api.enums.ExportFileSuffixEnum;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.task.export.sink.SqlSink;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;


/**
 * Table data SQL export. Rows are collected into multi-value {@code INSERT} statements bounded by
 * row count and byte size, instead of one statement per row.
 */
@Slf4j
@Component
public class SqlDataExporter extends BaseExporter {

    public SqlDataExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
        this.suffix = ExportFileSuffixEnum.SQL.getSuffix();
        this.contentType = "text/sql";
    }

    @Override
    public String type() {
        return "sql";
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            OutputStream output, boolean resuming) {
        streamTable(spec, tableName, context, output,
                (stream, effectiveSpec, effectiveTable, resume) -> new SqlSink(stream,
                        Chat2DBContext.getSqlBuilder(),
                        effectiveSpec.getTarget().getDatabaseName(), effectiveSpec.getTarget().getSchemaName()),
                ExportValueMode.SQL_LITERAL, EXPORT_BATCH_ROWS,
                new ExportProgressLogger(context, "SQL", tableName), resuming);
    }
}
