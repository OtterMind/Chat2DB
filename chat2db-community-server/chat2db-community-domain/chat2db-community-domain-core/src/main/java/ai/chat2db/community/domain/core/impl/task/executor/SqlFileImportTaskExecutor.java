package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import ai.chat2db.community.domain.core.impl.task.imports.ImportParallelAdmission;
import org.springframework.stereotype.Component;

@Component
public class SqlFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

    private final IImportFileStagingService importFileStagingService;
    private final TaskStorage taskStorage;

    public SqlFileImportTaskExecutor(IImportFileStagingService importFileStagingService,
                                     TaskStorage taskStorage) {
        this.importFileStagingService = importFileStagingService;
        this.taskStorage = taskStorage;
    }

    @Override
    public String taskType() {
        return TaskType.SQL_FILE_IMPORT.name();
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
            if (!TaskFileFormat.SQL.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "SQL import requires an SQL file");
            }
            context.reportProgress(5, TaskStage.READING.name(), "Preparing SQL import");
            ImportParallelAdmission.enforce(spec, java.util.List.of(), context);
            ImportFactory.get(format).run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "SQL import completed");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import SQL file", e);
        }
    }

    @Override
    public void cleanupTerminalResources(ImportTaskSpec spec, Long taskId) {
        RuntimeException cleanupFailure = null;
        if (spec != null && spec.getImportFileId() != null) {
            try {
                importFileStagingService.release(spec.getImportFileId());
            } catch (RuntimeException releaseFailure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, releaseFailure);
            }
        }
        if (taskId != null && taskStorage != null) {
            try {
                taskStorage.clearResumeStates(taskId);
            } catch (RuntimeException stateFailure) {
                cleanupFailure = recordCleanupFailure(cleanupFailure, stateFailure);
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static RuntimeException recordCleanupFailure(RuntimeException existing, RuntimeException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }
}
