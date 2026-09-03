package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbVariableControllerDatasourceContextTest {

    @Test
    void everyVariableRequestCarriesDatasourceContext() {
        assertTrue(DataSourceBaseRequest.class.isAssignableFrom(DbVariableController.VariableSessionRequest.class));
        assertTrue(IDataSourceConsoleRequestInfo.class.isAssignableFrom(DbVariableController.VariableSessionRequest.class));
        assertTrue(DbVariableController.VariableSessionRequest.class.isAssignableFrom(
                DbVariableController.VariableListRequest.class));
        assertTrue(DbVariableController.VariableSessionRequest.class.isAssignableFrom(
                DbVariableController.VariableNameRequest.class));
        assertTrue(DbVariableController.VariableSessionRequest.class.isAssignableFrom(
                DbVariableController.SetVariableRequest.class));

        DbVariableController.VariableSessionRequest request = new DbVariableController.VariableSessionRequest();
        request.setDataSourceId(7L);
        assertFalse(Validation.buildDefaultValidatorFactory().getValidator().validate(request).isEmpty());
        request.setConsoleId(101L);
        assertTrue(Validation.buildDefaultValidatorFactory().getValidator().validate(request).isEmpty());
    }
}
