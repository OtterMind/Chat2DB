package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.request.db.*;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.api.service.db.IDbMybatisGenerateService;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.*;
import ai.chat2db.community.web.api.model.response.db.ColumnResponse;
import ai.chat2db.community.web.api.model.response.db.IndexResponse;
import ai.chat2db.community.web.api.model.response.db.SqlResponse;
import ai.chat2db.community.web.api.model.response.db.TableResponse;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;

/**
 * Exposes relational table metadata, DDL, and table operation endpoints.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/table")
@RestController
public class DbTableController {

    @Autowired
    private IDbTableService tableService;

    @Autowired
    private IDbMybatisGenerateService mybatisGenerateService;

    @Autowired
    private DbWebConverter dbWebConverter;

    private final IIdentityService identityService;

    public DbTableController(IIdentityService identityService) {
        this.identityService = identityService;
    }

    /**
     * Lists tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing table response.
     */
    @GetMapping("/list")
    public WebPageResult<TableResponse> list(@Valid TableBriefQueryRequest request) {
        DbTablePageQueryRequest queryParam = dbWebConverter.tablePageRequest2param(request,
                identityService.currentUserId());
        PageResponse<Table> tableDTOPageResult = tableService.pageQueryWithPinned(queryParam,
                dbWebConverter.tableSelector(false, false));
        List<TableResponse> tableResponses = dbWebConverter.tableDto2response(tableDTOPageResult.getData());
        return WebPageResult.of(tableResponses, tableDTOPageResult.getTotal(), request.getPageNo(),
                request.getPageSize());
    }

    /**
     * Handles table list for tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/table_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing simple table.
     */
    @GetMapping("/table_list")
    public ListResult<SimpleTable> tableList(@Valid TableBriefQueryRequest request) {
        DbTablePageQueryRequest queryParam = dbWebConverter.tablePageRequest2param(request);
        return ListResult.of(tableService.queryTables(queryParam));

    }


    /**
     * Handles column list for tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/column_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing column response.
     */
    @GetMapping("/column_list")
    public ListResult<ColumnResponse> columnList(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        return ListResult.of(dbWebConverter.columnDto2response(tableService.queryColumns(queryParam)));
    }

    /**
     * Copies DML SQL.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/copy_dml_sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @GetMapping("/copy_dml_sql")
    public DataResult<String> copyDmlSql(@Valid DmlSqlCopyRequest request) {
        DbDmlSqlCopyRequest queryParam = dbWebConverter.dmlRequest2param(request);
        return DataResult.of(tableService.copyDmlSql(queryParam));
    }

    /**
     * Handles index list for tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/index_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing index response.
     */
    @GetMapping("/index_list")
    public ListResult<IndexResponse> indexList(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        List<TableIndex> tableIndices = tableService.queryIndexes(queryParam);
        List<IndexResponse> indexResponses = dbWebConverter.indexDto2response(tableIndices);


        return ListResult.of(indexResponses);
    }

    /**
     * Handles key list for tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/key_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing index response.
     */
    @GetMapping("/key_list")
    public ListResult<IndexResponse> keyList(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        return ListResult.of(dbWebConverter.indexDto2response(tableService.queryKeys(queryParam)));
    }

    /**
     * Exports tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/export}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @GetMapping("/export")
    public DataResult<String> export(@Valid DdlExportRequest request) {
        DbTableShowCreateRequest param = dbWebConverter.ddlExport2showCreate(request);
        return DataResult.of(tableService.showCreateTable(param));
    }

    /**
     * Creates example.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/create/example}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @GetMapping("/create/example")
    public DataResult<String> createExample(@Valid TableCreateDdlQueryRequest request) {
        return DataResult.of(tableService.createTableExample(request.getDbType()));
    }

    /**
     * Updates example.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/update/example}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing string.
     */
    @GetMapping("/update/example")
    public DataResult<String> updateExample(@Valid TableUpdateDdlQueryRequest request) {
        return DataResult.of(tableService.alterTableExample(request.getDbType()));
    }

    /**
     * Queries tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/query}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing table.
     */
    @GetMapping("/query")
    public DataResult<Table> query(@Valid TableDetailQueryRequest request) {
        DbTableQueryRequest queryParam = dbWebConverter.tableRequest2param(request);
        return DataResult.of(tableService.query(queryParam, dbWebConverter.tableSelector(true, true)));
    }

    /**
     * Handles modify SQL for tables.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/modify/sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing SQL response.
     */
    @PostMapping("/modify/sql")
    public ListResult<SqlResponse> modifySql(@Valid @RequestBody TableModifySqlRequest request) {
        Table table = dbWebConverter.normalizeModifyTable(request);
        return ListResult.of(tableService.buildSql(request.getOldTable(), table, TableBuilderConfig.defaultConfig())
                .stream()
                .map(dbWebConverter::dto2response)
                .toList());
    }


    /**
     * Lists tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/type_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing type.
     */
    @GetMapping("/type_list")
    public ListResult<Type> types(@Valid TypeQueryRequest request) {
        DbTypeQueryRequest typeQueryParam =
                DbTypeQueryRequest.builder()
                        .dataSourceId(request.getDataSourceId())
                        .build();
        List<Type> types = tableService.queryTypes(typeQueryParam);
        return ListResult.of(types);
    }


    /**
     * Handles table meta for tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/table/table_meta}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing table meta.
     */
    @GetMapping("/table_meta")
    public DataResult<TableMeta> tableMeta(@Valid TypeQueryRequest request) {
        DbTypeQueryRequest typeQueryParam =
                DbTypeQueryRequest.builder()
                        .dataSourceId(request.getDataSourceId())
                        .build();
        TableMeta tableMeta = tableService.queryTableMeta(typeQueryParam);
        return DataResult.of(tableMeta);
    }

    /**
     * Deletes tables.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/delete}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/delete")
    public ActionResult delete(@Valid @RequestBody TableDeleteRequest request) {
        DbTableQueryRequest param = dbWebConverter.tableRequest2param(request);
        tableService.dropTable(param);
        return ActionResult.isSuccess();
    }

    /**
     * Truncates tables.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/truncate}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/truncate")
    public ActionResult truncate(@RequestBody TableDetailQueryRequest request) {
        DbTableQueryRequest param = dbWebConverter.tableRequest2param(request);
        tableService.truncateTable(param);
        return ActionResult.isSuccess();
    }

    /**
     * Generates maintenance SQL for a table operation.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/maintenance/sql}.
     *
     * @param request request payload containing table info.
     * @param operationType type of maintenance: ANALYZE, OPTIMIZE, CHECK, or REPAIR.
     * @return data result containing the generated SQL.
     */
    @PostMapping("/maintenance/sql")
    public DataResult<String> maintenanceSql(@RequestBody TableDetailQueryRequest request,
                                             @RequestParam String operationType) {
        DbTableQueryRequest param = dbWebConverter.tableRequest2param(request);
        return DataResult.of(tableService.maintenanceSql(param, operationType));
    }

    /**
     * Copies tables.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/copy}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/copy")
    public ActionResult copy(@RequestBody TableCopyRequest request) {
        tableService.copyTable(dbWebConverter.request2param(request));
        return ActionResult.isSuccess();
    }

    /**
     * Generates class metadata from a table name.
     * <p>
     * Endpoint: {@code POST /api/rdb/table/generate/class}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     * @throws SQLException when the operation cannot be completed.
     */
    @PostMapping("/generate/class")
    public ActionResult tableNameToClassName(@Valid @RequestBody TableGenerateClassRequest request) throws SQLException {
        mybatisGenerateService.generateClass(request.getTableName(), request.getSchemaName(), request.getExportPath());
        return ActionResult.isSuccess();
    }
}
