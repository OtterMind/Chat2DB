package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskQuery;

import java.util.List;

public interface TaskService {

    Long submitExport(ExportTaskSpec spec);

    Long submitImport(ImportTaskSpec spec);

    /**
     * Creates a new import task that continues an owned task after the rows its checkpoint already
     * processed. The original task is left untouched. Fails when the source task carries no import
     * spec or no checkpoint, or when its format cannot be resumed.
     */
    Long resumeImport(Long taskId);

    PageResponse<Task> list(TaskQuery query);

    Task get(Long taskId);

    List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit);

    List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit);

    void delete(Long taskId);

    /**
     * Requests cancellation of an owned task. Returns false when the task already reached a terminal
     * status, so repeated requests are safe.
     */
    boolean cancel(Long taskId);

    int activeTaskCount();

    void prepareForUserExit();

    void abortUserExit();

    TaskDownload resolveArtifact(Long taskId);

    /**
     * Resolves the error artifact of an owned task, the CSV listing the rows an import could not
     * store. Unlike {@link #resolveArtifact(Long)} it also serves failed and cancelled tasks,
     * because that is exactly when the error file matters.
     */
    TaskDownload resolveErrorArtifact(Long taskId);
}
