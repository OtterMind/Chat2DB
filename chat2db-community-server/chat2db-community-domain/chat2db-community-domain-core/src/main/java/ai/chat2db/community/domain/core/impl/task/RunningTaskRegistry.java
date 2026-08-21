package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class RunningTaskRegistry {

    private final ConcurrentMap<Long, RunningTask> tasks = new ConcurrentHashMap<>();

    void register(RunningTask task) {
        RunningTask existing = tasks.putIfAbsent(task.taskId(), task);
        if (existing != null) {
            throw new IllegalStateException("Task is already registered: " + task.taskId());
        }
    }

    RunningTask get(Long taskId) {
        return tasks.get(taskId);
    }

    void remove(Long taskId, RunningTask task) {
        tasks.remove(taskId, task);
    }
}
