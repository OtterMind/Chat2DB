package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.ImportResumability;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class DataFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

    @Autowired
    private IImportFileStagingService importFileStagingService;

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
        boolean completed = false;
        try {
            TaskExecutorSupport.requireReadableSource(spec.getSourceFile());
            String format = TaskExecutorSupport.requireFormat(spec.getFormat());
            if (TaskFileFormat.SQL.name().equals(format) || TaskFileFormat.ZIP.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Unsupported data import format");
            }
            context.reportProgress(5, TaskStage.READING.name(), "Preparing data import");
            context.logInfo(TaskEventCode.IMPORT_PREPARING.name(), "Preparing data import");
            IImportStrategy strategy = ImportFactory.get(format);
            strategy.run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "Data import completed");
            context.logInfo(TaskEventCode.IMPORT_COMPLETED.name(), "Data import completed");
            completed = true;
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
        } finally {
            // A resumable import that did not complete keeps its staged source file: continuing it
            // reads the same bytes again, so releasing the upload would make the resume impossible.
            // The file stays claimed and is removed by the staging expiry, by the deletion of the
            // task, or by the next completed run of the same upload.
            if (spec.getImportFileId() != null && (completed || !ImportResumability.isResumable(spec))) {
                importFileStagingService.release(spec.getImportFileId());
            }
        }
    }
}
