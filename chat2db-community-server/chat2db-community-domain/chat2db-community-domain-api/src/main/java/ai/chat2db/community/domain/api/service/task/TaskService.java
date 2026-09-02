package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportPreview;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskQuery;

import java.util.List;

public interface TaskService {

    Long submitExport(ExportTaskSpec spec);

    Long submitImport(ImportTaskSpec spec);

    /**
     * Parses the import source and resolves its columns against the target table without writing
     * anything, so the UI can show the mapping and sample rows before submission.
     */
    ImportPreview previewImport(ImportTaskSpec spec);

    /**
     * Re-runs an interrupted task that startup reconciliation marked resumable, using the persisted
     * spec and the connection context of the current request.
     *
     * @return the task id being resumed
     */
    Long resume(Long taskId);

    PageResponse<Task> list(TaskQuery query);

    Task get(Long taskId);

    List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit);

    List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit);

    void delete(Long taskId);

    int activeTaskCount();

    void prepareForUserExit();

    void abortUserExit();

    TaskDownload resolveArtifact(Long taskId);

    TaskDownload resolveArtifact(Long taskId, String artifactId);

    List<TaskArtifact> listArtifacts(Long taskId);
}
