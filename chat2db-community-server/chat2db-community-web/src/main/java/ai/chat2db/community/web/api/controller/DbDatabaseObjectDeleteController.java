package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.db.DatabaseObjectDeletePrepare;
import ai.chat2db.community.domain.api.service.db.IDbDatabaseObjectDeleteService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.DatabaseDeletePrepareRequest;
import ai.chat2db.community.web.api.model.request.db.DatabaseObjectDeleteExecuteRequest;
import ai.chat2db.community.web.api.model.request.db.SchemaDeletePrepareRequest;
import ai.chat2db.community.web.api.model.request.db.TablespaceDeletePrepareRequest;
import ai.chat2db.community.web.api.model.response.db.DatabaseObjectDeletePrepareResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prepares and executes database object deletion operations.
 */
@ConnectionInfoAspect
@RequestMapping("/api/rdb/delete")
@RestController
public class DbDatabaseObjectDeleteController {

    @Autowired
    private IDbDatabaseObjectDeleteService databaseObjectDeleteService;

    @Autowired
    private DbWebConverter dbWebConverter;

    /**
     * Handles prepare database delete for database object deletion.
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/database/prepare}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing database object delete prepare response.
     */
    @PostMapping("/database/prepare")
    public DataResult<DatabaseObjectDeletePrepareResponse> prepareDatabaseDelete(
            @Valid @RequestBody DatabaseDeletePrepareRequest request) {
        DatabaseObjectDeletePrepare result = databaseObjectDeleteService.prepareDatabaseDelete(
                dbWebConverter.request2param(request));
        return DataResult.of(dbWebConverter.databaseObjectDeletePrepare2response(result));
    }

    /**
     * Executes database delete.
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/database/execute}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/database/execute")
    public ActionResult executeDatabaseDelete(@Valid @RequestBody DatabaseObjectDeleteExecuteRequest request) {
        databaseObjectDeleteService.executeDatabaseDelete(dbWebConverter.request2param(request));
        return ActionResult.isSuccess();
    }

    /**
     * Handles prepare schema delete for database object deletion.
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/schema/prepare}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing database object delete prepare response.
     */
    @PostMapping("/schema/prepare")
    public DataResult<DatabaseObjectDeletePrepareResponse> prepareSchemaDelete(
            @Valid @RequestBody SchemaDeletePrepareRequest request) {
        DatabaseObjectDeletePrepare result = databaseObjectDeleteService.prepareSchemaDelete(
                dbWebConverter.request2param(request));
        return DataResult.of(dbWebConverter.databaseObjectDeletePrepare2response(result));
    }

    /**
     * Executes schema delete.
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/schema/execute}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/schema/execute")
    public ActionResult executeSchemaDelete(@Valid @RequestBody DatabaseObjectDeleteExecuteRequest request) {
        databaseObjectDeleteService.executeSchemaDelete(dbWebConverter.request2param(request));
        return ActionResult.isSuccess();
    }

    /**
     * Prepares tablespace deletion, surfacing occupying tables so the UI can block a non-empty
     * tablespace and require name-typing for an empty one.
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/tablespace/prepare}.
     */
    @PostMapping("/tablespace/prepare")
    public DataResult<DatabaseObjectDeletePrepareResponse> prepareTablespaceDelete(
            @Valid @RequestBody TablespaceDeletePrepareRequest request) {
        DatabaseObjectDeletePrepare result = databaseObjectDeleteService.prepareTablespaceDelete(
                dbWebConverter.request2param(request));
        return DataResult.of(dbWebConverter.databaseObjectDeletePrepare2response(result));
    }

    /**
     * Executes a prepared tablespace deletion (empty tablespace only).
     * <p>
     * Endpoint: {@code POST /api/rdb/delete/tablespace/execute}.
     */
    @PostMapping("/tablespace/execute")
    public ActionResult executeTablespaceDelete(@Valid @RequestBody DatabaseObjectDeleteExecuteRequest request) {
        databaseObjectDeleteService.executeTablespaceDelete(dbWebConverter.request2param(request));
        return ActionResult.isSuccess();
    }

}
