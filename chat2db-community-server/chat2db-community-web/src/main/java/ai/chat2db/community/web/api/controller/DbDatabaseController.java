package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.model.request.db.DbMetaDataQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.response.data.source.DatabaseResponse;
import ai.chat2db.community.web.api.converter.db.DatabaseConverter;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.DatabaseCreateRequest;
import ai.chat2db.community.web.api.model.request.db.UpdateDatabaseRequest;
import ai.chat2db.community.web.api.model.response.db.MetaSchemaResponse;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.MetaSchema;
import ai.chat2db.community.domain.api.model.sql.Sql;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

import lombok.Data;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes relational database metadata and operation endpoints.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/database")
@RestController
public class DbDatabaseController {
    @Autowired
    private DbWebConverter dbWebConverter;

    @Autowired
    private IDbDatabaseService databaseService;

    /**
     * Handles database schema list for databases.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing meta schema response.
     */
    @Autowired
    public DatabaseConverter databaseConverter;

    /**
     * Handles database schema list for databases.
     * <p>
     * Endpoint: {@code GET /api/rdb/database/database_schema_list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing meta schema response.
     */
    @GetMapping("/database_schema_list")
    public DataResult<MetaSchemaResponse> databaseSchemaList(@Valid DataSourceBaseRequest request) {
        DbMetaDataQueryRequest queryParam = DbMetaDataQueryRequest.builder().dataSourceId(request.getDataSourceId())
            .refresh(
            request.isRefresh()).build();
        MetaSchema result = databaseService.queryDatabaseSchema(queryParam);
        MetaSchemaResponse schemaDto2response = dbWebConverter.metaSchemaDto2response(result);
        return DataResult.of(schemaDto2response);
    }


    /**
     * Handles database list for databases.
     * <p>
     * Endpoint: {@code GET /api/rdb/database/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing database response.
     */
    @GetMapping("/list")
    public ListResult<DatabaseResponse> databaseList(@Valid DataSourceBaseRequest request) {
        DbDatabaseQueryAllRequest queryParam = DbDatabaseQueryAllRequest.builder().dataSourceId(request.getDataSourceId())
            .refresh(
                request.isRefresh()).build();
        List<Database> result = databaseService.queryAll(queryParam);
        return ListResult.of(dbWebConverter.databaseDto2response(result));
    }

    /**
     * Deletes database.
     * <p>
     * Endpoint: {@code POST /api/rdb/database/delete_database}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/delete_database")
    public ActionResult deleteDatabase(@Valid @RequestBody DataSourceBaseRequest request) {
        throw new BusinessException("database.delete.twoPhaseRequired");
    }

    /**
     * Creates database.
     * <p>
     * Endpoint: {@code POST /api/rdb/database/create_database_sql}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing SQL.
     */
    @PostMapping("/create_database_sql")
    public DataResult<Sql> createDatabase(@Valid @RequestBody DatabaseCreateRequest request) {
        Database database = databaseConverter.createRequest2param(request);
        return DataResult.of(databaseService.createDatabase(database));
    }

    /**
     * Returns the database default character set and collation.
     * <p>
     * Endpoint: {@code GET /api/rdb/database/info?dataSourceId=1&databaseName=xxx}.
     */
    @GetMapping("/info")
    public DataResult<Map<String, String>> info(@Valid DataSourceBaseRequest request) {
        return DataResult.of(databaseService.databaseInfo(request.getDataSourceId(), request.getDatabaseName()));
    }

    /**
     * Generates an ALTER DATABASE statement preview for a character set / collation change.
     * <p>
     * Endpoint: {@code POST /api/rdb/database/alter_preview}.
     */
    @PostMapping("/alter_preview")
    public DataResult<String> alterPreview(@RequestBody @Valid AlterDatabasePreviewRequest request) {
        return DataResult.of(databaseService.previewAlterDatabaseSql(
                request.getDataSourceId(), request.getDatabaseName(), request.getCharset(), request.getCollation()));
    }

    @Data
    public static class AlterDatabasePreviewRequest extends DataSourceBaseRequest {
        private String charset;

        private String collation;
    }


    /**
     * Handles modify database for databases.
     * <p>
     * Endpoint: {@code POST /api/rdb/database/modify_database}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/modify_database")
    public ActionResult modifyDatabase(@Valid @RequestBody UpdateDatabaseRequest request) {
        DbDatabaseCreateRequest param =
                DbDatabaseCreateRequest.builder().name(request.getDatabaseName())
            .name(request.getNewDatabaseName()).build();
        databaseService.modifyDatabase(param);
        return ActionResult.isSuccess();
    }
}
