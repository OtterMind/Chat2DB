package ai.chat2db.community.storage;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.enums.StorageTypeEnum;
import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.datasource.DataSourceNamespace;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
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
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorage;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import ai.chat2db.community.storage.converter.StorageConverter;
import ai.chat2db.community.storage.large.ConsoleStorage;
import ai.chat2db.community.storage.large.OperationLogStorage;
import ai.chat2db.community.storage.large.TaskStorage;
import ai.chat2db.community.storage.small.*;
import ai.chat2db.community.tools.security.AesGcmUtil;
import ai.chat2db.community.tools.wrapper.result.DataResult;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class LocalWorkspaceStorage implements IWorkspaceStorage {

    private final StorageConverter storageConverter;

    public LocalWorkspaceStorage(StorageConverter storageConverter) {
        this.storageConverter = storageConverter;
    }

    @Override
    public WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
        DataSource dataSource = DataSourceStorage.INSTANCE.getById(id);
        if (dataSource == null) {
            return null;
        }
        WorkspaceDataSource result = storageConverter.dataSource2workspace(dataSource);
        result.setStorageType(StorageTypeEnum.LOCAL.name());
        if (!Boolean.TRUE.equals(requestPassword)) {
            result.setPassword(null);
        }
        return result;
    }

    @Override
    public Long createDataSource(WorkspaceDataSource dataSource) {
        dataSource.setStorageType(StorageTypeEnum.LOCAL.name());
        dataSource.setPassword(encryptString(dataSource.getPassword()));
        encryptSslSensitiveFields(dataSource.getSsl());
        dataSource.setId(DataSourceStorage.INSTANCE.generateId());
        Long id = DataSourceStorage.INSTANCE.save(storageConverter.workspace2dataSource(dataSource));
        if (dataSource.getSpaceId() != null && dataSource.getSpaceId() > 0) {
            NamespaceStorage.INSTANCE.updateDataSourcePosition(dataSource.getSpaceId(), id);
        }
        return id;
    }

    @Override
    public void deleteDataSource(Long id) {
        DataSourceStorage.INSTANCE.delete(id);
    }

    @Override
    public Long updateDataSource(WorkspaceDataSource dataSource) {
        dataSource.setStorageType(StorageTypeEnum.LOCAL.name());
        DataSource oldDataSource = DataSourceStorage.INSTANCE.getById(dataSource.getId());
        if (dataSource.getPassword() != null && !dataSource.getPassword().isEmpty()) {
            dataSource.setPassword(encryptString(dataSource.getPassword()));
        } else {
            dataSource.setPassword(oldDataSource == null ? null : oldDataSource.getPassword());
        }
        dataSource.setSsl(mergeAndEncryptSsl(dataSource.getSsl(),
                oldDataSource == null ? null : oldDataSource.getSsl()));
        DataSourceStorage.INSTANCE.update(storageConverter.workspace2dataSource(dataSource));
        return dataSource.getId();
    }

    @Override
    public PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest dataSourcePageQueryRequest) {
        List<DataSource> dataSources = DataSourceStorage.INSTANCE.getDataList();
        List<WorkspaceDataSource> result = storageConverter.dataSource2workspace(dataSources);
        result.forEach(dataSource -> dataSource.setStorageType(StorageTypeEnum.LOCAL.name()));
        return page(result, dataSourcePageQueryRequest.getPageNo(), dataSourcePageQueryRequest.getPageSize());
    }

    @Override
    public WorkspaceDataSourceNamespace getNamespaceDataSources() {
        DataResult<DataSourceNamespace> result = DataSourceStorage.INSTANCE.getNamespaceDatasource();
        return result.getData() == null ? null : storageConverter.dataSourceNamespace2workspace(result.getData());
    }

    @Override
    public Long createNamespace(Namespace namespace) {
        return NamespaceStorage.INSTANCE.save(namespace);
    }

    @Override
    public void updateNamespace(Namespace namespace) {
        NamespaceStorage.INSTANCE.update(namespace);
    }

    @Override
    public void deleteNamespace(Long id) {
        NamespaceStorage.INSTANCE.delete(id);
    }

    @Override
    public void updateDataSourcePosition(DbDataSourcePositionUpdateRequest updateDataSourcePositionRequest) {
        NamespaceStorage.INSTANCE.updateDataSourcePosition(updateDataSourcePositionRequest.getNamespaceId(),
                updateDataSourcePositionRequest.getDataSourceId());
    }

    @Override
    public List<Node> getTree() {
        return DataSourceStorage.INSTANCE.getNodes();
    }

    @Override
    public void updatePosition(Node dropToNode, Node dragNode, Integer dropPosition) {
        TreeNodeStorage.INSTANCE.updatePosition(dropToNode, dragNode, dropPosition);
    }

    @Override
    public List<String> queryPinTables(DbTablePinRequest pinTableRequest) {
        PinTable pinTable = storageConverter.pinTableParam2model(pinTableRequest);
        return PinTableStorage.INSTANCE.getPinTables(pinTable);
    }

    @Override
    public void pinTable(PinTable request) {
        PinTableStorage.INSTANCE.save(request);
    }

    @Override
    public void deletePinTable(PinTable request) {
        PinTableStorage.INSTANCE.delete(request);
    }

    @Override
    public PageResponse<Task> taskList(TaskRecordPageRequest taskPageRequest) {
        List<Task> tasks = TaskStorage.INSTANCE.getDataList();
        return page(tasks, taskPageRequest.getPageNo(), taskPageRequest.getPageSize());
    }

    @Override
    public Task getTask(Long id) {
        return TaskStorage.INSTANCE.getById(id);
    }

    @Override
    public Long createTask(TaskRecordCreateRequest taskCreateRequest) {
        Task task = storageConverter.taskCreateParam2model(taskCreateRequest);
        task.setId(TaskStorage.INSTANCE.generateId());
        task.setGmtCreate(new Date());
        task.setGmtModified(new Date());
        TaskStorage.INSTANCE.save(task);
        return task.getId();
    }

    @Override
    public void updateTask(TaskRecordUpdateRequest taskUpdateRequest) {
        Task task = storageConverter.taskUpdateParam2model(taskUpdateRequest);
        task.setGmtModified(new Date());
        TaskStorage.INSTANCE.update(task);
    }

    @Override
    public Long createOperationLog(OperationLog request) {
        request.setGmtCreate(DateUtil.format(new Date(), DatePattern.NORM_DATETIME_PATTERN));
        request.setGmtModified(DateUtil.format(new Date(), DatePattern.NORM_DATETIME_PATTERN));
        return OperationLogStorage.INSTANCE.save(request);
    }

    @Override
    public PageResponse<OperationLog> operationLogList(OpsOperationLogPageQueryRequest operationLogPageQueryRequest) {
        List<OperationLog> logs = OperationLogStorage.INSTANCE.getDataList();
        return page(logs, operationLogPageQueryRequest.getPageNo(), operationLogPageQueryRequest.getPageSize());
    }

    @Override
    public OperationLog getOperationLog(Long id) {
        return OperationLogStorage.INSTANCE.getById(id);
    }

    @Override
    public PageResponse<Operation> consoleList(OpsOperationPageQueryRequest operationPageQueryRequest) {
        Operation operation = storageConverter.operationPageParam2model(operationPageQueryRequest);
        List<Operation> allConsoles = ConsoleStorage.INSTANCE.getDataList(operation, 1, Integer.MAX_VALUE);
        return page(allConsoles, operationPageQueryRequest.getPageNo(), operationPageQueryRequest.getPageSize());
    }

    @Override
    public Operation getConsole(Long id) {
        return ConsoleStorage.INSTANCE.getById(id);
    }

    @Override
    public void deleteConsole(Long id) {
        ConsoleStorage.INSTANCE.delete(id);
    }

    @Override
    public Long createConsole(Operation request) {
        return ConsoleStorage.INSTANCE.save(request);
    }

    @Override
    public void updateConsole(Operation request) {
        ConsoleStorage.INSTANCE.update(request);
    }

    @Override
    public String getErPosition(Long dataSourceId, String databaseName, String schemaName) {
        return ERPositionStorage.getInstance().getPosition(dataSourceId, databaseName, schemaName);
    }

    @Override
    public void savePosition(ERPosition request) {
        ERPositionStorage.getInstance().savePosition(request);
    }

    private String encryptString(String password) {
        if (password == null || password.isEmpty()) {
            return password;
        }
        return AesGcmUtil.configured().encrypt(password);
    }

    /**
     * Encrypt the secret TLS fields in place on create. Public material (CA/client cert PEM,
     * keystore type, mode) is left cleartext.
     */
    private void encryptSslSensitiveFields(SSLInfo ssl) {
        if (ssl == null) {
            return;
        }
        ssl.setClientPrivateKeyPem(encryptString(ssl.getClientPrivateKeyPem()));
        ssl.setClientKeyPassword(encryptString(ssl.getClientKeyPassword()));
        ssl.setKeyStoreBytes(encryptString(ssl.getKeyStoreBytes()));
        ssl.setKeyStorePassword(encryptString(ssl.getKeyStorePassword()));
    }

    /**
     * Reconcile incoming TLS material against the previously-saved (still-encrypted) material on
     * update, mirroring the password preserve-if-blank rule.
     *
     * <p>Secret fields (private key, key password, keystore bytes, keystore password): a blank
     * incoming value keeps the previous encrypted value, so the user does not have to re-upload
     * the key on every edit; a non-blank incoming value is encrypted and replaces it.
     *
     * <p>Public fields (mode, CA PEM, client cert PEM, keystore type): the incoming value always
     * wins — an empty string clears the previous value, satisfying "replacing or deleting TLS
     * material makes the previous material unavailable".
     *
     * @param incoming      the SSLInfo from the update request (secrets still cleartext)
     * @param oldEncrypted  the previously-saved SSLInfo (secrets still encrypted), may be null
     * @return the SSLInfo to persist, or null when neither side has TLS configured
     */
    private SSLInfo mergeAndEncryptSsl(SSLInfo incoming, SSLInfo oldEncrypted) {
        if (incoming == null) {
            return oldEncrypted;
        }
        if (oldEncrypted == null) {
            encryptSslSensitiveFields(incoming);
            return incoming;
        }
        // Public fields: incoming wins (empty string clears).
        oldEncrypted.setTlsMode(incoming.getTlsMode());
        oldEncrypted.setCaPem(incoming.getCaPem());
        oldEncrypted.setClientCertPem(incoming.getClientCertPem());
        oldEncrypted.setKeyStoreType(incoming.getKeyStoreType());
        // Secret fields: preserve the previous encrypted value when blank, otherwise re-encrypt.
        oldEncrypted.setClientPrivateKeyPem(
                preserveOrEncrypt(incoming.getClientPrivateKeyPem(), oldEncrypted.getClientPrivateKeyPem()));
        oldEncrypted.setClientKeyPassword(
                preserveOrEncrypt(incoming.getClientKeyPassword(), oldEncrypted.getClientKeyPassword()));
        oldEncrypted.setKeyStoreBytes(
                preserveOrEncrypt(incoming.getKeyStoreBytes(), oldEncrypted.getKeyStoreBytes()));
        oldEncrypted.setKeyStorePassword(
                preserveOrEncrypt(incoming.getKeyStorePassword(), oldEncrypted.getKeyStorePassword()));
        return oldEncrypted;
    }

    private String preserveOrEncrypt(String incomingCleartext, String oldEncrypted) {
        if (incomingCleartext == null || incomingCleartext.isEmpty()) {
            return oldEncrypted;
        }
        return encryptString(incomingCleartext);
    }

    private int normalizePageNo(Integer pageNo) {
        return Math.max(1, pageNo == null ? 1 : pageNo);
    }

    private int normalizePageSize(Integer pageSize) {
        return Math.max(1, pageSize == null ? 100 : pageSize);
    }

    private <T> PageResponse<T> page(List<T> data, Integer requestedPageNo, Integer requestedPageSize) {
        int pageNo = normalizePageNo(requestedPageNo);
        int pageSize = normalizePageSize(requestedPageSize);
        long total = data.size();
        long offset = ((long) pageNo - 1L) * pageSize;
        if (offset >= total) {
            return PageResponse.of(List.of(), total, pageNo, pageSize);
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(offset + (long) pageSize, total);
        return PageResponse.of(data.subList(fromIndex, toIndex), total, pageNo, pageSize);
    }
}
