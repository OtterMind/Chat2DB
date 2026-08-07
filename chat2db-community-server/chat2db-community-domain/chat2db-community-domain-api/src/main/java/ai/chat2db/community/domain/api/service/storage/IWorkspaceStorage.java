package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.tools.exception.storage.UnsupportedStorageCapabilityException;
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

import java.util.List;

/**
 * Defines optional workspace persistence capabilities for datasources, tasks, consoles, pins, and ER positions.
 */
public interface IWorkspaceStorage {

    /**
     * Returns a workspace datasource by identifier.
     *
     * @param id datasource identifier.
     * @param requestPassword whether the returned datasource should include password fields.
     * @return workspace datasource, or null when no matching datasource exists.
     */
    default WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
        throw unsupported("queryDataSourceById");
    }

    /**
     * Creates a workspace datasource record.
     *
     * @param dataSource datasource data to persist.
     * @return created datasource identifier.
     */
    default Long createDataSource(WorkspaceDataSource dataSource) {
        throw unsupported("createDataSource");
    }

    /**
     * Deletes a workspace datasource record.
     *
     * @param id datasource identifier.
     */
    default void deleteDataSource(Long id) {
        throw unsupported("deleteDataSource");
    }

    /**
     * Updates a workspace datasource record.
     *
     * @param dataSource datasource data to persist.
     * @return updated datasource identifier.
     */
    default Long updateDataSource(WorkspaceDataSource dataSource) {
        throw unsupported("updateDataSource");
    }

    /**
     * Updates only the shared identity color of a datasource. Providers must implement
     * a native partial update so credentials are never fetched or resubmitted as a
     * side effect of changing presentation metadata.
     *
     * @param id datasource identifier.
     * @param identityColor normalized nullable identity color.
     * @return updated datasource identifier.
     */
    default Long updateDataSourceIdentityColor(Long id, String identityColor) {
        throw unsupported("updateDataSourceIdentityColor");
    }

    /**
     * Lists workspace datasources with pagination and filters.
     *
     * @param dbDataSourcePageQueryRequest datasource page query parameters.
     * @return paged workspace datasource records.
     */
    default PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest dbDataSourcePageQueryRequest){
        throw unsupported("listDataSources");
    }

    /**
     * Returns datasource namespace tree data.
     *
     * @return datasource namespace tree data.
     */
    default WorkspaceDataSourceNamespace getNamespaceDataSources() {
        throw unsupported("getNamespaceDataSources");
    }

    /**
     * Creates a workspace namespace.
     *
     * @param namespace namespace data to persist.
     * @return created namespace identifier.
     */
    default Long createNamespace(Namespace namespace) {
        throw unsupported("createNamespace");
    }

    /**
     * Updates a workspace namespace.
     *
     * @param namespace namespace data to persist.
     */
    default void updateNamespace(Namespace namespace) {
        throw unsupported("updateNamespace");
    }

    /**
     * Deletes a workspace namespace.
     *
     * @param id namespace identifier.
     */
    default void deleteNamespace(Long id) {
        throw unsupported("deleteNamespace");
    }

    /**
     * Updates datasource ordering or namespace position.
     *
     * @param dbDataSourcePositionUpdateRequest datasource position update parameters.
     */
    default void updateDataSourcePosition(DbDataSourcePositionUpdateRequest dbDataSourcePositionUpdateRequest){
        throw unsupported("updateDataSourcePosition");
    }

    /**
     * Returns the workspace navigation tree.
     *
     * @return workspace navigation nodes.
     */
    default List<Node> getTree() {
        throw unsupported("getTree");
    }

    /**
     * Updates node position in the workspace tree.
     *
     * @param dropToNode target node for the drop operation.
     * @param dragNode node being moved.
     * @param dropPosition drop position relative to the target node.
     */
    default void updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        throw unsupported("updatePosition");
    }

    /**
     * Lists pinned tables for a datasource scope.
     *
     * @param dbTablePinRequest pinned table query parameters.
     * @return pinned table names.
     */
    default List<String> queryPinTables(DbTablePinRequest dbTablePinRequest){
        throw unsupported("queryPinTables");
    }

    /**
     * Pins a table in the workspace.
     *
     * @param request table pin data.
     */
    default void pinTable(PinTable request) {
        throw unsupported("pinTable");
    }

    /**
     * Removes a pinned table from the workspace.
     *
     * @param request table pin data to remove.
     */
    default void deletePinTable(PinTable request) {
        throw unsupported("deletePinTable");
    }

    /**
     * Lists workspace tasks with pagination and filters.
     *
     * @param taskRecordPageRequest task page query parameters.
     * @return paged task records.
     */
    default PageResponse<Task> taskList(TaskRecordPageRequest taskRecordPageRequest){
        throw unsupported("taskList");
    }

    /**
     * Returns a workspace task by identifier.
     *
     * @param id task identifier.
     * @return task record, or null when no matching task exists.
     */
    default Task getTask(Long id) {
        throw unsupported("getTask");
    }

    /**
     * Creates a workspace task.
     *
     * @param taskRecordCreateRequest task creation parameters.
     * @return created task identifier.
     */
    default Long createTask(TaskRecordCreateRequest taskRecordCreateRequest){
        throw unsupported("createTask");
    }

    /**
     * Updates a workspace task.
     *
     * @param taskRecordUpdateRequest task update parameters.
     */
    default void updateTask(TaskRecordUpdateRequest taskRecordUpdateRequest){
        throw unsupported("updateTask");
    }

    /**
     * Creates an operation log record.
     *
     * @param request operation log data to persist.
     * @return created operation log identifier.
     */
    default Long createOperationLog(OperationLog request) {
        throw unsupported("createOperationLog");
    }

    /**
     * Lists operation log records with pagination and filters.
     *
     * @param opsOperationLogPageQueryRequest operation log page query parameters.
     * @return paged operation log records.
     */
    default PageResponse<OperationLog> operationLogList(OpsOperationLogPageQueryRequest opsOperationLogPageQueryRequest){
        throw unsupported("operationLogList");
    }

    /**
     * Returns an operation log record by identifier.
     *
     * @param id operation log identifier.
     * @return operation log record, or null when no matching log exists.
     */
    default OperationLog getOperationLog(Long id) {
        throw unsupported("getOperationLog");
    }

    /**
     * Lists saved console records with pagination and filters.
     *
     * @param opsOperationPageQueryRequest console page query parameters.
     * @return paged console records.
     */
    default PageResponse<Operation> consoleList(OpsOperationPageQueryRequest opsOperationPageQueryRequest){
        throw unsupported("consoleList");
    }

    /**
     * Returns a saved console record by identifier.
     *
     * @param id console identifier.
     * @return console record, or null when no matching console exists.
     */
    default Operation getConsole(Long id) {
        throw unsupported("getConsole");
    }

    /**
     * Deletes a saved console record.
     *
     * @param id console identifier.
     */
    default void deleteConsole(Long id) {
        throw unsupported("deleteConsole");
    }

    /**
     * Creates a saved console record.
     *
     * @param request console data to persist.
     * @return created console identifier.
     */
    default Long createConsole(Operation request) {
        throw unsupported("createConsole");
    }

    /**
     * Updates a saved console record.
     *
     * @param request console data to persist.
     */
    default void updateConsole(Operation request) {
        throw unsupported("updateConsole");
    }

    /**
     * Returns saved ER diagram position data for a datasource scope.
     *
     * @param dataSourceId datasource identifier.
     * @param databaseName database name that scopes the lookup.
     * @param schemaName schema name that scopes the lookup.
     * @return serialized ER position data, or null when no position is saved.
     */
    default String getErPosition(Long dataSourceId, String databaseName, String schemaName) {
        throw unsupported("getErPosition");
    }

    /**
     * Saves ER diagram position data for a datasource scope.
     *
     * @param request ER position data to persist.
     */
    default void savePosition(ERPosition request) {
        throw unsupported("savePosition");
    }

    private UnsupportedStorageCapabilityException unsupported(String capability) {
        return UnsupportedStorageCapabilityException.forCapability(capability);
    }
}
