package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordCreateRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordPageRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordStopRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordUpdateRequest;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.domain.api.service.task.ITaskRecordService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.task.TaskDownloadWebConverter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes async task status and cancellation endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/task")
@RestController
@Slf4j
public class TaskRecordController {

    private final ITaskRecordService taskRecordService;
    private final IIdentityService identityService;
    private final TaskDownloadWebConverter taskDownloadWebConverter;

    public TaskRecordController(ITaskRecordService taskRecordService, IIdentityService identityService,
            TaskDownloadWebConverter taskDownloadWebConverter) {
        this.taskRecordService = taskRecordService;
        this.identityService = identityService;
        this.taskDownloadWebConverter = taskDownloadWebConverter;
    }

    /**
     * Lists async tasks.
     * <p>
     * Endpoint: {@code GET /api/task/list}.
     *
     * @param taskPageParam operation parameters.
     * @return paged web result containing task.
     */
    @GetMapping("/list")
    public WebPageResult<Task> list(TaskRecordPageRequest taskPageParam) {
        PageResponse<Task> pageResult = taskRecordService.taskList(taskPageParam);
        return WebPageResult.of(pageResult.getData(), pageResult.getTotal(), pageResult.getPageNo(),
                pageResult.getPageSize());
    }

    /**
     * Gets async tasks.
     * <p>
     * Endpoint: {@code GET /api/task/get}.
     *
     * @param id identifier used to locate the target resource.
     * @return data result containing task.
     */
    @GetMapping("/get")
    public DataResult<Task> get(@RequestParam("id") Long id) {
        return DataResult.of(taskRecordService.getTask(id));
    }


    /**
     * Creates async tasks.
     * <p>
     * Endpoint: {@code POST /api/task/create}.
     *
     * @param taskCreateParam operation parameters.
     * @return data result containing long.
     */
    @PostMapping("/create")
    public DataResult<Long> create(@RequestBody TaskRecordCreateRequest taskCreateParam) {
        return DataResult.of(taskRecordService.createTask(taskCreateParam));
    }

    /**
     * Updates async tasks.
     * <p>
     * Endpoint: {@code POST /api/task/update}.
     *
     * @param taskCreateParam operation parameters.
     * @return operation result for the request.
     */
    @PostMapping("/update")
    public ActionResult update(@RequestBody TaskRecordUpdateRequest taskCreateParam) {
        taskRecordService.updateTask(taskCreateParam);
        return ActionResult.isSuccess();
    }

    /**
     * Stops async tasks.
     * <p>
     * Endpoint: {@code POST /api/task/stop}.
     *
     * @param request request containing the identifier of the task to stop.
     * @return operation result for the request.
     */
    @PostMapping("/stop")
    public ActionResult stop(@RequestBody @Valid TaskRecordStopRequest request) {
        taskRecordService.stopTask(request.getId());
        return ActionResult.isSuccess();
    }

    /**
     * Downloads async tasks.
     * <p>
     * Endpoint: {@code GET /api/task/download}.
     *
     * @param id identifier used to locate the target resource.
     * @return controller response containing resource.
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) {
        return taskDownloadWebConverter.toResponse(
                taskRecordService.resolveDownload(id, identityService.currentUserId()));
    }
}
