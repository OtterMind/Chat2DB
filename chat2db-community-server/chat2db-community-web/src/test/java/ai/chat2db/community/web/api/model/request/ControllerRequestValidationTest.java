package ai.chat2db.community.web.api.model.request;

import ai.chat2db.community.web.api.model.request.db.StructureDiffRequest;
import ai.chat2db.community.web.api.model.request.driver.JdbcDriverDeleteRequest;
import ai.chat2db.community.web.api.model.request.driver.JdbcDriverRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void structureDiffRequiresBothEndpoints() {
        assertEquals(Set.of("source", "target"), invalidFields(new StructureDiffRequest()));
    }

    @Test
    void structureDiffValidatesNestedDataSourceIds() {
        StructureDiffRequest request = new StructureDiffRequest();
        request.setSource(structure(null, null, "REPORTING"));
        request.setTarget(structure(null, "analytics", null));

        assertEquals(Set.of("source.dataSourceId", "target.dataSourceId"), invalidFields(request));
    }

    @Test
    void structureDiffAcceptsSchemaOnlyPayloadFromOracleSelector() throws Exception {
        StructureDiffRequest request = MAPPER.readValue("""
                {"source":{"dataSourceId":1,"schemaName":"SOURCE_SCHEMA"},
                 "target":{"dataSourceId":2,"schemaName":"TARGET_SCHEMA"}}
                """, StructureDiffRequest.class);

        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void structureDiffAcceptsDatabaseOnlyAndUnicodeIdentifiers() {
        StructureDiffRequest request = new StructureDiffRequest();
        request.setSource(structure(1L, "分析库", null));
        request.setTarget(structure(2L, "analytics-db", "reporting"));

        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void driverSaveRequiresClassTypeAndReferences() {
        JdbcDriverRequest request = new JdbcDriverRequest();
        assertEquals(Set.of("jdbcDriverClass", "dbType", "jdbcDriver"), invalidFields(request));
        request.setDbType("POSTGRESQL");
        request.setJdbcDriverClass(" ");
        request.setJdbcDriver(List.of("driver.jar"));
        assertEquals(Set.of("jdbcDriverClass"), invalidFields(request));
    }

    @Test
    void driverSaveAcceptsCompleteRequest() {
        JdbcDriverRequest request = new JdbcDriverRequest();
        request.setJdbcDriverClass("org.postgresql.Driver");
        request.setDbType("POSTGRESQL");
        request.setJdbcDriver(List.of("0123456789abcdef0123456789abcdef:driver.jar"));

        assertTrue(VALIDATOR.validate(request).isEmpty());
    }

    @Test
    void driverDeleteAcceptsExistingUiPayloadWithoutClassName() throws Exception {
        JdbcDriverDeleteRequest request = MAPPER.readValue("""
                {"dbType":"POSTGRESQL","jdbcDriver":["driver.jar"]}
                """, JdbcDriverDeleteRequest.class);

        assertTrue(VALIDATOR.validate(request).isEmpty());
        assertEquals("POSTGRESQL", request.getDbType());
        assertEquals(List.of("driver.jar"), request.getJdbcDriver());
    }

    @Test
    void driverDeleteRejectsMissingTypeAndEmptyOrInvalidReferences() {
        JdbcDriverDeleteRequest request = new JdbcDriverDeleteRequest();
        assertEquals(Set.of("dbType", "jdbcDriver"), invalidFields(request));
        request.setDbType(" ");
        request.setJdbcDriver(List.of("driver.jar"));
        assertEquals(Set.of("dbType"), invalidFields(request));
        request.setDbType("POSTGRESQL");
        request.setJdbcDriver(List.of());
        assertEquals(Set.of("jdbcDriver"), invalidFields(request));
        request.setJdbcDriver(Arrays.asList(" ", null));
        assertEquals(2, VALIDATOR.validate(request).size());
    }

    private static Set<String> invalidFields(Object request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static StructureDiffRequest.StructureInfo structure(Long dataSourceId, String database, String schema) {
        StructureDiffRequest.StructureInfo info = new StructureDiffRequest.StructureInfo();
        info.setDataSourceId(dataSourceId);
        info.setDatabaseName(database);
        info.setSchemaName(schema);
        return info;
    }
}
