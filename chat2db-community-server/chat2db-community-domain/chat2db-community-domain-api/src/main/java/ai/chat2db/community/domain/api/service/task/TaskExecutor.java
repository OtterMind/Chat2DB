package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskType;

public interface TaskExecutor<S extends TaskSpec> {

    String taskType();

    Class<S> specType();

    void execute(S spec, TaskExecutionContext context);

    /**
     * Releases task-owned resources only after the task has reached a durable terminal state.
     * A process crash never invokes this hook, so sources required by persisted checkpoints stay
     * available for startup recovery.
     */
    default void cleanupTerminalResources(S spec, Long taskId) {
    }
}
