package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordCreateRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordPageRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordUpdateRequest;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskDownload;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.service.task.ITaskRecordService;
import ai.chat2db.community.domain.api.service.task.ITaskSchedulerService;
import ai.chat2db.community.tools.exception.DataNotFoundException;
import ai.chat2db.community.tools.exception.PermissionDeniedBusinessException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Objects;

@Service
public class TaskRecordServiceImpl implements ITaskRecordService {

    private final IWorkspaceStorageFacade workspaceStorageFacade;
    private final ITaskSchedulerService taskSchedulerService;

    public TaskRecordServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade,
            ITaskSchedulerService taskSchedulerService) {
        this.workspaceStorageFacade = workspaceStorageFacade;
        this.taskSchedulerService = taskSchedulerService;
    }

    @Override
    public PageResponse<Task> taskList(TaskRecordPageRequest request) {
        return workspaceStorageFacade.taskList(request);
    }

    @Override
    public Task getTask(Long id) {
        return workspaceStorageFacade.getTask(id);
    }

    @Override
    public Long createTask(TaskRecordCreateRequest request) {
        return workspaceStorageFacade.createTask(request);
    }

    @Override
    public void updateTask(TaskRecordUpdateRequest request) {
        workspaceStorageFacade.updateTask(request);
    }

    @Override
    public void stopTask(Long id) {
        if (!taskSchedulerService.cancel(id)) {
            Task task = getTask(id);
            if (task == null || StringUtils.equalsAnyIgnoreCase(
                    task.getTaskStatus(), "FINISHED", STATUS_STOP, "ERROR")) {
                return;
            }
        }
        TaskRecordUpdateRequest request = new TaskRecordUpdateRequest();
        request.setId(id);
        request.setTaskStatus(STATUS_STOP);
        request.setDownloadUrl("");
        updateTask(request);
    }

    @Override
    public TaskDownload resolveDownload(Long id, Long userId) {
        Task task = getTask(id);
        if (task == null || StringUtils.isBlank(task.getDownloadUrl())) {
            throw new DataNotFoundException();
        }
        if (!Objects.equals(userId, task.getUserId())) {
            throw new PermissionDeniedBusinessException();
        }

        File file = new File(task.getDownloadUrl());
        if (!file.exists() || !file.canRead()) {
            throw new DataNotFoundException();
        }
        return TaskDownload.builder()
                .fileName(file.getName())
                .fileUri(file.toURI().toString())
                .build();
    }
}
