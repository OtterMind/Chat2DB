package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.domain.api.service.db.IDbDlTemplateService;
import ai.chat2db.community.domain.api.service.db.IDbDmlExecutionService;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.DdlCountRequest;
import ai.chat2db.community.web.api.model.request.db.DdlExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.CopyInValuesRequest;
import ai.chat2db.community.web.api.model.request.db.SelectResultUpdateRequest;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.TableBrowseRequest;
import ai.chat2db.community.web.api.model.request.db.TableEditExecuteRequest;
import ai.chat2db.community.web.api.model.response.db.ExecuteResultResponse;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executes relational DML, SQL preview, and table-data operations.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/dml")
@RestController
public class DbDmlController {

    @Autowired
    private DbWebConverter dbWebConverter;

    @Autowired
    private IDbDlTemplateService dlTemplateService;

    @Autowired
    private IDbDmlExecutionService dmlExecutionService;

    /**
     * Executes SQL against a relational datasource.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/execute}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing execute result response.
     */
    @RequestMapping(value = "/execute", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultResponse> manage(@Valid @RequestBody SqlEditorExecuteRequest request) {
        return ListResult.of(dbWebConverter.dto2response(
                dmlExecutionService.execute(dbWebConverter.sqlEditorExecutionRequest(request))));
    }


    /**
     * Executes a table-data query for a relational datasource.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/execute_table}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing execute result response.
     */
    @RequestMapping(value = "/execute_table", method = {RequestMethod.POST, RequestMethod.PUT})
    public ListResult<ExecuteResultResponse> executeTable(@Valid @RequestBody TableBrowseRequest request) {
        return ListResult.of(dbWebConverter.dto2response(
                dmlExecutionService.executeTable(dbWebConverter.tableBrowseExecutionRequest(request))));
    }

    /**
     * Executes updates produced from editable select results.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/execute_update}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing execute result response.
     */
    @RequestMapping(value = "/execute_update", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<ExecuteResultResponse> executeSelectResultUpdate(
            @Valid @RequestBody TableEditExecuteRequest request) {
        ExecuteResponse result = dmlExecutionService.executeUpdate(dbWebConverter.tableEditExecutionRequest(request));
        return DataResult.of(dbWebConverter.dto2response(result));
    }

    /**
     * Builds SQL for updating editable select results.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/get_update_sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @RequestMapping(value = "/get_update_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getUpdateSelectResultSql(@Valid @RequestBody SelectResultUpdateRequest request) {
        DbSelectResultUpdateRequest param = dbWebConverter.request2param(request);
        dmlExecutionService.rejectPartialLargeValueOperations(param);
        return DataResult.of(dlTemplateService.updateSelectResult(param));
    }

    /**
     * Builds copy SQL for selected result rows.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/copy_update_sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @RequestMapping(value = "/copy_update_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getCopySelectResultSql(@Valid @RequestBody SelectResultUpdateRequest request) {
        DbSelectResultUpdateRequest param = dbWebConverter.request2param(request);
        dmlExecutionService.rejectPartialLargeValueOperations(param);
        return DataResult.of(dlTemplateService.copySelectResult(param));
    }

    /**
     * Builds SQL IN values for selected result rows.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/copy_in_values_sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @RequestMapping(value = "/copy_in_values_sql", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<String> getCopyInValuesSql(@Valid @RequestBody CopyInValuesRequest request) {
        DbCopyInValuesRequest param = dbWebConverter.request2param(request);
        return DataResult.of(dlTemplateService.copyInValues(param));
    }

    /**
     * Executes DDL SQL against a relational datasource.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/execute_ddl}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing execute result response.
     */
    @RequestMapping(value = "/execute_ddl", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<ExecuteResultResponse> executeDDL(@Valid @RequestBody DdlExecuteRequest request) {
        ExecuteResponse result = dmlExecutionService.executeDdl(dbWebConverter.ddlExecutionRequest(request));
        return DataResult.of(dbWebConverter.dto2response(result));
    }

    /**
     * Counts rows for a relational SQL query.
     * <p>
     * Endpoint: {@code POST/PUT /api/rdb/dml/count}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing long.
     */
    @RequestMapping(value = "/count", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<Long> count(@Valid @RequestBody DdlCountRequest request) {
        return DataResult.of(dlTemplateService.count(dbWebConverter.request2param(request)));
    }

}
