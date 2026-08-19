package ai.chat2db.community.domain.api.model.task;

public class TaskCancelledException extends RuntimeException {

    public TaskCancelledException() {
        super("Task was cancelled");
    }
}
