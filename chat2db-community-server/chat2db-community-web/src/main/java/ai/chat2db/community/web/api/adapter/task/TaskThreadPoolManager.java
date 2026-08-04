package ai.chat2db.community.web.api.adapter.task;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskThreadPoolManager {

    private static final Map<Long, TaskThread> taskMap = new ConcurrentHashMap<>();

    public static void submitTask(Long taskId, TaskThread task) {
        taskMap.put(taskId, task);
        task.start();
    }

    public static boolean cancelTask(Long taskId) {
        TaskThread thread = taskMap.get(taskId);
        return thread != null && thread.cancel();
    }

    public static void remove(Long taskId, TaskThread task) {
        taskMap.remove(taskId, task);
    }
}
