package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlCountRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.web.api.model.request.db.DdlCountRequest;
import ai.chat2db.community.web.api.model.request.db.DdlExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.TableBrowseRequest;
import ai.chat2db.community.web.api.model.request.db.TableEditExecuteRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbWebConverterDmlRequestTest {

    private final DbWebConverter converter = Mappers.getMapper(DbWebConverter.class);

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {false, true})
    void preservesDdlErrorContinuationPolicy(Boolean errorContinue) {
        DdlExecuteRequest request = new DdlExecuteRequest();
        request.setDataSourceId(1L);
        request.setSql("CREATE DATABASE reports; COMMENT ON DATABASE reports IS 'reporting';");
        request.setErrorContinue(errorContinue);

        DbDlExecuteRequest result = converter.ddlExecutionRequest(request).getExecuteRequest();

        assertEquals(errorContinue, result.getErrorContinue());
        assertEquals(request.getSql(), result.getSql());
    }

    @Test
    void mapsSqlEditorRequestWithoutTableContext() {
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setSql("select 1");
        request.setConsoleId(2L);
        request.setApplyId(3L);
        request.setPageNo(4);
        request.setPageSize(5);
        request.setSingle(true);
        request.setResultSetId(6);
        request.setErrorContinue(true);
        request.setExplain(true);
        request.setSqlParameters(true);
        request.setParameters(Map.of("id", SqlParameterValue.number("123")));
        request.setPositionalParameters(List.of(SqlParameterValue.string("a")));

        DbDlExecuteRequest result = converter.request2param(request);

        assertEquals(request.getDataSourceId(), result.getDataSourceId());
        assertEquals(request.getDatabaseName(), result.getDatabaseName());
        assertEquals(request.getSchemaName(), result.getSchemaName());
        assertEquals(request.getSql(), result.getSql());
        assertEquals(request.getConsoleId(), result.getConsoleId());
        assertEquals(request.getApplyId(), result.getApplyId());
        assertEquals(request.getPageNo(), result.getPageNo());
        assertEquals(request.getPageSize(), result.getPageSize());
        assertEquals(request.isSingle(), result.isSingle());
        assertEquals(request.getResultSetId(), result.getResultSetId());
        assertEquals(request.getErrorContinue(), result.getErrorContinue());
        assertEquals(request.isExplain(), result.isExplain());
        assertEquals(request.getSqlParameters(), result.getSqlParameters());
        assertEquals(request.getParameters(), result.getParameters());
        assertEquals(request.getPositionalParameters(), result.getPositionalParameters());
        assertNull(result.getTableName());
        assertNull(result.getPageSizeAll());
    }

    @Test
    void sqlEditorRequestWithoutParametersKeepsPlainExecution() {
        SqlEditorExecuteRequest request = new SqlEditorExecuteRequest();
        request.setDataSourceId(1L);
        request.setSql("select ':id', '?'");

        DbDlExecuteRequest result = converter.sqlEditorExecutionRequest(request).getExecuteRequest();

        assertNull(result.getSqlParameters());
        assertNull(result.getParameters());
        assertNull(result.getPositionalParameters());
    }

    @Test
    void countRequestCarriesTheValuesOfTheResultItCounts() {
        DdlCountRequest request = new DdlCountRequest();
        request.setDataSourceId(1L);
        request.setSql("select * from t where id = ?");
        request.setPositionalParameters(List.of(SqlParameterValue.number("7")));

        DbDlCountRequest result = converter.request2param(request);

        assertEquals(request.getSql(), result.getSql());
        assertEquals(request.getPositionalParameters(), result.getPositionalParameters());
        assertNull(result.getParameters());
    }

    @Test
    void mapsTableBrowseRequestWithoutConsoleContext() {
        TableBrowseRequest request = new TableBrowseRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setTableName("sms_attendance");
        request.setPageNo(2);
        request.setPageSize(100);

        DbDlExecuteRequest result = converter.request2param(request);

        assertEquals(request.getDataSourceId(), result.getDataSourceId());
        assertEquals(request.getDatabaseName(), result.getDatabaseName());
        assertEquals(request.getSchemaName(), result.getSchemaName());
        assertEquals(request.getTableName(), result.getTableName());
        assertEquals(request.getPageNo(), result.getPageNo());
        assertEquals(request.getPageSize(), result.getPageSize());
        assertNull(result.getSql());
        assertNull(result.getPageSizeAll());
        assertNull(result.getConsoleId());
        assertNull(result.getApplyId());
        assertFalse(result.isSingle());
    }

    @Test
    void mapsTableEditRequestWithoutBrowseOrConsoleFields() {
        TableEditExecuteRequest request = new TableEditExecuteRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setSql("update sms_attendance set status = 'present' where id = 1");

        DbDlExecuteRequest result = converter.request2param(request);

        assertEquals(request.getDataSourceId(), result.getDataSourceId());
        assertEquals(request.getDatabaseName(), result.getDatabaseName());
        assertEquals(request.getSchemaName(), result.getSchemaName());
        assertEquals(request.getSql(), result.getSql());
        assertNull(result.getTableName());
        assertNull(result.getConsoleId());
        assertNull(result.getApplyId());
        assertNull(result.getPageNo());
        assertNull(result.getPageSize());
    }

    @Test
    void mapsDdlRequestWithOptionalTableHint() {
        DdlExecuteRequest request = new DdlExecuteRequest();
        request.setDataSourceId(1L);
        request.setDatabaseName("app");
        request.setSchemaName("public");
        request.setSql("alter table sms_attendance add column note varchar(255)");
        request.setTableName("sms_attendance");

        DbDlExecuteRequest result = converter.request2param(request);

        assertEquals(request.getDataSourceId(), result.getDataSourceId());
        assertEquals(request.getDatabaseName(), result.getDatabaseName());
        assertEquals(request.getSchemaName(), result.getSchemaName());
        assertEquals(request.getSql(), result.getSql());
        assertEquals(request.getTableName(), result.getTableName());
        assertNull(result.getConsoleId());
        assertNull(result.getApplyId());
        assertNull(result.getPageNo());
        assertNull(result.getPageSize());
    }
}
