package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.db.ImportPreview;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.domain.api.service.db.IDbMappedImportService;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.adapter.db.ImportFileUploadAdapter;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbImportWebConverter;
import ai.chat2db.community.web.api.model.request.db.DesktopImportFileRequest;
import ai.chat2db.community.web.api.model.request.db.ImportExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.ImportPreviewRequest;
import ai.chat2db.community.web.api.model.response.task.TaskSubmitResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Database-independent, bounded import preview and column mapping. Preview and execution
 * share the same parser; nothing is written during preview.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/import_preview")
@RestController
public class DbImportPreviewController {

    private final IDbImportPreviewService importPreviewService;

    private final IDbMappedImportService mappedImportService;

    private final IImportFileStagingService importFileStagingService;

    private final DbImportWebConverter importWebConverter;

    private final ImportFileUploadAdapter importFileUploadAdapter;

    public DbImportPreviewController(IDbImportPreviewService importPreviewService,
            IDbMappedImportService mappedImportService,
            IImportFileStagingService importFileStagingService, DbImportWebConverter importWebConverter,
            ImportFileUploadAdapter importFileUploadAdapter) {
        this.importPreviewService = importPreviewService;
        this.mappedImportService = mappedImportService;
        this.importFileStagingService = importFileStagingService;
        this.importWebConverter = importWebConverter;
        this.importFileUploadAdapter = importFileUploadAdapter;
    }

    @PostMapping("/upload")
    public DataResult<String> upload(@RequestParam("file") MultipartFile file) {
        return DataResult.of(importFileUploadAdapter.stage(file));
    }

    @PostMapping("/upload_local")
    public DataResult<String> uploadDesktopFile(@Valid @RequestBody DesktopImportFileRequest request) {
        return DataResult.of(importFileUploadAdapter.stageDesktopFile(request));
    }

    @PostMapping("/preview")
    public DataResult<ImportPreview> preview(@Valid @RequestBody ImportPreviewRequest request) {
        return DataResult.of(importPreviewService.preview(request.getDataSourceId(), request.getDatabaseName(),
                request.getSchemaName(), request.getTableName(), importFileStagingService.resolve(request.getFileId()),
                request.getCsvOptions()));
    }

    @PostMapping("/execute")
    public DataResult<TaskSubmitResponse> execute(@Valid @RequestBody ImportExecuteRequest request) {
        return DataResult.of(new TaskSubmitResponse(
                mappedImportService.submit(importWebConverter.toMappedImportExecution(request))));
    }
}
