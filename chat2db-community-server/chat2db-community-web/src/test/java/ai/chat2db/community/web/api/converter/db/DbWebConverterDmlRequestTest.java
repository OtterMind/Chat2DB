package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.DdlExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.TableBrowseRequest;
import ai.chat2db.community.web.api.model.request.db.TableEditExecuteRequest;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbWebConverterDmlRequestTest {

    private final DbWebConverter converter = Mappers.getMapper(DbWebConverter.class);

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
        assertNull(result.getTableName());
        assertNull(result.getPageSizeAll());
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
        request.setConsoleId(42L);
        request.setSql("alter table sms_attendance add column note varchar(255)");
        request.setTableName("sms_attendance");

        DbDlExecuteRequest result = converter.request2param(request);

        assertEquals(request.getDataSourceId(), result.getDataSourceId());
        assertEquals(request.getDatabaseName(), result.getDatabaseName());
        assertEquals(request.getSchemaName(), result.getSchemaName());
        assertEquals(request.getSql(), result.getSql());
        assertEquals(request.getTableName(), result.getTableName());
        assertEquals(request.getConsoleId(), result.getConsoleId());
        assertNull(result.getApplyId());
        assertNull(result.getPageNo());
        assertNull(result.getPageSize());
    }
}
