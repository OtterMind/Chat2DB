package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;

@Component
public class DataFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

    private final IDbImportPreviewService importPreviewService;

    private final IImportFileRegistry importFileRegistry;

    public DataFileImportTaskExecutor(IDbImportPreviewService importPreviewService,
                                      IImportFileRegistry importFileRegistry) {
        this.importPreviewService = importPreviewService;
        this.importFileRegistry = importFileRegistry;
    }

    @Override
    public String taskType() {
        return TaskType.DATA_FILE_IMPORT.name();
    }

    @Override
    public Class<ImportTaskSpec> specType() {
        return ImportTaskSpec.class;
    }

    @Override
    public void execute(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            TaskExecutorSupport.requireReadableSource(spec.getSourceFile());
            String format = TaskExecutorSupport.requireFormat(spec.getFormat());
            if (TaskFileFormat.SQL.name().equals(format) || TaskFileFormat.ZIP.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Unsupported data import format");
            }
            context.reportProgress(5, TaskStage.READING.name(), "Preparing data import");
            if (TaskFileFormat.CSV.name().equals(format) && usesMappedCsvImport(spec)) {
                executeMappedCsvImport(spec, context);
                return;
            }
            IImportStrategy strategy = ImportFactory.get(format);
            strategy.run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "Data import completed");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
        } finally {
            if (spec.getImportFileId() != null) {
                importFileRegistry.release(spec.getImportFileId());
            }
        }
    }

    private boolean usesMappedCsvImport(ImportTaskSpec spec) {
        return spec.getCsvOptions() != null
                || spec.getMappings() != null
                || spec.getUnmappedTarget() != null;
    }

    private void executeMappedCsvImport(ImportTaskSpec spec, TaskExecutionContext context) {
        Map<String, Object> result = importPreviewService.execute(
                spec.getTarget().getDataSourceId(),
                spec.getTarget().getDatabaseName(),
                spec.getTarget().getSchemaName(),
                spec.getTarget().getTableName(),
                new File(spec.getSourceFile()),
                spec.getCsvOptions() == null ? Map.of() : spec.getCsvOptions().toMap(),
                spec.getMappings(),
                spec.getUnmappedTarget(),
                context);
        context.reportProgress(95, TaskStage.IMPORTING.name(), "Data import completed");
        context.logInfo(TaskEventCode.BATCH_EXECUTED.name(), "CSV data import completed",
                Map.of("totalRows", result.getOrDefault("totalRows", 0),
                        "successCount", result.getOrDefault("successCount", 0),
                        "failedCount", result.getOrDefault("failedCount", 0),
                        "skippedCount", result.getOrDefault("skippedCount", 0)));
        Object errors = result.get("errors");
        if (errors instanceof List<?> list && !list.isEmpty()) {
            context.logWarn(TaskEventCode.BATCH_EXECUTED.name(), "CSV data import completed with row errors",
                    Map.of("errors", list));
        }
    }
}
