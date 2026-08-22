package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.db.TablespaceCapability;
import ai.chat2db.community.domain.api.model.metadata.Tablespace;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceCreateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceModifyRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbTablespaceQueryRequest;
import ai.chat2db.community.domain.api.model.sql.Sql;
import ai.chat2db.community.domain.api.service.db.IDbTablespaceService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.db.DbWebConverter;
import ai.chat2db.community.web.api.model.request.db.TablespaceCreateRequest;
import ai.chat2db.community.web.api.model.request.db.TablespaceModifyRequest;
import ai.chat2db.community.web.api.model.request.db.TablespaceQueryRequest;
import ai.chat2db.community.web.api.model.response.db.TablespaceCapabilityResponse;
import ai.chat2db.community.web.api.model.response.db.TablespaceResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes InnoDB General Tablespace discovery and management endpoints.
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/rdb/tablespace")
@RestController
public class DbTablespaceController {

    @Autowired
    private IDbTablespaceService tablespaceService;

    @Autowired
    private DbWebConverter dbWebConverter;

    /**
     * Lists InnoDB General Tablespaces.
     * <p>
     * Endpoint: {@code GET /api/rdb/tablespace/list}.
     */
    @GetMapping("/list")
    public ListResult<TablespaceResponse> tablespaceList(@Valid TablespaceQueryRequest request) {
        DbTablespaceQueryRequest queryParam = dbWebConverter.request2param(request);
        List<Tablespace> result = tablespaceService.queryAll(queryParam);
        return ListResult.of(dbWebConverter.tablespaceDto2response(result));
    }

    /**
     * Loads a single tablespace with its occupying tables.
     * <p>
     * Endpoint: {@code GET /api/rdb/tablespace/detail}.
     */
    @GetMapping("/detail")
    public DataResult<TablespaceResponse> tablespaceDetail(@Valid TablespaceQueryRequest request) {
        DbTablespaceQueryRequest queryParam = dbWebConverter.request2param(request);
        Tablespace result = tablespaceService.query(queryParam);
        return DataResult.of(dbWebConverter.tablespaceDto2response(result));
    }

    /**
     * Builds (preview) the SQL for creating a tablespace.
     * <p>
     * Endpoint: {@code POST /api/rdb/tablespace/create_sql}.
     */
    @PostMapping("/create_sql")
    public DataResult<Sql> createTablespace(@Valid @RequestBody TablespaceCreateRequest request) {
        DbTablespaceCreateRequest param = dbWebConverter.request2param(request);
        return DataResult.of(tablespaceService.createTablespace(param));
    }

    /**
     * Renames a tablespace (MySQL 8.0+; the service version-gates this).
     * <p>
     * Endpoint: {@code POST /api/rdb/tablespace/modify}.
     */
    @PostMapping("/modify")
    public ActionResult modifyTablespace(@Valid @RequestBody TablespaceModifyRequest request) {
        DbTablespaceModifyRequest param = dbWebConverter.request2param(request);
        tablespaceService.modifyTablespace(param);
        return ActionResult.isSuccess();
    }

    /**
     * Reports whether the current server supports {@code ALTER TABLESPACE ... RENAME TO}.
     * <p>
     * Endpoint: {@code GET /api/rdb/tablespace/capability}.
     */
    @GetMapping("/capability")
    public DataResult<TablespaceCapabilityResponse> capability(@Valid TablespaceQueryRequest request) {
        TablespaceCapability capability = tablespaceService.capability(dbWebConverter.request2param(request));
        return DataResult.of(dbWebConverter.tablespaceCapability2response(capability));
    }
}
