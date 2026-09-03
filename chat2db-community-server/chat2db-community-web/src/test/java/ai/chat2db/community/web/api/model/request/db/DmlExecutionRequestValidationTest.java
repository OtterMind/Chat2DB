package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.request.db.SelectResultOperation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmlExecutionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void executionEndpointsExposeIndependentRequestContracts() {
        assertRequestFields(SqlEditorExecuteRequest.class,
                "dataSourceId", "databaseName", "schemaName", "sql", "consoleId", "applyId", "pageNo",
                "pageSize", "single", "resultSetId", "errorContinue", "explain");
        assertRequestFields(TableBrowseRequest.class,
                "dataSourceId", "databaseName", "schemaName", "tableName", "pageNo", "pageSize");
        assertRequestFields(TableEditExecuteRequest.class,
                "dataSourceId", "databaseName", "schemaName", "sql");
        assertRequestFields(DdlExecuteRequest.class,
                "dataSourceId", "databaseName", "schemaName", "consoleId", "sql", "tableName");
    }

    @Test
    void sqlEditorExecutionDoesNotRequireTableContext() {
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setDataSourceId(1L);
        request.setSql("select * from sms_attendance");
        request.setPageNo(1);
        request.setPageSize(1000);

        assertValid(request);

        request.setSql(" ");
        assertInvalid(request);
    }

    @Test
    void tableBrowseRequiresTableButNotConsoleContext() {
        TableBrowseRequest request = new TableBrowseRequest();
        request.setDataSourceId(1L);
        request.setTableName("sms_attendance");
        request.setPageNo(1);
        request.setPageSize(1000);

        assertValid(request);

        request.setTableName(" ");
        assertInvalid(request);
    }

    @Test
    void tableEditExecutionOnlyRequiresSqlAndDatasourceContext() {
        TableEditExecuteRequest request = new TableEditExecuteRequest();
        request.setDataSourceId(1L);
        request.setSql("update sms_attendance set status = 'present' where id = 1");

        assertValid(request);
    }

    @Test
    void resultEditPreviewDoesNotRequireConsoleContext() {
        SelectResultUpdateRequest request = new SelectResultUpdateRequest();
        request.setDataSourceId(1L);
        request.setOperations(List.of(new SelectResultOperation()));

        assertValid(request);
    }

    @Test
    void ddlExecutionDoesNotRequireATableTarget() {
        DdlExecuteRequest request = new DdlExecuteRequest();
        request.setDataSourceId(1L);
        request.setSql("create database attendance_archive");

        assertValid(request);
    }

    @Test
    void everyExecutionRequestRequiresDatasourceContext() {
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setSql("select 1");

        assertInvalid(request);
    }

    private void assertInvalid(Object request) {
        assertFalse(validator.validate(request).isEmpty());
    }

    private void assertValid(Object request) {
        assertTrue(validator.validate(request).isEmpty());
    }

    private void assertRequestFields(Class<?> requestType, String... expectedFields) {
        Set<String> actualFields = Arrays.stream(requestType.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertSame(Object.class, requestType.getSuperclass());
        assertEquals(Set.of(expectedFields), actualFields);
    }
}
