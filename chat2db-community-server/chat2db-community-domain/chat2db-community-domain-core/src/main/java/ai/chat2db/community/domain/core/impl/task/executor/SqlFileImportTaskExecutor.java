package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import org.springframework.stereotype.Component;

@Component
public class SqlFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

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
            ImportFactory.get(format).run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "SQL import completed");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import SQL file", e);
        }
    }
}
