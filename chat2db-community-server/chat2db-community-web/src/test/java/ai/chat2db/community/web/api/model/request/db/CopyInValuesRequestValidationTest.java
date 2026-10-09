package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopyInValuesRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void resultSetCopyDoesNotRequireAConsole() {
        CopyInValuesRequest request = request("RESULT_SET");

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void externalTextCopyDoesNotRequireAConsole() {
        CopyInValuesRequest request = request("EXTERNAL_TEXT");
        request.setExternalValues(List.of("O'Brien", "value"));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void existingClientsCanStillSupplyAConsoleId() {
        CopyInValuesRequest request = request("RESULT_SET");
        request.setConsoleId(123L);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void dataSourceIsStillRequiredWithoutAConsole() {
        CopyInValuesRequest request = request("RESULT_SET");
        request.setDataSourceId(null);

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("dataSourceId"), invalidFields);
    }

    private CopyInValuesRequest request(String sourceType) {
        CopyInValuesRequest request = new CopyInValuesRequest();
        request.setDataSourceId(42L);
        request.setSourceType(sourceType);
        return request;
    }
}
