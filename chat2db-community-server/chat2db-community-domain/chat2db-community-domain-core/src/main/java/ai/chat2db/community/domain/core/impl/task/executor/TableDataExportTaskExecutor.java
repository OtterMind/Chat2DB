package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.core.impl.task.export.IExportStrategy;
import ai.chat2db.community.domain.core.impl.task.export.ExportStrategyRegistry;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TableDataExportTaskExecutor implements TaskExecutor<ExportTaskSpec> {

    private final ExportStrategyRegistry exportStrategyRegistry;

    public TableDataExportTaskExecutor(ExportStrategyRegistry exportStrategyRegistry) {
        this.exportStrategyRegistry = exportStrategyRegistry;
    }

    @Override
    public String taskType() {
        return TaskType.TABLE_DATA_EXPORT.name();
    }

    @Override
    public Class<ExportTaskSpec> specType() {
        return ExportTaskSpec.class;
    }

    @Override
    public void execute(ExportTaskSpec spec, TaskExecutionContext context) {
        try {
            String format = TaskExecutorSupport.requireFormat(spec.getFormat());
            if (TaskFileFormat.ZIP.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                        "ZIP is an output container, not a table data format");
            }
            boolean multipleTables = CollectionUtils.size(spec.getTableNames()) > 1;
            String compression = TaskExecutorSupport.requireCompression(spec.getCompression());
            if (spec.getCheckpointRows() != null && spec.getCheckpointRows() > 0) {
                requireAppendableCheckpointFormat(format, multipleTables);
            }
            String artifactFormat = multipleTables ? TaskFileFormat.ZIP.name() : format;
            String fileName = TaskExecutorSupport.artifactFileName(spec, spec.getSuggestedFileName(),
                    artifactFormat, compression);
            ArtifactDraft draft = context.createArtifact(spec.getExportPath(), fileName,
                    TaskExecutorSupport.mediaType(artifactFormat));
            context.logInfo(TaskEventCode.EXPORT_STARTED.name(), "Table data export started",
                    Map.of(TaskConstants.FILE_FORMAT_DETAIL_KEY, format,
                            TaskConstants.TOTAL_TABLES_DETAIL_KEY, CollectionUtils.size(spec.getTableNames())));
            context.reportProgress(10, TaskStage.EXPORTING.name(), "Exporting table data");
            IExportStrategy strategy = exportStrategyRegistry.getExporter(format);
            strategy.run(spec, context, draft.getTemporaryFile());
            context.reportProgress(92, TaskStage.FINALIZING.name(), "Finalizing table data export");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Could not export table data", e);
        }
    }

    private static void requireAppendableCheckpointFormat(String format, boolean multipleTables) {
        if (multipleTables) {
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Checkpointed export supports a single table, not a ZIP archive");
        }
        switch (TaskFileFormat.valueOf(format)) {
            case CSV, MARKDOWN, NDJSON, SQL -> {
                // These append row by row, so a resumed run can continue the same file.
            }
            default -> throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(),
                    "Checkpointed export supports only CSV, MARKDOWN, NDJSON and SQL");
        }
    }
}
