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

    PageResponse<Task> list(TaskQuery query);

    Task get(Long taskId);

    List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit);

    List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit);

    Task cancel(Long taskId);

    void delete(Long taskId);

    int activeTaskCount();

    void prepareForUserExit();

    TaskDownload resolveArtifact(Long taskId);
}
