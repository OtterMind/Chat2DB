package ai.chat2db.community.web.api.model.request.sql;

import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExplainRequestContractTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void cancelRequestRequiresAndCarriesDatasourceConsoleContext() {
        SqlExplainCancelRequest request = new SqlExplainCancelRequest();
        request.setRequestId("explain-editor-1");
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> "dataSourceId".equals(violation.getPropertyPath().toString())));

        request.setDataSourceId(42L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setConsoleId(7L);

        assertTrue(validator.validate(request).isEmpty());
        assertInstanceOf(IDataSourceConsoleRequestInfo.class, request);
    }
}
