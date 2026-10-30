package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.imports.ImportTemplate;
import ai.chat2db.community.web.api.model.request.db.DbImportTemplateSaveRequest;
import ai.chat2db.community.web.api.model.response.db.DbImportTemplateResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbImportTemplateWebConverterTest {

    private final DbImportTemplateWebConverter converter = new DbImportTemplateWebConverter();

    @Test
    void keepsTheTemplateFieldsFromRequestToDomainAndBack() {
        DbImportTemplateSaveRequest request = request();

        ImportTemplate domain = converter.request2importTemplate(request);
        List<DbImportTemplateResponse> responses = converter.importTemplateList2response(List.of(domain));
        DbImportTemplateResponse response = responses.get(0);

        Map<String, Object> formatOptions = Map.of("csvOptions", Map.of("encoding", "UTF-8", "delimiter", ";"));
        assertAll(
                () -> assertEquals(1, responses.size()),
                () -> assertEquals("e2e-ok", response.getName()),
                () -> assertEquals(1700000000000L, response.getCreatedAt()),
                () -> assertEquals("CSV", response.getFileType()),
                () -> assertEquals("CSV", domain.getFileType()),
                () -> assertEquals(formatOptions, response.getFormatOptions()),
                () -> assertEquals(formatOptions, domain.getFormatOptions())
        );
    }

    @Test
    void toleratesAMissingTemplateAndAnEmptyList() {
        DbImportTemplateSaveRequest request = request();
        request.setFormatOptions(null);
        request.setFileType(null);

        assertAll(
                () -> assertNull(converter.request2importTemplate(request).getFormatOptions()),
                () -> assertNull(converter.request2importTemplate(request).getFileType()),
                () -> assertEquals(List.of(), converter.importTemplateList2response(null)),
                () -> assertEquals(List.of(), converter.importTemplateList2response(List.of())),
                () -> assertNull(converter.importTemplate2response(null))
        );
    }

    private DbImportTemplateSaveRequest request() {
        DbImportTemplateSaveRequest request = new DbImportTemplateSaveRequest();
        request.setName("e2e-ok");
        request.setCreatedAt(1700000000000L);
        request.setFileType("CSV");
        request.setFormatOptions(Map.of("csvOptions", Map.of("encoding", "UTF-8", "delimiter", ";")));
        return request;
    }
}
