package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbImportTemplateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void completeTemplateRequestPassesValidation() {
        assertTrue(validator.validate(saveRequest()).isEmpty());
        assertTrue(validator.validate(deleteRequest()).isEmpty());
    }

    @Test
    void templateNameIsRequired() {
        DbImportTemplateSaveRequest request = saveRequest();
        request.setName(null);

        assertEquals(Set.of("name"), invalidFields(request));
    }

    @Test
    void deleteRequiresATemplateName() {
        DbImportTemplateDeleteRequest missing = deleteRequest();
        missing.setName(null);
        DbImportTemplateDeleteRequest blank = deleteRequest();
        blank.setName("   ");

        assertEquals(Set.of("name"), invalidFields(missing));
        assertEquals(Set.of("name"), invalidFields(blank));
    }

    @Test
    void blankNamesReachTheServiceSoItCanReportItsOwnMessageCode() {
        DbImportTemplateSaveRequest request = saveRequest();
        request.setName("   ");

        // Bean Validation only checks presence: the template service owns the blank, length, and
        // format-option rules so it can answer with import.template.nameRequired / nameTooLong /
        // formatOptionsRequired.
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void theTemplateRequestCarriesItsFileTypeAndOptions() {
        DbImportTemplateSaveRequest request = saveRequest();

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("e2e-ok", request.getName());
        assertEquals(1700000000000L, request.getCreatedAt());
        assertEquals("CSV", request.getFileType());
        assertEquals(Map.of("csvOptions", Map.of("encoding", "UTF-8", "delimiter", ";")), request.getFormatOptions());
    }

    @Test
    void theFileTypeIsOptionalOnSaveAndDelete() {
        DbImportTemplateSaveRequest withoutType = saveRequest();
        withoutType.setFileType(null);
        DbImportTemplateDeleteRequest deleteWithoutType = deleteRequest();
        deleteWithoutType.setFileType(null);

        assertAll(
                () -> assertTrue(validator.validate(withoutType).isEmpty()),
                () -> assertTrue(validator.validate(deleteWithoutType).isEmpty()),
                () -> assertEquals("CSV", deleteRequest().getFileType())
        );
    }

    private DbImportTemplateSaveRequest saveRequest() {
        DbImportTemplateSaveRequest request = new DbImportTemplateSaveRequest();
        request.setName("e2e-ok");
        request.setCreatedAt(1700000000000L);
        request.setFileType("CSV");
        request.setFormatOptions(Map.of("csvOptions", Map.of("encoding", "UTF-8", "delimiter", ";")));
        return request;
    }

    private DbImportTemplateDeleteRequest deleteRequest() {
        DbImportTemplateDeleteRequest request = new DbImportTemplateDeleteRequest();
        request.setName("e2e-ok");
        request.setFileType("CSV");
        return request;
    }

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
