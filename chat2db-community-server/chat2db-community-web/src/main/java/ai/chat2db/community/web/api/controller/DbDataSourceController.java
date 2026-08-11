package ai.chat2db.community.web.api.controller;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceImportService;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect;
import ai.chat2db.community.web.api.converter.data.source.DataSourceWebConverter;
import ai.chat2db.community.web.api.converter.data.source.SSHWebConverter;
import ai.chat2db.community.web.api.model.request.data.source.*;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DatabaseResponse;
import ai.chat2db.community.web.api.model.response.data.source.ProgressResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * Manages datasource connection configuration and lifecycle endpoints.
 */
@ConnectionInfoAspect
@RequestMapping("/api/connection")
@RestController
public class DbDataSourceController {

    private final IDbDataSourceService dataSourceService;
    private final DataSourceWebConverter dataSourceWebConverter;
    private final SSHWebConverter sshWebConverter;
    private final IDbWorkspaceDataSourceService workspaceDataSourceService;
    private final IDbDataSourceImportService dataSourceImportService;

    public DbDataSourceController(IDbDataSourceService dataSourceService,
            DataSourceWebConverter dataSourceWebConverter,
            SSHWebConverter sshWebConverter,
            IDbWorkspaceDataSourceService workspaceDataSourceService,
            IDbDataSourceImportService dataSourceImportService) {
        this.dataSourceService = dataSourceService;
        this.dataSourceWebConverter = dataSourceWebConverter;
        this.sshWebConverter = sshWebConverter;
        this.workspaceDataSourceService = workspaceDataSourceService;
        this.dataSourceImportService = dataSourceImportService;
    }

    /**
     * Lists datasources.
     * <p>
     * Endpoint: {@code GET /api/connection/datasource/list}.
     *
     * @param request request payload or query parameters for the operation.
     * @return paged web result containing data source response.
     */
    @GetMapping("/datasource/list")
    public WebPageResult<DataSourceResponse> list(DataSourceQueryRequest request) {
        PageResponse<WorkspaceDataSource> pageResult =
                workspaceDataSourceService.listDataSources(dataSourceWebConverter.request2param(request));
        List<DataSourceResponse> dataSourceResponses =
                pageResult.getData().stream()
                        .map(dataSourceWebConverter::storage2response)
                        .toList();
        return WebPageResult.of(dataSourceResponses, pageResult.getTotal(), pageResult.getPageNo(),
                pageResult.getPageSize());
    }


    /**
     * Tests datasource connectivity before saving a connection.
     * <p>
     * Endpoint: {@code POST /api/connection/datasource/pre_connect}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @PostMapping("/datasource/pre_connect")
    public ActionResult preConnect(@RequestBody DataSourceTestRequest request) {
        workspaceDataSourceService.preConnect(dataSourceWebConverter.testRequest2param(request));
        return ActionResult.isSuccess();
    }

    /**
     * Tests SSH tunnel connectivity for a datasource.
     * <p>
     * Endpoint: {@code REQUEST /api/connection/ssh/pre_connect}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @RequestMapping("/ssh/pre_connect")
    public ActionResult sshConnect(@RequestBody SSHTestRequest request) {
        dataSourceService.testSshConnection(sshWebConverter.toInfo(request));
        return ActionResult.isSuccess();
    }

    /**
     * Creates datasources.
     * <p>
     * Endpoint: {@code POST /api/connection/datasource/create}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing data source response.
     */
    @PostMapping("/datasource/create")
    public DataResult<DataSourceResponse> create(@RequestBody DataSourceCreateRequest request) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.createDataSource(
                dataSourceWebConverter.response2storage(dataSourceWebConverter.request2response(request)));
        return DataResult.of(dataSourceWebConverter.storage2response(dataSource));
    }

    /**
     * Imports Chat2DB community datasource definitions.
     * <p>
     * Endpoint: {@code POST/GET /api/connection/datasource/import_community}.
     *
     * @return data result containing progress response.
     */
    @RequestMapping(value = "/datasource/import_community", method = {RequestMethod.POST, RequestMethod.GET})
    public DataResult<ProgressResponse> importChat2db() {
        dataSourceImportService.importCommunityDataSources();
        return DataResult.of(dataSourceWebConverter.importProgress());
    }

    /**
     * Exports datasource definitions.
     * <p>
     * Endpoint: {@code POST /api/connection/datasource/export}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing progress response.
     */
    @RequestMapping(value = "/datasource/export", method = {RequestMethod.POST})
    public DataResult<ProgressResponse> exportDataSource(@RequestBody DataSourceExportRequest request) {
        List<DataSourceResponse> dataSourceResponses = workspaceDataSourceService
                .exportDisplayDataSources(request.getDatasourceIds())
                .stream()
                .map(dataSourceWebConverter::storage2response)
                .toList();
        return DataResult.of(dataSourceWebConverter.exportProgress(dataSourceResponses));
    }


    /**
     * Attaches to a datasource and opens a working connection.
     * <p>
     * Endpoint: {@code GET /api/connection/datasource/connect}.
     *
     * @param request request payload or query parameters for the operation.
     * @return list result containing database response.
     */
    @GetMapping("/datasource/connect")
    public ListResult<DatabaseResponse> attach(@Valid @NotNull DataSourceAttachRequest request) {
        List<Database> databaseDTOListResult = dataSourceService.connect(request.getId());
        List<DatabaseResponse> databaseResponses = dataSourceWebConverter.databaseDto2response(databaseDTOListResult);
        return ListResult.of(databaseResponses);
    }

    /**
     * Closes the active datasource connection.
     * <p>
     * Endpoint: {@code GET /api/connection/datasource/close}.
     *
     * @param request request payload or query parameters for the operation.
     * @return operation result for the request.
     */
    @GetMapping("/datasource/close")
    public ActionResult close(@Valid @NotNull DataSourceCloseRequest request) {
        dataSourceService.close(request.getId());
        return ActionResult.isSuccess();
    }


    /**
     * Gets datasource details by identifier.
     * <p>
     * Endpoint: {@code GET /api/connection/datasource}.
     *
     * @param id identifier used to locate the target resource.
     * @return data result containing data source response.
     */
    @GetMapping("/datasource")
    public DataResult<DataSourceResponse> queryById(@RequestParam("id") Long id) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(id, false);
        return DataResult.of(
                dataSource == null ? null : dataSourceWebConverter.storage2response(dataSource));
    }

    /**
     * Closes datasource connections by request parameters.
     * <p>
     * Endpoint: {@code POST /api/connection/close}.
     *
     * @param request request containing the identifier of the connection to close.
     * @return operation result for the request.
     */
    @PostMapping("/close")
    public ActionResult closeConnection(@RequestBody @Valid DataSourceCloseRequest request) {
        dataSourceService.removeConnection(request.getId());
        return ActionResult.isSuccess();
    }

    /**
     * Deletes datasources.
     * <p>
     * Endpoint: {@code DELETE /api/connection/datasource}.
     *
     * @param id identifier used to locate the target resource.
     * @return operation result for the request.
     */
    @DeleteMapping("/datasource")
    public ActionResult delete(@RequestParam("id") Long id) {
        workspaceDataSourceService.deleteDataSource(id);
        return ActionResult.isSuccess();
    }

    /**
     * Updates datasources.
     * <p>
     * Endpoint: {@code POST/PUT /api/connection/datasource/update}.
     *
     * @param request request payload or query parameters for the operation.
     * @return data result containing data source response.
     */
    @RequestMapping(value = "/datasource/update", method = {RequestMethod.POST, RequestMethod.PUT})
    public DataResult<DataSourceResponse> update(@RequestBody DataSourceUpdateRequest request) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.updateDataSource(
                dataSourceWebConverter.response2storage(dataSourceWebConverter.request2response(request)));
        return DataResult.of(dataSourceWebConverter.storage2response(dataSource));
    }
}
