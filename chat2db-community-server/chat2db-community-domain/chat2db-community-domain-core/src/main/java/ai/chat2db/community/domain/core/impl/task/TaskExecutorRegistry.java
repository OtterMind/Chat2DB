package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskExecutorRegistry {

    private final Map<String, TaskExecutor<?>> executors = new HashMap<>();

    public TaskExecutorRegistry(List<TaskExecutor<?>> taskExecutors) {
        for (TaskExecutor<?> executor : taskExecutors) {
            TaskExecutor<?> previous = executors.put(executor.taskType(), executor);
            if (previous != null) {
                throw new IllegalStateException("Duplicate task executor for " + executor.taskType());
            }
        }
    }

    @SuppressWarnings("unchecked")
    <S extends TaskSpec> TaskExecutor<S> require(S spec) {
        String taskType = spec.getTaskType();
        TaskExecutor<?> executor = executors.get(taskType);
        if (executor == null) {
            throw new IllegalArgumentException("No task executor registered for " + taskType);
        }
        if (!executor.specType().isInstance(spec)) {
            throw new IllegalArgumentException("Task type " + taskType + " requires "
                    + executor.specType().getSimpleName() + ", but received " + spec.getClass().getSimpleName());
        }
        return (TaskExecutor<S>) executor;
    }
}
