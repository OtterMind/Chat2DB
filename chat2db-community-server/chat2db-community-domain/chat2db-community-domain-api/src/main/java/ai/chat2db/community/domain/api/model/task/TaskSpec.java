package ai.chat2db.community.domain.api.model.task;

public interface TaskSpec {

    String getTaskType();

    String getTaskName();

    TaskTargetSnapshot getTarget();
}
