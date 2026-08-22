package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Bounded import preview and column mapping (MYSQL-IMPORT-001). Preview and execution
 * share the same parser; nothing is written during preview.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/import_preview")
@RestController
public class DbImportPreviewController {

    @Autowired
    private IDbImportPreviewService importPreviewService;

    @PostMapping("/preview")
    public DataResult<Map<String, Object>> preview(@Valid @RequestBody ImportPreviewRequest request) {
        return DataResult.of(importPreviewService.preview(request.getDataSourceId(), request.getDatabaseName(),
                request.getTableName(), request.getFilePath(), request.getCsvOptions() == null ? Map.of() : request.getCsvOptions()));
    }

    @PostMapping("/execute")
    public DataResult<Map<String, Object>> execute(@Valid @RequestBody ImportExecuteRequest request) {
        return DataResult.of(importPreviewService.execute(
                request.getDataSourceId(), request.getDatabaseName(), request.getTableName(),
                request.getFilePath(), request.getCsvOptions() == null ? Map.of() : request.getCsvOptions(),
                request.getMappings(), request.getUnmappedTarget()));
    }

    @Data
    public static class ImportPreviewRequest implements IDataSourceBaseRequestInfo {
        @NotNull
        private Long dataSourceId;

        @NotBlank
        private String databaseName;

        @NotBlank
        private String tableName;

        @NotBlank
        private String filePath;

        private Map<String, Object> csvOptions;
    }

    @Data
    public static class ImportExecuteRequest implements IDataSourceBaseRequestInfo {
        @NotNull
        private Long dataSourceId;

        @NotBlank
        private String databaseName;

        @NotBlank
        private String tableName;

        @NotBlank
        private String filePath;

        private Map<String, Object> csvOptions;

        private List<Map<String, String>> mappings;

        private String unmappedTarget;
    }
}
