package ai.chat2db.community.web.api.adapter.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordUpdateRequest;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.domain.api.service.task.ITaskSchedulerService;
import ai.chat2db.community.tools.util.ContextUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TaskSchedulerAdapter implements ITaskSchedulerService {

    private final IWorkspaceStorageFacade workspaceStorageFacade;

    public TaskSchedulerAdapter(IWorkspaceStorageFacade workspaceStorageFacade) {
        this.workspaceStorageFacade = workspaceStorageFacade;
    }

    @Override
    public ITaskAsyncCall asyncCall(Long taskId) {
        return map -> updateTask(taskId, map);
    }

    @Override
    public void submit(Long taskId, AsyncContext asyncContext, Runnable runnable) {
        TaskThreadPoolManager.submitTask(taskId,
                new TaskThread(ContextUtils.queryContext(), asyncContext, taskId, runnable));
    }

    @Override
    public boolean cancel(Long taskId) {
        return TaskThreadPoolManager.cancelTask(taskId);
    }

    private void updateTask(Long taskId, Map<String, Object> map) {
        TaskRecordUpdateRequest taskUpdateRequest = new TaskRecordUpdateRequest();
        taskUpdateRequest.setId(taskId);
        Object progress = map.get("progress");
        if (progress != null) {
            taskUpdateRequest.setTaskProgress(String.valueOf(progress));
        }
        Object info = map.get("info");
        if (info != null) {
            taskUpdateRequest.setInfoLog(String.valueOf(info));
        }
        Object error = map.get("error");
        if (error != null) {
            taskUpdateRequest.setErrorLog(String.valueOf(error));
        }
        Object status = map.get("status");
        if (status != null) {
            taskUpdateRequest.setTaskStatus(String.valueOf(status));
        }
        Object downloadUrl = map.get("downloadUrl");
        if (downloadUrl != null) {
            taskUpdateRequest.setDownloadUrl(String.valueOf(downloadUrl));
        }
        workspaceStorageFacade.updateTask(taskUpdateRequest);
    }
}
