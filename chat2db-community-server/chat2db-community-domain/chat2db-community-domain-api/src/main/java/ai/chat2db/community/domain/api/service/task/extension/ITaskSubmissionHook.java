package ai.chat2db.community.domain.api.service.task.extension;

import ai.chat2db.community.domain.api.model.task.extension.TaskSubmissionContext;

public interface ITaskSubmissionHook {

    void capture(TaskSubmissionContext context);
}
