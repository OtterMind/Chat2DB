package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.er.ERPosition;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.pin.PinTable;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePositionUpdateRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationPageQueryRequest;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorage;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageProviderResolver;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

@Lazy(false)
@Component
public class DefaultWorkspaceStorageFacade implements IWorkspaceStorageFacade {

    private final IWorkspaceStorageProviderResolver resolver;

    public DefaultWorkspaceStorageFacade(IWorkspaceStorageProviderResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
        return storage().queryDataSourceById(id, requestPassword);
    }

    @Override
    public Long createDataSource(WorkspaceDataSource dataSource) {
        return storage().createDataSource(dataSource);
    }

    @Override
    public void deleteDataSource(Long id) {
        storage().deleteDataSource(id);
    }

    @Override
    public Long updateDataSource(WorkspaceDataSource dataSource) {
        return storage().updateDataSource(dataSource);
    }

    @Override
    public PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest dataSourcePageQueryRequest) {
        return storage().listDataSources(dataSourcePageQueryRequest);
    }

    @Override
    public WorkspaceDataSourceNamespace getNamespaceDataSources() {
        return storage().getNamespaceDataSources();
    }

    @Override
    public Long createNamespace(Namespace namespace) {
        return storage().createNamespace(namespace);
    }

    @Override
    public void updateNamespace(Namespace namespace) {
        storage().updateNamespace(namespace);
    }

    @Override
    public void deleteNamespace(Long id) {
        storage().deleteNamespace(id);
    }

    @Override
    public void updateDataSourcePosition(DbDataSourcePositionUpdateRequest updateDataSourcePositionRequest) {
        storage().updateDataSourcePosition(updateDataSourcePositionRequest);
    }

    @Override
    public List<Node> getTree() {
        return storage().getTree();
    }

    @Override
    public void updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        storage().updatePosition(dropToNode, dragNode, dropPosition);
    }

    @Override
    public List<String> queryPinTables(DbTablePinRequest pinTableRequest) {
        return storage().queryPinTables(pinTableRequest);
    }

    @Override
    public void pinTable(PinTable request) {
        storage().pinTable(request);
    }

    @Override
    public void deletePinTable(PinTable request) {
        storage().deletePinTable(request);
    }

    @Override
    public Long createOperationLog(OperationLog request) {
        return storage().createOperationLog(request);
    }

    @Override
    public PageResponse<OperationLog> operationLogList(OpsOperationLogPageQueryRequest operationLogPageQueryRequest) {
        return storage().operationLogList(operationLogPageQueryRequest);
    }

    @Override
    public OperationLog getOperationLog(Long id) {
        return storage().getOperationLog(id);
    }

    @Override
    public PageResponse<Operation> consoleList(OpsOperationPageQueryRequest operationPageQueryRequest) {
        return storage().consoleList(operationPageQueryRequest);
    }

    @Override
    public Operation getConsole(Long id) {
        return storage().getConsole(id);
    }

    @Override
    public void deleteConsole(Long id) {
        storage().deleteConsole(id);
    }

    @Override
    public Long createConsole(Operation request) {
        return storage().createConsole(request);
    }

    @Override
    public void updateConsole(Operation request) {
        storage().updateConsole(request);
    }

    @Override
    public String getErPosition(Long dataSourceId, String databaseName, String schemaName) {
        return storage().getErPosition(dataSourceId, databaseName, schemaName);
    }

    @Override
    public void savePosition(ERPosition request) {
        storage().savePosition(request);
    }

    private IWorkspaceStorage storage() {
        if (resolver == null) {
            throw new IllegalStateException("IWorkspaceStorageFacade dependencies are not available");
        }
        return resolver.resolve();
    }
}
