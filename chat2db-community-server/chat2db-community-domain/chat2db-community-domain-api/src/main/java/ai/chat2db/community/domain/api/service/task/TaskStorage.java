package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.ResumeState;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskArtifact;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskProgress;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.model.task.TaskStatus;
import ai.chat2db.community.domain.api.model.task.TaskStatusPatch;

import java.util.List;
import java.util.Optional;

public interface TaskStorage {

    Task create(Task task, TaskEvent createdEvent);

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

    /**
     * Every artifact recorded for the task, primary first. Reading a task through {@link #get(Long)}
     * also fills {@code Task.artifacts}; this method is the standalone lookup for list and download paths.
     */
    List<TaskArtifact> listArtifacts(Long taskId);

    /**
     * Records one published artifact, replacing any earlier row with the same {@code artifactId}.
     * The task must exist.
     */
    void saveArtifact(Long taskId, TaskArtifact artifact);

    /**
     * Forgets one artifact row without touching the file; used when a completion race is lost.
     */
    void deleteArtifact(Long taskId, String artifactId);

    /**
     * Non-terminal tasks that carry at least one persisted resume state and can therefore be resumed
     * instead of being failed by startup reconciliation.
     */
    List<Task> listResumableTasks();

    /**
     * Stores one shard checkpoint, replacing any earlier row for the same {@code shardNo}. The task must exist.
     */
    void saveResumeState(Long taskId, ResumeState state);

    List<ResumeState> listResumeStates(Long taskId);

    void clearResumeStates(Long taskId);
}
