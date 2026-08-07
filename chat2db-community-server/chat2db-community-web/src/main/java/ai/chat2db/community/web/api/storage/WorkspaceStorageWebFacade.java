package ai.chat2db.community.web.api.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.er.ERPosition;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.pin.PinTable;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePositionUpdateRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.tools.wrapper.result.ActionResult;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import ai.chat2db.community.tools.wrapper.result.ListResult;
import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceQueryRequest;
import ai.chat2db.community.web.api.model.request.data.source.PositionUpdateRequest;
import ai.chat2db.community.web.api.model.request.data.source.UpdateDatasourcePositionRequest;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceNamespaceResponse;
import ai.chat2db.community.web.api.model.response.data.source.DataSourceResponse;
import ai.chat2db.community.web.api.converter.data.source.DataSourceWebConverter;
import ai.chat2db.community.web.api.converter.operation.log.OperationLogConverter;
import ai.chat2db.community.web.api.model.request.er.ERModelQueryRequest;
import ai.chat2db.community.web.api.model.request.operation.log.OperationLogQueryRequest;
import ai.chat2db.community.web.api.model.request.operation.saved.OperationQueryRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Lazy(false)
@Component
public final class WorkspaceStorageWebFacade {

    private static volatile WorkspaceStorageWebFacade delegate;

    private final IWorkspaceStorageFacade workspaceStorageFacade;
    private final DataSourceWebConverter dataSourceWebConverter;
    private final OperationLogConverter operationLogConverter;

    public WorkspaceStorageWebFacade(IWorkspaceStorageFacade workspaceStorageFacade,
            DataSourceWebConverter dataSourceWebConverter,
            OperationLogConverter operationLogConverter) {
        this.workspaceStorageFacade = workspaceStorageFacade;
        this.dataSourceWebConverter = dataSourceWebConverter;
        this.operationLogConverter = operationLogConverter;
        delegate = this;
    }

    public static DataResult<DataSourceResponse> queryById(Long id, Boolean requestPassword) {
        WorkspaceDataSource dataSource = delegate().workspaceStorageFacade.queryDataSourceById(id, requestPassword);
        return DataResult.of(dataSource == null ? null : delegate().dataSourceWebConverter.storage2response(dataSource));
    }

    public static DataResult<Long> createDatasource(DataSourceResponse dataSourceResponse) {
        return DataResult.of(delegate().workspaceStorageFacade.createDataSource(
                delegate().dataSourceWebConverter.response2storage(dataSourceResponse)));
    }

    public static DataResult<Long> createLocalDatasource(DataSourceResponse dataSourceResponse) {
        return createDatasource(dataSourceResponse);
    }

    public static void deleteDatasource(Long id) {
        delegate().workspaceStorageFacade.deleteDataSource(id);
    }

    public static DataResult<Long> updateDatasource(DataSourceResponse dataSourceResponse) {
        return DataResult.of(delegate().workspaceStorageFacade.updateDataSource(
                delegate().dataSourceWebConverter.response2storage(dataSourceResponse)));
    }

    public static WebPageResult<DataSourceResponse> getDataSourceList(DataSourceQueryRequest request) {
        return mapPageResponse(delegate().workspaceStorageFacade.listDataSources(
                        delegate().dataSourceWebConverter.request2param(request)),
                delegate().dataSourceWebConverter::storage2response);
    }

    public static DataResult<DataSourceNamespaceResponse> getNamespaceDatasource() {
        WorkspaceDataSourceNamespace result = delegate().workspaceStorageFacade.getNamespaceDataSources();
        return DataResult.of(result == null ? null : delegate().dataSourceWebConverter.storage2response(result));
    }

    public static DataResult<Long> createNamespace(Namespace namespace) {
        return DataResult.of(delegate().workspaceStorageFacade.createNamespace(namespace));
    }

    public static ActionResult updateNamespace(Namespace namespace) {
        delegate().workspaceStorageFacade.updateNamespace(namespace);
        return ActionResult.isSuccess();
    }

    public static ActionResult deleteNamespace(Long id) {
        delegate().workspaceStorageFacade.deleteNamespace(id);
        return ActionResult.isSuccess();
    }

    public static ActionResult updateDataSourcePosition(UpdateDatasourcePositionRequest request) {
        DbDataSourcePositionUpdateRequest updateDataSourcePositionRequest = new DbDataSourcePositionUpdateRequest();
        updateDataSourcePositionRequest.setNamespaceId(request.getNamespaceId());
        updateDataSourcePositionRequest.setDataSourceId(request.getDataSourceId());
        updateDataSourcePositionRequest.setBeforePosition(request.getBeforePosition());
        updateDataSourcePositionRequest.setAfterPosition(request.getAfterPosition());
        delegate().workspaceStorageFacade.updateDataSourcePosition(updateDataSourcePositionRequest);
        return ActionResult.isSuccess();
    }

    public static ListResult<Node> getTree() {
        return ListResult.of(delegate().workspaceStorageFacade.getTree());
    }

    public static ActionResult updatePosition(PositionUpdateRequest request) {
        delegate().workspaceStorageFacade.updatePosition(request.getDropToNode(), request.getDragNode(), request.getDropPosition());
        return ActionResult.isSuccess();
    }

    public static ListResult<String> queryPinTables(DbTablePinRequest param) {
        return ListResult.of(delegate().workspaceStorageFacade.queryPinTables(param));
    }

    public static ActionResult pinTable(PinTable request) {
        delegate().workspaceStorageFacade.pinTable(request);
        return ActionResult.isSuccess();
    }

    public static ActionResult deletePinTable(PinTable request) {
        delegate().workspaceStorageFacade.deletePinTable(request);
        return ActionResult.isSuccess();
    }

    public static DataResult<Long> createOperationLog(OperationLog request) {
        return DataResult.of(delegate().workspaceStorageFacade.createOperationLog(request));
    }

    public static WebPageResult<OperationLog> operationLogList(OperationLogQueryRequest request) {
        return toWebPageResult(delegate().workspaceStorageFacade.operationLogList(delegate().operationLogConverter.request2param(request)));
    }

    public static DataResult<OperationLog> getOperationLog(Long id) {
        return DataResult.of(delegate().workspaceStorageFacade.getOperationLog(id));
    }

    public static WebPageResult<Operation> consoleList(OperationQueryRequest request) {
        return toWebPageResult(delegate().workspaceStorageFacade.consoleList(delegate().operationLogConverter.request2param(request)));
    }

    public static DataResult<Operation> getConsole(Long id) {
        return DataResult.of(delegate().workspaceStorageFacade.getConsole(id));
    }

    public static ActionResult deleteConsole(Long id) {
        delegate().workspaceStorageFacade.deleteConsole(id);
        return ActionResult.isSuccess();
    }

    public static DataResult<Long> createConsole(Operation request) {
        return DataResult.of(delegate().workspaceStorageFacade.createConsole(request));
    }

    public static ActionResult updateConsole(Operation request) {
        delegate().workspaceStorageFacade.updateConsole(request);
        return ActionResult.isSuccess();
    }

    public static DataResult<String> getErPosition(ERModelQueryRequest request) {
        return DataResult.of(delegate().workspaceStorageFacade.getErPosition(request.getDataSourceId(),
                request.getDatabaseName(), request.getSchemaName()));
    }

    public static ActionResult savePosition(ERPosition request) {
        delegate().workspaceStorageFacade.savePosition(request);
        return ActionResult.isSuccess();
    }

    private static WorkspaceStorageWebFacade delegate() {
        WorkspaceStorageWebFacade current = delegate;
        if (current == null) {
            throw new IllegalStateException("WorkspaceStorageWebFacade is not initialized");
        }
        return current;
    }

    private static <T, R> WebPageResult<R> mapPageResponse(PageResponse<T> pageResponse, Function<T, R> mapper) {
        if (pageResponse == null) {
            return WebPageResult.empty((Integer) null, (Integer) null);
        }
        List<T> sourceData = pageResponse.getData();
        List<R> mapped = sourceData == null ? null : sourceData.stream().map(mapper).collect(Collectors.toList());
        return WebPageResult.of(mapped, pageResponse.getTotal(), pageResponse.getPageNo(), pageResponse.getPageSize());
    }

    private static <T> WebPageResult<T> toWebPageResult(PageResponse<T> pageResponse) {
        if (pageResponse == null) {
            return WebPageResult.empty((Integer) null, (Integer) null);
        }
        return WebPageResult.of(pageResponse.getData(), pageResponse.getTotal(), pageResponse.getPageNo(),
                pageResponse.getPageSize());
    }
}
