package ai.chat2db.community.domain.api.service.task;

import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskType;

public interface TaskExecutor<S extends TaskSpec> {

    String taskType();

    Class<S> specType();

    void execute(S spec, TaskExecutionContext context);
}
