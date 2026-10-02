package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;

import java.util.List;
import java.util.Optional;

public interface TaskStorage {

    Task create(Task task, List<TaskEvent> initialEvents);

    default Task create(Task task, TaskEvent createdEvent) {
        return create(task, List.of(createdEvent));
    }

    Optional<Task> get(Long taskId);

    PageResponse<Task> list(TaskQuery query);

    boolean compareAndSetStatus(Long taskId, String expectedStatus, String targetStatus,
            TaskStatusPatch patch, TaskEvent lifecycleEvent);

    boolean updateProgressIfRunning(Long taskId, TaskProgress progress);

    TaskEvent appendEvent(TaskEvent event);

    List<TaskEvent> listEvents(Long taskId, long afterSequence, int limit);

    List<TaskEvent> listEventsBefore(Long taskId, Long beforeSequence, int limit);

    List<Task> listNonTerminalTasks();

    default List<Task> listTasksForRecovery() {
        return listNonTerminalTasks();
    }

    /**
     * Removes a terminal task while retaining enough storage state to roll back if the coordinated commit fails.
     */
    boolean deleteTerminalTask(Long taskId, Runnable commitAction);
}
