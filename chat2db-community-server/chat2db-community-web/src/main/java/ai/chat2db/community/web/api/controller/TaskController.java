package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.task.TaskEvent;
import ai.chat2db.community.domain.api.model.task.TaskQuery;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.task.TaskDownloadWebConverter;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskEventQueryRequest;
import ai.chat2db.community.web.api.model.request.task.TaskExportRequest;
import ai.chat2db.community.web.api.model.request.task.TaskIdRequest;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@ConnectionInfoAspect
@RequestMapping("/api/tasks")
@RestController
public class TaskController {

    private final TaskService taskService;

    private final TaskWebConverter taskWebConverter;

    private final TaskDownloadWebConverter taskDownloadWebConverter;

    public TaskController(TaskService taskService, TaskWebConverter taskWebConverter,
            TaskDownloadWebConverter taskDownloadWebConverter) {
        this.taskService = taskService;
        this.taskWebConverter = taskWebConverter;
        this.taskDownloadWebConverter = taskDownloadWebConverter;
    }

    @PostMapping("/export")
    public DataResult<TaskSubmitResponse> submitExport(@Valid @RequestBody TaskExportRequest request) {
        Long taskId = taskService.submitExport(taskWebConverter.exportRequest2spec(request));
        return DataResult.of(new TaskSubmitResponse(taskId));
    }

    @PostMapping("/import")
    public DataResult<TaskSubmitResponse> submitImport(@Valid @RequestBody TaskImportRequest request) {
        Long taskId = taskService.submitImport(taskWebConverter.importRequest2spec(request));
        return DataResult.of(new TaskSubmitResponse(taskId));
    }

    @GetMapping("/list")
    public WebPageResult<Task> list(TaskQuery query) {
        PageResponse<Task> page = taskService.list(query);
        return WebPageResult.of(page.getData(), page.getTotal(), page.getPageNo(), page.getPageSize());
    }

    @GetMapping("/get")
    public DataResult<Task> get(@Valid TaskIdRequest request) {
        return DataResult.of(taskService.get(request.getTaskId()));
    }

    @GetMapping("/events")
    public DataResult<List<TaskEvent>> events(@Valid TaskEventQueryRequest request) {
        if (request.getAfterSequence() != null && request.getBeforeSequence() != null) {
            throw new IllegalArgumentException("afterSequence and beforeSequence cannot be used together");
        }
        if (request.getAfterSequence() != null) {
            return DataResult.of(taskService.listEvents(request.getTaskId(), request.getAfterSequence(),
                    request.effectiveLimit()));
        }
        return DataResult.of(taskService.listEventsBefore(request.getTaskId(), request.getBeforeSequence(),
                request.effectiveLimit()));
    }

    @PostMapping("/cancel")
    public DataResult<Task> cancel(@Valid @RequestBody TaskIdRequest request) {
        return DataResult.of(taskService.cancel(request.getTaskId()));
    }

    @DeleteMapping("/delete")
    public ActionResult delete(@Valid TaskIdRequest request) {
        taskService.delete(request.getTaskId());
        return ActionResult.isSuccess();
    }

    @GetMapping("/artifact")
    public ResponseEntity<Resource> artifact(@Valid TaskIdRequest request) {
        return taskDownloadWebConverter.toResponse(taskService.resolveArtifact(request.getTaskId()));
    }

    @GetMapping("/active-count")
    public DataResult<Integer> activeCount() {
        return DataResult.of(taskService.activeTaskCount());
    }

    @PostMapping("/prepare-user-exit")
    public ActionResult prepareForExit() {
        taskService.prepareForUserExit();
        return ActionResult.isSuccess();
    }

    @PostMapping("/abort-user-exit")
    public ActionResult abortUserExit() {
        taskService.abortUserExit();
        return ActionResult.isSuccess();
    }
}
