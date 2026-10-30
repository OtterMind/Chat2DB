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
     * Terminal tasks, including the ones whose artifacts may still occupy disk. Storages that can
     * enumerate their history return it through {@link #listTasksForRecovery()}.
     */
    default List<Task> listTerminalTasks() {
        return listTasksForRecovery().stream()
                .filter(task -> task.getStatus() != null && TaskStatus.isTerminal(task.getStatus()))
                .toList();
    }

    /**
     * Removes a terminal task while retaining enough storage state to roll back if the coordinated commit fails.
     */
    boolean deleteTerminalTask(Long taskId, Runnable commitAction);

    /**
     * Persists the resume checkpoint of a task, including a terminal one. Cancellation persists the
     * terminal status before the running import stops, and the batches still in flight keep
     * committing afterwards; their final contiguous position is written once the workers drained,
     * which is after the task reached that status.
     *
     * @return {@code true} when the checkpoint was written, {@code false} when the task is unknown
     *         or the arguments are incomplete
     */
    default boolean saveCheckpoint(Long taskId, String checkpointJson) {
        throw new UnsupportedOperationException("This task storage does not support checkpoints");
    }

    /**
     * Attaches the secondary error artifact of a task while keeping the primary one in
     * {@link Task#getArtifactId()}. The artifact is identified by its published absolute path, the
     * same value {@code ArtifactService#publish} returns. Unlike a checkpoint this stays valid after
     * the task finished: the error file produced by a failed or cancelled import must remain
     * discoverable.
     *
     * @return {@code true} when the artifact was attached, {@code false} when the task is unknown or
     *         the arguments are incomplete
     */
    default boolean attachErrorArtifact(Long taskId, String errorArtifactId) {
        throw new UnsupportedOperationException("This task storage does not support error artifacts");
    }
}
