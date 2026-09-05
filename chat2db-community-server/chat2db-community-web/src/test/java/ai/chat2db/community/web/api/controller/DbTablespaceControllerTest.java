package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.web.api.model.request.db.TablespaceCapabilityRequest;
import ai.chat2db.community.web.api.model.request.db.DatabaseObjectDeleteExecuteRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTablespaceControllerTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void capabilityRequestExposesDatasourceContextToConnectionAspect() throws Exception {
        Method method = DbTablespaceController.class.getMethod("capability", TablespaceCapabilityRequest.class);

        assertEquals(TablespaceCapabilityRequest.class, method.getParameterTypes()[0]);
    }

    @Test
    void capabilityRequiresDatasourceId() {
        TablespaceCapabilityRequest request = new TablespaceCapabilityRequest();

        assertFalse(validator.validate(request).isEmpty());
        request.setDataSourceId(42L);
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void deleteExecuteRequestCarriesTablespaceName() {
        DatabaseObjectDeleteExecuteRequest request = new DatabaseObjectDeleteExecuteRequest();
        request.setDataSourceId(42L);
        request.setTablespaceName("ts_archive");
        request.setConfirmName("ts_archive");

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("ts_archive", request.getTablespaceName());
    }
}
