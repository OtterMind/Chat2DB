package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountCommandRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void accountCommandRequiresActionType() {
        AccountCommandRequest request = new AccountCommandRequest();
        request.setDataSourceId(42L);
        request.setUser("alice");
        request.setHost("%");

        assertFalse(validator.validate(request).isEmpty());
        request.setActionType(AccountActionTypeEnum.ALTER_AUTH_PLUGIN);
        assertTrue(validator.validate(request).isEmpty());
    }
}
