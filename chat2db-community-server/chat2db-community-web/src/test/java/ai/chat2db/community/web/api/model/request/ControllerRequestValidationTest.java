package ai.chat2db.community.web.api.model.request;

import ai.chat2db.community.web.api.model.request.db.StructureDiffRequest;
import ai.chat2db.community.web.api.model.request.driver.JdbcDriverRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void structureDiffRequiresNestedEndpointsAndDatabaseNames() {
        StructureDiffRequest request = new StructureDiffRequest();
        assertEquals(2, validator.validate(request).size());

        request.setSource(structure(1L, "analytics", "reporting"));
        request.setTarget(structure(2L, "", "reporting"));

        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void structureDiffAcceptsCompleteUnicodeIdentifiers() {
        StructureDiffRequest request = new StructureDiffRequest();
        request.setSource(structure(1L, "分析库", null));
        request.setTarget(structure(2L, "analytics-db", "reporting"));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void jdbcDriverRequiresClassTypeAndAtLeastOneNonBlankReference() {
        JdbcDriverRequest request = new JdbcDriverRequest();
        assertEquals(3, validator.validate(request).size());

        request.setJdbcDriverClass("");
        request.setDbType("");
        request.setJdbcDriver(List.of(""));

        assertEquals(3, validator.validate(request).size());
    }

    @Test
    void jdbcDriverAcceptsCompleteRequest() {
        JdbcDriverRequest request = new JdbcDriverRequest();
        request.setJdbcDriverClass("com.mysql.cj.jdbc.Driver");
        request.setDbType("MYSQL");
        request.setJdbcDriver(List.of("0123456789abcdef0123456789abcdef:mysql-driver.jar"));

        assertTrue(validator.validate(request).isEmpty());
    }

    private static StructureDiffRequest.StructureInfo structure(Long dataSourceId, String database, String schema) {
        StructureDiffRequest.StructureInfo info = new StructureDiffRequest.StructureInfo();
        info.setDataSourceId(dataSourceId);
        info.setDatabaseName(database);
        info.setSchemaName(schema);
        return info;
    }
}
