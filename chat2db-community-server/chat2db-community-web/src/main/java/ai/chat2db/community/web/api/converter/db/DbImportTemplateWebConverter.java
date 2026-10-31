package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.web.api.model.request.db.DbImportTemplateSaveRequest;
import ai.chat2db.community.web.api.model.response.db.DbImportTemplateResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DbImportTemplateWebConverter {

    public ImportTemplate request2importTemplate(DbImportTemplateSaveRequest request) {
        ImportTemplate importTemplate = new ImportTemplate();
        importTemplate.setName(request.getName());
        importTemplate.setCreatedAt(request.getCreatedAt());
        importTemplate.setFileType(request.getFileType());
        importTemplate.setFormatOptions(request.getFormatOptions());
        return importTemplate;
    }

    public DbImportTemplateResponse importTemplate2response(ImportTemplate importTemplate) {
        if (importTemplate == null) {
            return null;
        }
        DbImportTemplateResponse response = new DbImportTemplateResponse();
        response.setName(importTemplate.getName());
        response.setCreatedAt(importTemplate.getCreatedAt());
        response.setFileType(importTemplate.getFileType());
        response.setFormatOptions(importTemplate.getFormatOptions());
        return response;
    }

    public List<DbImportTemplateResponse> importTemplateList2response(List<ImportTemplate> importTemplates) {
        List<DbImportTemplateResponse> responses = new ArrayList<>();
        if (importTemplates == null) {
            return responses;
        }
        importTemplates.forEach(importTemplate -> responses.add(importTemplate2response(importTemplate)));
        return responses;
    }
}
