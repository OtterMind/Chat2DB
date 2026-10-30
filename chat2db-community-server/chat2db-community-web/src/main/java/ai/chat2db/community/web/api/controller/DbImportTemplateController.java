package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.service.imports.IImportTemplateService;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.converter.db.DbImportTemplateWebConverter;
import ai.chat2db.community.web.api.model.request.db.DbImportTemplateDeleteRequest;
import ai.chat2db.community.web.api.model.request.db.DbImportTemplateSaveRequest;
import ai.chat2db.community.web.api.model.response.db.DbImportTemplateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Import mapping templates. Templates are a global import configuration: they are not scoped to a
 * data source, database, or table, and the stored list is the only scope.
 */
@RestController
@RequestMapping("/api/rdb/import/templates")
public class DbImportTemplateController {

    private final IImportTemplateService importTemplateService;

    private final DbImportTemplateWebConverter importTemplateWebConverter;

    public DbImportTemplateController(IImportTemplateService importTemplateService,
            DbImportTemplateWebConverter importTemplateWebConverter) {
        this.importTemplateService = importTemplateService;
        this.importTemplateWebConverter = importTemplateWebConverter;
    }

    /**
     * Lists every saved import template, or the templates of one file type.
     * <p>
     * Endpoint: {@code GET /api/rdb/import/templates?fileType=CSV}. The wizard's format step asks
     * for the file type it is importing, so it receives only the presets it can apply.
     *
     * @param fileType optional {@code TaskFileFormat} name; absent lists every file type.
     * @return list result containing the stored templates.
     */
    @GetMapping("")
    public ListResult<DbImportTemplateResponse> list(
            @RequestParam(value = "fileType", required = false) String fileType) {
        List<DbImportTemplateResponse> responses =
                importTemplateWebConverter.importTemplateList2response(importTemplateService.list(fileType));
        return ListResult.of(responses);
    }

    /**
     * Saves one import template, replacing the stored template with the same name.
     * <p>
     * Endpoint: {@code POST /api/rdb/import/templates}.
     *
     * @param request template to store; the name identifies it.
     * @return list result containing the templates stored after the save.
     */
    @PostMapping("")
    public ListResult<DbImportTemplateResponse> save(@Valid @RequestBody DbImportTemplateSaveRequest request) {
        List<DbImportTemplateResponse> responses = importTemplateWebConverter.importTemplateList2response(
                importTemplateService.upsert(importTemplateWebConverter.request2importTemplate(request)));
        return ListResult.of(responses);
    }

    /**
     * Deletes one import template by name.
     * <p>
     * Endpoint: {@code DELETE /api/rdb/import/templates?name=...}.
     *
     * @param request delete payload carrying the template name and, optionally, its file type.
     * @return list result containing the templates stored after the delete.
     */
    @DeleteMapping("")
    public ListResult<DbImportTemplateResponse> delete(@Valid DbImportTemplateDeleteRequest request) {
        List<DbImportTemplateResponse> responses = importTemplateWebConverter.importTemplateList2response(
                importTemplateService.delete(request.getName(), request.getFileType()));
        return ListResult.of(responses);
    }
}
