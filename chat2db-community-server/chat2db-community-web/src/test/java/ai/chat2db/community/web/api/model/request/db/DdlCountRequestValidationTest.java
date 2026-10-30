package ai.chat2db.community.web.api.model.request.db;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlCountRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void tableDataCountDoesNotRequireAConsole() {
        DdlCountRequest request = tableDataRequest();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void consoleCountDoesNotRequireATableNameOrConsole() {
        DdlCountRequest request = consoleRequest();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void existingClientsCanStillSupplyAConsoleId() {
        DdlCountRequest request = consoleRequest();
        request.setConsoleId(123L);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void sqlAndDataSourceAreStillRequired() {
        DdlCountRequest request = new DdlCountRequest();

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("dataSourceId", "sql"), invalidFields);
    }

    @Test
    void missingSqlReportsAReadableFieldError() {
        DdlCountRequest request = new DdlCountRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("bench");

        String message = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("; "));
        assertTrue(message.contains("sql"), message);
        assertFalse(message.isBlank());
    }

    private DdlCountRequest tableDataRequest() {
        DdlCountRequest request = new DdlCountRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("bench");
        request.setSql("SELECT * FROM `bench`.`e2e_import`");
        request.setTableName("e2e_import");
        return request;
    }

    private DdlCountRequest consoleRequest() {
        DdlCountRequest request = new DdlCountRequest();
        request.setDataSourceId(42L);
        request.setDatabaseName("bench");
        request.setSql("SELECT * FROM bench.e2e_import");
        return request;
    }
}
