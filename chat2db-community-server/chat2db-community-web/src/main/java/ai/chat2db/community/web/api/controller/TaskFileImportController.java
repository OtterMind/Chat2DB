package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.service.task.TaskService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.adapter.file.TaskImportUploadService;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.task.TaskWebConverter;
import ai.chat2db.community.web.api.model.request.task.TaskImportRequest;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@ConnectionInfoAspect
@RequestMapping("/api/tasks")
@RestController
@Slf4j
public class TaskFileImportController {

    private final TaskService taskService;

    private final TaskWebConverter taskWebConverter;

    private final TaskImportUploadService uploadFileService;

    public TaskFileImportController(TaskService taskService, TaskWebConverter taskWebConverter,
            TaskImportUploadService uploadFileService) {
        this.taskService = taskService;
        this.taskWebConverter = taskWebConverter;
        this.uploadFileService = uploadFileService;
    }

    @PostMapping(value = "/import/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DataResult<TaskSubmitResponse> submitImport(@Valid @RequestPart("request") TaskImportRequest request,
            @RequestPart("file") MultipartFile file) {
        TaskImportUploadService.StagedTaskInput stagedInput = uploadFileService.stage(file);
        File temporarySource = new File(stagedInput.sourceFile());
        boolean submitted = false;
        try {
            ImportTaskSpec spec = taskWebConverter.importUploadRequest2spec(request,
                    temporarySource.getAbsolutePath(), stagedInput.cleanupToken(), file.getOriginalFilename());
            Long taskId = taskService.submitImport(spec);
            submitted = true;
            return DataResult.of(new TaskSubmitResponse(taskId));
        } finally {
            if (!submitted) {
                deleteTemporarySource(stagedInput);
            }
        }
    }

    private void deleteTemporarySource(TaskImportUploadService.StagedTaskInput stagedInput) {
        if (!uploadFileService.cleanup(stagedInput)) {
            log.warn("Deferred cleanup for rejected task import upload: {}", stagedInput.sourceFile());
        }
    }
}
