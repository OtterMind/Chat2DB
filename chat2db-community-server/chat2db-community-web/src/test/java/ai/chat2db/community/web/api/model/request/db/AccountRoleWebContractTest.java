package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.enums.plugin.AccountActionTypeEnum;
import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountOperationRequest;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.response.db.AccountResponse;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRoleWebContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final DbWebConverter converter = Mappers.getMapper(DbWebConverter.class);

    @Test
    void accountCommandRequiresActionType() {
        AccountCommandRequest request = baseRequest();

        assertFalse(validator.validate(request).isEmpty());
        request.setActionType(AccountActionTypeEnum.CREATE_ROLE);
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void selectedRoleRequiresBothAccountParts() {
        AccountCommandRequest request = baseRequest();
        request.setActionType(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        request.setRoleList(List.of(new AccountRoleRequest()));

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void converterMapsExplicitRoleRequestAndResponseDtos() {
        AccountRoleRequest roleRequest = new AccountRoleRequest();
        roleRequest.setUser("reader");
        roleRequest.setHost("%");
        AccountCommandRequest request = baseRequest();
        request.setActionType(AccountActionTypeEnum.SET_DEFAULT_ROLE);
        request.setRoleList(List.of(roleRequest));

        AccountOperationRequest command = converter.request2command(request);
        AccountInfo role = new AccountInfo();
        role.setUser("reader");
        role.setHost("%");
        role.setDisplayName("reader@%");
        role.setRole(Boolean.TRUE);
        role.setDirectRoles(List.of());
        AccountInfo account = new AccountInfo();
        account.setUser("alice");
        account.setHost("%");
        account.setDirectRoles(List.of(role));
        AccountResponse response = converter.account2response(account);

        assertEquals("reader", command.getRoleList().get(0).getUser());
        assertEquals("%", command.getRoleList().get(0).getHost());
        assertEquals("reader@%", response.getDirectRoles().get(0).getDisplayName());
        assertEquals(Boolean.TRUE, response.getDirectRoles().get(0).getRole());
    }

    private AccountCommandRequest baseRequest() {
        AccountCommandRequest request = new AccountCommandRequest();
        request.setDataSourceId(42L);
        request.setUser("alice");
        request.setHost("%");
        return request;
    }
}
