package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.db.DbViewDeleteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbViewMetaModifyRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.db.IDbViewService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.ListResultWithTotal;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.*;
import ai.chat2db.community.web.api.model.response.db.ColumnResponse;
import ai.chat2db.community.web.api.model.response.db.TableResponse;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.community.domain.api.model.view.ModifyViewConfiguration;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Exposes relational view metadata and SQL generation endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/view")
@RestController
public class DbViewController {
    @Autowired
    private IDbViewService viewService;

    @Autowired
    private IDbTableService tableService;

    @Autowired
    private DbWebConverter dbWebConverter;

    /**
     * Lists database views.
     * <p>
     * Endpoint: {@code GET /api/rdb/view/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing table response.
     */
    @GetMapping("/list")
    public WebPageResult<TableResponse> list(@Valid TableBriefQueryRequest request) {
        List<Table> tableDTOPageResult = viewService.views(request.getDatabaseName(), request.getSchemaName());
        List<TableResponse> tableResponses = dbWebConverter.tableDto2response(tableDTOPageResult);
        if (tableResponses == null) {
            tableResponses = List.of();
        }
        int size = tableResponses.size();
        return WebPageResult.of(tableResponses, Long.valueOf(size), 1, size);
    }


    /**
     * Handles column list for database views.
     * <p>
     * Endpoint: {@code GET /api/rdb/view/column_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing column responses and their total count.
     */
    @GetMapping("/column_list")
    public ListResultWithTotal<ColumnResponse> columnList(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        List<TableColumn> tableColumns = tableService.queryColumns(queryParam);
        List<ColumnResponse> tableResponses = dbWebConverter.columnDto2response(tableColumns);
        return ListResultWithTotal.from(tableResponses);
    }


    /**
     * Gets database views.
     * <p>
     * Endpoint: {@code GET /api/rdb/view/detail}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing table response.
     */
    @GetMapping("/detail")
    public DataResult<TableResponse> detail(@Valid TableDetailQueryRequest request) {
        Table dataResult = viewService.detail(request.getDatabaseName(), request.getSchemaName(), request.getTableName());
        TableResponse tableResponse = dbWebConverter.tableDto2response(dataResult);
        return DataResult.of(tableResponse);
    }

    /**
     * Queries database views.
     * <p>
     * Endpoint: {@code GET /api/rdb/view/query}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing table.
     */
    @GetMapping("/query")
    public DataResult<Table> query(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        return DataResult.of(viewService.query(queryParam));
    }


    /**
     * Deletes database views.
     * <p>
     * Endpoint: {@code POST /api/rdb/view/delete}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/delete")
    public ActionResult delete(@RequestBody @Valid TableDeleteRequest request) {
        // The /delete endpoint deletes a VIEW, not a table. Build a DbViewDeleteRequest
        // (mapping tableName -> viewName, since a view's object name is carried in tableName
        // on this request type) and delegate to viewService.drop, mirroring the /drop endpoint.
        DbViewDeleteRequest param = new DbViewDeleteRequest(
                request.getDataSourceId(),
                request.getDatabaseName(),
                request.getSchemaName(),
                request.getTableName());
        viewService.drop(param);
        return ActionResult.isSuccess();
    }

    /**
     * Handles view meta for database views.
     * <p>
     * Endpoint: {@code GET /api/rdb/view/view_meta}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing modify view configuration.
     */
    @GetMapping("/view_meta")
    public DataResult<ModifyViewConfiguration> viewMeta(@Valid ModifyViewMetaRequest request) {
        DbViewMetaModifyRequest param = dbWebConverter.request2param(request);
        ModifyViewConfiguration configuration = viewService.meta(param);
        return DataResult.of(configuration);
    }


    /**
     * Handles modify SQL for database views.
     * <p>
     * Endpoint: {@code POST /api/rdb/view/modify/sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @PostMapping("/modify/sql")
    public DataResult<String> modifySql(@RequestBody ModifyViewRequest request) {
        ModifyView modifyView = dbWebConverter.request2Param(request);
        String createViewSql = viewService.modifySql(modifyView);
        return DataResult.of(createViewSql);
    }

    /**
     * Deletes database views.
     * <p>
     * Endpoint: {@code POST /api/rdb/view/drop}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/drop")
    public ActionResult delete(@RequestBody DeleteViewRequest request) {
        DbViewDeleteRequest param = dbWebConverter.request2param(request);
        viewService.drop(param);
        return ActionResult.isSuccess();
    }
}
