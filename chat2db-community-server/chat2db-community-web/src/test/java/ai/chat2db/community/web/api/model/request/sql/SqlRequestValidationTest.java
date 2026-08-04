package ai.chat2db.community.web.api.model.request.sql;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void formatSqlMustBePresentAndAtMostFiftyThousandCharacters() {
        SqlFormatRequest request = new SqlFormatRequest();

        assertInvalid(request);
        request.setSql(" ");
        assertInvalid(request);
        request.setSql("x".repeat(50_001));
        assertInvalid(request);
        request.setSql("x".repeat(50_000));
        assertValid(request);
    }

    @Test
    void selectValidationSqlUsesTheSameBounds() {
        SqlValidSelectRequest request = new SqlValidSelectRequest();

        assertInvalid(request);
        request.setSql("x".repeat(50_001));
        assertInvalid(request);
        request.setSql("SELECT 1");
        assertValid(request);
    }

    private void assertInvalid(Object request) {
        assertFalse(validator.validate(request).isEmpty());
    }

    private void assertValid(Object request) {
        assertTrue(validator.validate(request).isEmpty());
    }
}
