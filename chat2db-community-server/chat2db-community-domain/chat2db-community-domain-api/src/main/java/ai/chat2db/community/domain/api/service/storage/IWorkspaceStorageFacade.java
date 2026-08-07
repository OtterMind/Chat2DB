package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.er.ERPosition;
import ai.chat2db.community.domain.api.model.workspace.Namespace;
import ai.chat2db.community.domain.api.model.workspace.Node;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.operation.OperationLog;
import ai.chat2db.community.domain.api.model.pin.PinTable;
import ai.chat2db.community.domain.api.model.task.Task;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordCreateRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordPageRequest;
import ai.chat2db.community.domain.api.model.request.task.TaskRecordUpdateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePositionUpdateRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationLogPageQueryRequest;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationPageQueryRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.tools.exception.storage.UnsupportedStorageCapabilityException;

import java.util.List;

/**
 * Provides workspace persistence operations through the selected storage implementation.
 */
public interface IWorkspaceStorageFacade {

    /**
     * Returns a workspace datasource by identifier.
     *
     * @param id datasource identifier.
     * @param requestPassword whether the returned datasource should include password fields.
     * @return workspace datasource, or null when no matching datasource exists.
     */
    WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword);

    /**
     * Creates a workspace datasource record.
     *
     * @param dataSource datasource data to persist.
     * @return created datasource identifier.
     */
    Long createDataSource(WorkspaceDataSource dataSource);

    /**
     * Deletes a workspace datasource record.
     *
     * @param id datasource identifier.
     */
    void deleteDataSource(Long id);

    /**
     * Updates a workspace datasource record.
     *
     * @param dataSource datasource data to persist.
     * @return updated datasource identifier.
     */
    Long updateDataSource(WorkspaceDataSource dataSource);

    /**
     * Updates only the shared identity color of a datasource.
     *
     * @param id datasource identifier.
     * @param identityColor normalized nullable identity color.
     * @return updated datasource identifier.
     */
    default Long updateDataSourceIdentityColor(Long id, String identityColor) {
        throw UnsupportedStorageCapabilityException.forCapability("updateDataSourceIdentityColor");
    }

    /**
     * Lists workspace datasources with pagination and filters.
     *
     * @param dbDataSourcePageQueryRequest datasource page query parameters.
     * @return paged workspace datasource records.
     */
    PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest dbDataSourcePageQueryRequest);

    /**
     * Returns datasource namespace tree data.
     *
     * @return datasource namespace tree data.
     */
    WorkspaceDataSourceNamespace getNamespaceDataSources();

    /**
     * Creates a workspace namespace.
     *
     * @param namespace namespace data to persist.
     * @return created namespace identifier.
     */
    Long createNamespace(Namespace namespace);

    /**
     * Updates a workspace namespace.
     *
     * @param namespace namespace data to persist.
     */
    void updateNamespace(Namespace namespace);

    /**
     * Deletes a workspace namespace.
     *
     * @param id namespace identifier.
     */
    void deleteNamespace(Long id);

    /**
     * Updates datasource ordering or namespace position.
     *
     * @param dbDataSourcePositionUpdateRequest datasource position update parameters.
     */
    void updateDataSourcePosition(DbDataSourcePositionUpdateRequest dbDataSourcePositionUpdateRequest);

    /**
     * Returns the workspace navigation tree.
     *
     * @return workspace navigation nodes.
     */
    List<Node> getTree();

    /**
     * Updates node position in the workspace tree.
     *
     * @param dropToNode target node for the drop operation.
     * @param dragNode node being moved.
     * @param dropPosition drop position relative to the target node.
     */
    void updatePosition(Node dropToNode, Node dragNode, Integer dropPosition);

    /**
     * Lists pinned tables for a datasource scope.
     *
     * @param dbTablePinRequest pinned table query parameters.
     * @return pinned table names.
     */
    List<String> queryPinTables(DbTablePinRequest dbTablePinRequest);

    /**
     * Pins a table in the workspace.
     *
     * @param request table pin data.
     */
    void pinTable(PinTable request);

    /**
     * Removes a pinned table from the workspace.
     *
     * @param request table pin data to remove.
     */
    void deletePinTable(PinTable request);

    /**
     * Lists workspace tasks with pagination and filters.
     *
     * @param taskRecordPageRequest task page query parameters.
     * @return paged task records.
     */
    PageResponse<Task> taskList(TaskRecordPageRequest taskRecordPageRequest);

    /**
     * Returns a workspace task by identifier.
     *
     * @param id task identifier.
     * @return task record, or null when no matching task exists.
     */
    Task getTask(Long id);

    /**
     * Creates a workspace task.
     *
     * @param taskRecordCreateRequest task creation parameters.
     * @return created task identifier.
     */
    Long createTask(TaskRecordCreateRequest taskRecordCreateRequest);

    /**
     * Updates a workspace task.
     *
     * @param taskRecordUpdateRequest task update parameters.
     */
    void updateTask(TaskRecordUpdateRequest taskRecordUpdateRequest);

    /**
     * Creates an operation log record.
     *
     * @param request operation log data to persist.
     * @return created operation log identifier.
     */
    Long createOperationLog(OperationLog request);

    /**
     * Lists operation log records with pagination and filters.
     *
     * @param opsOperationLogPageQueryRequest operation log page query parameters.
     * @return paged operation log records.
     */
    PageResponse<OperationLog> operationLogList(OpsOperationLogPageQueryRequest opsOperationLogPageQueryRequest);

    /**
     * Returns an operation log record by identifier.
     *
     * @param id operation log identifier.
     * @return operation log record, or null when no matching log exists.
     */
    OperationLog getOperationLog(Long id);

    /**
     * Lists saved console records with pagination and filters.
     *
     * @param opsOperationPageQueryRequest console page query parameters.
     * @return paged console records.
     */
    PageResponse<Operation> consoleList(OpsOperationPageQueryRequest opsOperationPageQueryRequest);

    /**
     * Returns a saved console record by identifier.
     *
     * @param id console identifier.
     * @return console record, or null when no matching console exists.
     */
    Operation getConsole(Long id);

    /**
     * Deletes a saved console record.
     *
     * @param id console identifier.
     */
    void deleteConsole(Long id);

    /**
     * Creates a saved console record.
     *
     * @param request console data to persist.
     * @return created console identifier.
     */
    Long createConsole(Operation request);

    /**
     * Updates a saved console record.
     *
     * @param request console data to persist.
     */
    void updateConsole(Operation request);

    /**
     * Returns saved ER diagram position data for a datasource scope.
     *
     * @param dataSourceId datasource identifier.
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @return serialized ER position data, or null when no position is saved.
     */
    String getErPosition(Long dataSourceId, String databaseName, String schemaName);

    /**
     * Saves ER diagram position data for a datasource scope.
     *
     * @param request ER position data to persist.
     */
    void savePosition(ERPosition request);
}
