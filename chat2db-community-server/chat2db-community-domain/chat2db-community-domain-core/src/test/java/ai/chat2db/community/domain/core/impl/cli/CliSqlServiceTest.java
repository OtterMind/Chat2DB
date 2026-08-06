package ai.chat2db.community.domain.core.impl.cli;

import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.cli.CliSqlQueryResponse;
import ai.chat2db.community.domain.api.model.request.cli.CliSqlQueryRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlCountRequest;
import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlValidateRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.ExecutionMetrics;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.service.cli.ICliSqlService;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.core.converter.CliSqlDomainConverter;
import ai.chat2db.community.tools.exception.cli.CliDomainException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliSqlServiceTest {

    @Test
    void queryPassesNonSelectSqlToExecutionService() {
        CapturingDlTemplateService dlTemplateService = new CapturingDlTemplateService();
        ICliSqlService cliSqlService = new CliSqlServiceImpl(dlTemplateService, new CliSqlDomainConverter());

        CliSqlQueryRequest request = new CliSqlQueryRequest();
        request.setDataSourceId(195652L);
        request.setDatabaseName("postgres");
        request.setSchemaName("public");
        request.setSql("update users set name = 'new-name' where id = 1");
        request.setPageNo(2);
        request.setPageSize(50);
        request.setResultSetId(2);
        request.setIncludeRowNumber(Boolean.FALSE);
        ExecuteResponse executeResult = new ExecuteResponse();
        executeResult.setSuccess(Boolean.TRUE);
        executeResult.setResultSetId(2);
        executeResult.setHeaderList(Collections.emptyList());
        executeResult.setDataList(Collections.emptyList());
        dlTemplateService.result = executeResult;

        cliSqlService.query(request);

        DbDlExecuteRequest param = dlTemplateService.capturedParam;
        assertNotNull(param);
        assertEquals(request.getSql(), param.getSql());
        assertEquals(195652L, param.getDataSourceId());
        assertEquals("postgres", param.getDatabaseName());
        assertEquals("public", param.getSchemaName());
        assertEquals(2, param.getPageNo());
        assertEquals(50, param.getPageSize());
        assertEquals(2, param.getResultSetId());
        assertTrue(param.isSingle());
    }

    @Test
    void queryMapsUpdateCountForDml() {
        CapturingDlTemplateService dlTemplateService = new CapturingDlTemplateService();
        ExecuteResponse executeResult = new ExecuteResponse();
        executeResult.setSuccess(Boolean.TRUE);
        executeResult.setUpdateCount(3);
        executeResult.setExecutionMetrics(ExecutionMetrics.builder().totalDurationMs(7L).build());
        executeResult.setHeaderList(Collections.emptyList());
        executeResult.setDataList(Collections.emptyList());
        dlTemplateService.result = executeResult;
        ICliSqlService cliSqlService = new CliSqlServiceImpl(dlTemplateService, new CliSqlDomainConverter());

        CliSqlQueryResponse vo = cliSqlService.query(baseRequest());

        assertEquals(3, vo.getUpdateCount());
        assertEquals(3, vo.getRowCount());
        assertEquals(7L, vo.getDuration());
    }

    @Test
    void queryCanStripSyntheticRowNumberColumn() {
        CapturingDlTemplateService dlTemplateService = new CapturingDlTemplateService();
        ExecuteResponse executeResult = new ExecuteResponse();
        executeResult.setSuccess(Boolean.TRUE);
        executeResult.setHeaderList(List.of(
                Header.builder().name("行号").dataType(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode()).build(),
                Header.builder().name("id").dataType("BIGINT").build()
        ));
        executeResult.setDataList(List.of(List.of(ResultCell.of("1"), ResultCell.of("42"))));
        dlTemplateService.result = executeResult;
        ICliSqlService cliSqlService = new CliSqlServiceImpl(dlTemplateService, new CliSqlDomainConverter());
        CliSqlQueryRequest request = baseRequest();
        request.setIncludeRowNumber(Boolean.FALSE);

        CliSqlQueryResponse vo = cliSqlService.query(request);

        assertEquals(1, vo.getColumns().size());
        assertEquals("id", vo.getColumns().get(0).getName());
        assertEquals(List.of(List.of("42")), vo.getRows());
        assertEquals(1, vo.getRowCount());
    }

    @Test
    void querySelectsRequestedResultSet() {
        CapturingDlTemplateService dlTemplateService = new CapturingDlTemplateService();
        ExecuteResponse first = new ExecuteResponse();
        first.setSuccess(Boolean.TRUE);
        first.setResultSetId(1);
        first.setHeaderList(List.of(Header.builder().name("first").dataType("VARCHAR").build()));
        first.setDataList(List.of(List.of(ResultCell.of("a"))));
        ExecuteResponse second = new ExecuteResponse();
        second.setSuccess(Boolean.TRUE);
        second.setResultSetId(2);
        second.setHeaderList(List.of(Header.builder().name("second").dataType("VARCHAR").build()));
        second.setDataList(List.of(List.of(ResultCell.of("b"))));
        dlTemplateService.results = List.of(first, second);
        ICliSqlService cliSqlService = new CliSqlServiceImpl(dlTemplateService, new CliSqlDomainConverter());
        CliSqlQueryRequest request = baseRequest();
        request.setResultSetId(2);

        CliSqlQueryResponse vo = cliSqlService.query(request);

        assertEquals("second", vo.getColumns().get(0).getName());
        assertEquals(List.of(List.of("b")), vo.getRows());
        assertEquals("2", vo.getResultSetId());
    }

    @Test
    void failedExecuteResponseDetailsDoNotExposeFullSql() {
        CapturingDlTemplateService dlTemplateService = new CapturingDlTemplateService();
        String sql = "select '" + "x".repeat(240) + "' as token";
        ExecuteResponse executeResult = new ExecuteResponse();
        executeResult.setSuccess(Boolean.FALSE);
        executeResult.setMessage("SQL syntax error");
        executeResult.setSql(sql);
        executeResult.setDescription("syntax error near token");
        executeResult.setSqlType("SELECT");
        executeResult.setResultSetId(7);
        dlTemplateService.result = executeResult;
        ICliSqlService cliSqlService = new CliSqlServiceImpl(dlTemplateService, new CliSqlDomainConverter());

        CliDomainException exception = assertThrows(CliDomainException.class,
                () -> cliSqlService.query(baseRequest()));

        Map<String, Object> details = exception.getDetails();
        assertFalse(details.containsKey("sql"));
        assertEquals(sql.length(), details.get("sqlLength"));
        assertEquals("syntax error near token", details.get("description"));
        assertEquals("SELECT", details.get("sqlType"));
        assertEquals(7, details.get("resultSetId"));
        assertTrue(((String) details.get("sqlPreview")).endsWith("..."));
        assertTrue(((String) details.get("sqlPreview")).length() <= 163);
    }

    private static CliSqlQueryRequest baseRequest() {
        CliSqlQueryRequest request = new CliSqlQueryRequest();
        request.setDataSourceId(195652L);
        request.setDatabaseName("postgres");
        request.setSchemaName("public");
        request.setSql("select 1");
        return request;
    }

    private static class CapturingDlTemplateService implements IDbDlTemplateService {

        private DbDlExecuteRequest capturedParam;
        private ExecuteResponse result;
        private List<ExecuteResponse> results;

        @Override
        public List<ExecuteResponse> execute(DbDlExecuteRequest param) {
            this.capturedParam = param;
            if (results != null) {
                return results;
            }
            return result == null ? List.of() : List.of(result);
        }

        @Override
        public ExecuteResponse executeDdl(DbDlExecuteRequest param) {
            return result;
        }

        @Override
        public ExecuteResponse executeUpdate(DbDlExecuteRequest param) {
            return result;
        }

        @Override
        public List<ExecuteResponse> executeSelectTable(DbDlExecuteRequest param) {
            return results == null ? List.of() : results;
        }

        @Override
        public Long count(DbDlCountRequest param) {
            return 0L;
        }

        @Override
        public String updateSelectResult(DbSelectResultUpdateRequest param) {
            return "";
        }

        @Override
        public String copySelectResult(DbSelectResultUpdateRequest param) {
            return "";
        }

        @Override
        public String copyInValues(DbCopyInValuesRequest param) {
            return "";
        }

        @Override
        public ExecuteResponse validate(DbSqlValidateRequest param) {
            return result;
        }
    }
}
