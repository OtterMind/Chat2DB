package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportManifest;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.task.TaskExecutionMode;
import ai.chat2db.community.domain.api.service.task.TaskStorage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.domain.core.impl.task.imports.IImportStrategy;
import ai.chat2db.community.domain.core.impl.task.imports.ImportFactory;
import ai.chat2db.community.domain.core.impl.task.imports.CsvManifestImporter;
import ai.chat2db.community.domain.core.impl.task.imports.CsvManifestPreparer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class DataFileImportTaskExecutor implements TaskExecutor<ImportTaskSpec> {

    @Autowired
    private IImportFileStagingService importFileStagingService;

    @Autowired
    private TaskStorage taskStorage;

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
        ImportManifest manifest = null;
        CsvManifestPreparer manifestPreparer = null;
        try {
            TaskExecutorSupport.requireReadableSource(spec.getSourceFile());
            String format = TaskExecutorSupport.requireFormat(spec.getFormat());
            if (TaskFileFormat.SQL.name().equals(format) || TaskFileFormat.ZIP.name().equals(format)) {
                throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                        "Unsupported data import format");
            }
            context.reportProgress(5, TaskStage.READING.name(), "Preparing data import");
            manifest = context.taskId() == null || taskStorage == null ? null
                    : taskStorage.loadImportManifest(context.taskId()).orElse(null);
            if (manifest == null && TaskFileFormat.CSV.name().equals(format)
                    && TaskExecutionMode.isUltraFast(spec.getMode())) {
                manifestPreparer = new CsvManifestPreparer(taskStorage);
                manifest = manifestPreparer.prepare(spec, context);
            }
            if (manifest != null) {
                if (!TaskFileFormat.CSV.name().equals(format) || !TaskExecutionMode.isUltraFast(spec.getMode())) {
                    throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                            "A persisted import manifest requires an ultra-fast CSV task");
                }
                new CsvManifestImporter(taskStorage).execute(spec, context, manifest);
                context.reportProgress(95, TaskStage.IMPORTING.name(), "Manifest import completed");
                completed = true;
                return;
            }
            IImportStrategy strategy = ImportFactory.get(format);
            strategy.run(spec, context);
            context.reportProgress(95, TaskStage.IMPORTING.name(), "Data import completed");
            completed = true;
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
        } finally {
            // Interrupted imports still need the exact staged source to resume from checkpoints.
            if (completed && spec.getImportFileId() != null) {
                importFileStagingService.release(spec.getImportFileId());
            }
            if (completed && manifest != null) {
                (manifestPreparer == null ? new CsvManifestPreparer(taskStorage) : manifestPreparer)
                        .cleanup(manifest);
            }
        }
    }
}
