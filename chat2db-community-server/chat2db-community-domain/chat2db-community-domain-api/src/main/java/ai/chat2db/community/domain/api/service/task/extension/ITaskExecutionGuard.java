package ai.chat2db.community.domain.api.service.task.extension;

import ai.chat2db.community.domain.api.model.task.extension.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.task.extension.TaskStatementContext;

public interface ITaskExecutionGuard {

    void beforeTask(TaskExecutionContext context);

    default void beforeStatement(TaskStatementContext context) {
    }
}
