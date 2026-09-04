package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.model.runtime.TransactionStateResponse;
import ai.chat2db.community.domain.api.model.runtime.TransactionMode;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.McpConnectionContextRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbObjectsQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.ops.IOpsOperationSavedService;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.model.request.ViewMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.community.domain.api.enums.plugin.ObjectTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Slf4j
@Service
public class DbConnectionContextServiceImpl implements IDbConnectionContextService {

    @Autowired
    private IDbWorkspaceDataSourceService workspaceDataSourceService;

    @Autowired
    private ConnectionContextConverter connectionContextConverter;

    @Autowired
    private IOpsOperationSavedService operationSavedService;

    @Override
    public void bind(DbConnectionContextRequest param) {
        // When a manual transaction is open for this console, reuse the bound connection
        // and the trusted metadata captured when the transaction began. Rebuilding from the
        // current request would let later request database/schema fields change the console's
        // transaction context while the JDBC connection itself stays bound.
        ConnectInfo bound = ConnectionPool.getBoundConnectInfo(param.getConsoleId());
        if (bound != null && bound.getConnection() != null) {
            validateBoundTransactionRequest(param, bound);
            ConnectInfo connectInfo = bound.copy();
            connectInfo.setConnection(bound.getConnection());
            connectInfo.setConsoleOwn(Boolean.TRUE);
            Chat2DBContext.putContext(connectInfo);
            return;
        }
        ConnectInfo connectInfo = buildConnectInfo(param);
        Chat2DBContext.putContext(connectInfo);
    }

    @Override
    public ConnectionProfile buildProfile(DbConnectionContextRequest param) {
        return connectionContextConverter.connectInfo2profile(buildConnectInfo(param));
    }

    @Override
    public void bindProfile(ConnectionProfile profile) {
        ConnectInfo connectInfo;
        if (profile != null && profile.getDataSourceId() != null && profile.getDataSourceId() > 0) {
            DbConnectionContextRequest param = new DbConnectionContextRequest();
            param.setDataSourceId(profile.getDataSourceId());
            param.setConsoleId(profile.getConsoleId());
            param.setDatabaseName(profile.getDatabaseName());
            param.setSchemaName(profile.getSchemaName());
            connectInfo = buildConnectInfo(param);
        } else {
            connectInfo = connectionContextConverter.profile2connectInfo(profile);
        }
        if (connectInfo != null) {
            Chat2DBContext.putContext(connectInfo);
        }
    }

    @Override
    public void bindMcp(McpConnectionContextRequest param) {
        Chat2DBContext.putContext(buildMcpConnectInfo(param));
    }

    @Override
    public void clear() {
        Chat2DBContext.removeContext();
    }

    @Override
    public void rebindCurrentDatabase(String databaseName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new BusinessException("connection error");
        }
        // Switching database changes the connection's catalog, so an open transaction cannot
        // survive it. Release the bound connection (rolling back any open transaction) before
        // rebuilding the context for the new database.
        if (connectInfo.getConsoleId() != null
                && ConnectionPool.isInTransaction(connectInfo.getConsoleId())) {
            ConnectionPool.release(connectInfo.getConsoleId(), true);
        }
        Chat2DBContext.removeContext();
        connectInfo.setDatabaseName(databaseName);
        connectInfo.setConnection(null);
        connectInfo.setConsoleOwn(Boolean.FALSE);
        Chat2DBContext.putContext(connectInfo);
    }

    @Override
    public void close() {
        Chat2DBContext.close();
    }

    @Override
    public TransactionStateResponse beginManualTransaction(DbConnectionContextRequest param) {
        Long consoleId = requireConsoleId(param);
        try {
            return ConnectionPool.withConsoleLock(consoleId, () -> beginManualTransactionLocked(param));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("connection error", null, e);
        }
    }

    private TransactionStateResponse beginManualTransactionLocked(DbConnectionContextRequest param) {
        try {
            DbConnectionContextRequest trustedParam = resolveTransactionBeginContext(param);
            bind(trustedParam);
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            if (connectInfo == null) {
                throw new BusinessException("connection error");
            }
            // If a transaction is already open for this console, this is idempotent.
            if (ConnectionPool.isInTransaction(trustedParam.getConsoleId())) {
                return manualTransactionState(trustedParam.getConsoleId());
            }
            if (!Chat2DBContext.supportsManualTransactions()) {
                throw new BusinessException("transaction.unsupported");
            }
            TransactionIsolationLevel isolationLevel = Objects.requireNonNullElse(
                    trustedParam.getTransactionIsolationLevel(),
                    TransactionIsolationLevel.DEFAULT
            );
            // Borrow one pool connection for the console and keep the lease until the
            // transaction is resolved. consoleOwn prevents request cleanup from returning it
            // to the shared queue while another console could acquire it.
            connectInfo.setConsoleOwn(Boolean.TRUE);
            Connection connection = ConnectionPool.getConnection(connectInfo);
            List<TransactionIsolationLevel> supportedIsolationLevels = List.of();
            Integer originalJdbcIsolationLevel = null;
            try {
                originalJdbcIsolationLevel = connection.getTransactionIsolation();
                supportedIsolationLevels = supportedTransactionIsolationLevels(connection);
                if (!supportedIsolationLevels.contains(isolationLevel)) {
                    throw new BusinessException("transaction.unsupported");
                }
                configureManualTransactionConnection(connection, isolationLevel);
            } catch (BusinessException e) {
                discardNewTransactionConnection(connectInfo, connection);
                throw e;
            } catch (SQLException e) {
                log.error("Failed to configure manual transaction for consoleId={}", param.getConsoleId(), e);
                discardNewTransactionConnection(connectInfo, connection);
                TransactionStateResponse response = TransactionStateResponse.of(
                        false,
                        TransactionMode.AUTO,
                        isolationLevel,
                        supportedIsolationLevels
                );
                response.setLastError(e.getMessage());
                return response;
            }
            if (!ConnectionPool.isCurrentGeneration(connectInfo)) {
                discardNewTransactionConnection(connectInfo, connection);
                throw new BusinessException("datasource.not.found");
            }
            if (!ConnectionPool.registerIfAbsent(
                    trustedParam.getConsoleId(),
                    connectInfo,
                    isolationLevel,
                    supportedIsolationLevels,
                    originalJdbcIsolationLevel
            )) {
                if (!ConnectionPool.isInTransaction(trustedParam.getConsoleId())) {
                    discardNewTransactionConnection(connectInfo, connection);
                    throw new BusinessException("datasource.not.found");
                }
                // Another request opened the transaction concurrently; drop this request's fresh
                // connection and report the existing open transaction.
                connectInfo.setConsoleOwn(Boolean.FALSE);
                connectInfo.setConnection(null);
                quietlyClose(connection);
                return manualTransactionState(trustedParam.getConsoleId());
            }
            return manualTransactionState(trustedParam.getConsoleId());
        } finally {
            clear();
        }
    }

    @Override
    public TransactionStateResponse commitTransaction(DbConnectionContextRequest param) {
        Long consoleId = requireConsoleId(param);
        try {
            return ConnectionPool.withConsoleLock(consoleId, () -> {
                validateTransactionRequest(param);
                ConnectionPool.TransactionOutcome outcome =
                        ConnectionPool.commit(consoleId);
                TransactionStateResponse response = TransactionStateResponse.of(false, TransactionMode.AUTO);
                applyTransactionOutcome(response, outcome);
                return response;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("connection error", null, e);
        }
    }

    @Override
    public TransactionStateResponse rollbackTransaction(DbConnectionContextRequest param) {
        Long consoleId = requireConsoleId(param);
        try {
            return ConnectionPool.withConsoleLock(consoleId, () -> {
                validateTransactionRequest(param);
                ConnectionPool.TransactionOutcome outcome =
                        ConnectionPool.rollback(consoleId);
                TransactionStateResponse response = TransactionStateResponse.of(false, TransactionMode.AUTO);
                applyTransactionOutcome(response, outcome);
                return response;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("connection error", null, e);
        }
    }

    @Override
    public TransactionStateResponse getTransactionState(DbConnectionContextRequest param) {
        Long consoleId = requireConsoleId(param);
        try {
            return ConnectionPool.withConsoleLock(consoleId, () -> getTransactionStateLocked(param, consoleId));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("connection error", null, e);
        }
    }

    private TransactionStateResponse getTransactionStateLocked(DbConnectionContextRequest param, Long consoleId) {
        validateTransactionRequest(param);
        boolean inTransaction = ConnectionPool.isInTransaction(consoleId);
        if (inTransaction) {
            return manualTransactionState(consoleId);
        }
        try {
            DbConnectionContextRequest trustedParam = resolveTransactionBeginContext(param);
            trustedParam.setConsoleId(null);
            bind(trustedParam);
            Connection connection = Chat2DBContext.getConnection();
            return TransactionStateResponse.of(
                    false,
                    TransactionMode.AUTO,
                    TransactionIsolationLevel.DEFAULT,
                    supportedTransactionIsolationLevels(connection)
            );
        } catch (SQLException e) {
            throw new BusinessException("connection error", null, e);
        } finally {
            clear();
        }
    }

    @Override
    public TransactionStateResponse releaseBoundConnection(DbConnectionContextRequest param) {
        Long consoleId = requireConsoleId(param);
        try {
            ConnectionPool.TransactionOutcome outcome =
                    ConnectionPool.withConsoleLock(consoleId, () -> {
                        validateTransactionRequest(param);
                        return ConnectionPool.release(consoleId, true);
                    });
            TransactionStateResponse response = TransactionStateResponse.of(false, TransactionMode.AUTO);
            applyTransactionOutcome(response, outcome);
            return response;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("connection error", null, e);
        }
    }

    @Override
    public boolean isInTransaction(Long consoleId) {
        return ConnectionPool.isInTransaction(consoleId);
    }

    static void configureManualTransactionConnection(
            Connection connection,
            TransactionIsolationLevel isolationLevel
    ) throws SQLException {
        TransactionIsolationLevel effectiveIsolationLevel = Objects.requireNonNullElse(
                isolationLevel,
                TransactionIsolationLevel.DEFAULT
        );
        int jdbcIsolationLevel = effectiveIsolationLevel == TransactionIsolationLevel.DEFAULT
                ? connection.getMetaData().getDefaultTransactionIsolation()
                : toJdbcTransactionIsolation(effectiveIsolationLevel);
        if (jdbcIsolationLevel != Connection.TRANSACTION_NONE) {
            connection.setTransactionIsolation(jdbcIsolationLevel);
        }
        connection.setAutoCommit(false);
    }

    static List<TransactionIsolationLevel> supportedTransactionIsolationLevels(
            Connection connection
    ) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        if (metaData == null || !metaData.supportsTransactions()) {
            return List.of();
        }
        List<TransactionIsolationLevel> supported = new ArrayList<>();
        supported.add(TransactionIsolationLevel.DEFAULT);
        for (TransactionIsolationLevel isolationLevel : TransactionIsolationLevel.values()) {
            if (isolationLevel != TransactionIsolationLevel.DEFAULT
                    && metaData.supportsTransactionIsolationLevel(toJdbcTransactionIsolation(isolationLevel))) {
                supported.add(isolationLevel);
            }
        }
        return List.copyOf(supported);
    }

    private static int toJdbcTransactionIsolation(TransactionIsolationLevel isolationLevel) {
        return switch (isolationLevel) {
            case READ_UNCOMMITTED -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case READ_COMMITTED -> Connection.TRANSACTION_READ_COMMITTED;
            case REPEATABLE_READ -> Connection.TRANSACTION_REPEATABLE_READ;
            case SERIALIZABLE -> Connection.TRANSACTION_SERIALIZABLE;
            case DEFAULT -> throw new IllegalArgumentException("DEFAULT does not have a JDBC isolation value");
        };
    }

    private static TransactionStateResponse manualTransactionState(Long consoleId) {
        return TransactionStateResponse.of(
                true,
                TransactionMode.MANUAL,
                ConnectionPool.getIsolationLevel(consoleId),
                ConnectionPool.getSupportedIsolationLevels(consoleId)
        );
    }

    private static void applyTransactionOutcome(
            TransactionStateResponse response,
            ConnectionPool.TransactionOutcome outcome
    ) {
        response.setOutcome(outcome.name());
        response.setLastError(ConnectionPool.consumeLastTransactionCleanupError());
    }

    @Override
    public <T> T withConsoleTransactionLock(Long consoleId, Callable<T> action) throws Exception {
        return ConnectionPool.withConsoleLock(consoleId, action);
    }

    @Override
    public void releaseAllBoundTransactions() {
        ConnectionPool.releaseAll(true);
    }

    @Override
    public ConnectionProfile currentProfile() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        return connectionContextConverter.connectInfo2profile(connectInfo);
    }

    @Override
    public ConnectionProfile currentProfileSnapshot() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            return null;
        }
        return connectionContextConverter.connectInfo2profile(connectInfo.copy());
    }

    @Override
    public DriverConfig getDefaultDriverConfig(String dbType) {
        return Chat2DBContext.getDefaultDriverConfig(dbType);
    }

    @Override
    public boolean supportCrossDatabase() {
        return Chat2DBContext.getDbMetaData().supportCrossDatabase();
    }

    @Override
    public boolean supportCrossSchema() {
        return Chat2DBContext.getDbMetaData().supportCrossSchema();
    }

    @Override
    public boolean supportDatabase() {
        return Chat2DBContext.getDBConfig().isSupportDatabase();
    }

    @Override
    public boolean supportSchema() {
        return Chat2DBContext.getDBConfig().isSupportSchema();
    }

    @Override
    public List<String> getSystemDatabases(String dbType) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData(dbType);
        if (metaData == null) {
            return Collections.emptyList();
        }
        return Objects.requireNonNullElse(metaData.getSystemDatabases(), Collections.emptyList());
    }

    @Override
    public List<String> getSystemSchemas(String dbType) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData(dbType);
        if (metaData == null) {
            return Collections.emptyList();
        }
        return Objects.requireNonNullElse(metaData.getSystemSchemas(), Collections.emptyList());
    }

    @Override
    public List<ForeignKeyInfo> getImportedKeys(String databaseName, String schemaName, String tableName) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        if (metaData == null) {
            return Collections.emptyList();
        }
        return metaData.getImportedKeys(Chat2DBContext.getConnection(),
                new TableMetadataRequest(databaseName, schemaName, tableName));
    }

    @Override
    public List<ai.chat2db.community.domain.api.model.metadata.Table> queryObjects(
            DbObjectsQueryRequest queryObjectsRequest) {
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        String databaseName = queryObjectsRequest == null ? null : queryObjectsRequest.getDatabaseName();
        String schemaName = queryObjectsRequest == null ? null : queryObjectsRequest.getSchemaName();
        String objectName = queryObjectsRequest == null ? null : queryObjectsRequest.getObjectName();
        String objectType = queryObjectsRequest == null ? null : queryObjectsRequest.getObjectType();
        if (metaData == null || StringUtils.isBlank(objectName)) {
            return Collections.emptyList();
        }
        List<ai.chat2db.community.domain.api.model.metadata.Table> tables;
        if (ObjectTypeEnum.VIEW.name().equalsIgnoreCase(objectType)) {
            tables = metaData.views(Chat2DBContext.getConnection(),
                    new ViewMetadataRequest(databaseName, schemaName, objectName));
        } else {
            tables = metaData.tables(Chat2DBContext.getConnection(),
                    new TablesRequest(databaseName, schemaName, objectName));
        }
        if (tables == null) {
            return Collections.emptyList();
        }
        return tables.stream().filter(Objects::nonNull).toList();
    }

    private ConnectInfo buildConnectInfo(DbConnectionContextRequest param) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(param.getDataSourceId(), true);
        if (dataSource == null) {
            log.info("query datasource failed:{}", param.getDataSourceId());
            throw new BusinessException("datasource.not.found");
        }
        return connectionContextConverter.datasource2connectInfo(param, dataSource, dataSource.getUrl());
    }

    private DbConnectionContextRequest resolveTransactionBeginContext(DbConnectionContextRequest param) {
        DbConnectionContextRequest resolved = new DbConnectionContextRequest();
        resolved.setDataSourceId(param.getDataSourceId());
        resolved.setConsoleId(param.getConsoleId());
        resolved.setTransactionIsolationLevel(param.getTransactionIsolationLevel());
        Operation console = param.getConsoleId() == null ? null : operationSavedService.getConsole(param.getConsoleId());
        if (console == null) {
            return resolved;
        }
        if (console.getDataSourceId() != null && param.getDataSourceId() != null
                && !Objects.equals(console.getDataSourceId(), param.getDataSourceId())) {
            throw new BusinessException("transaction.datasource.mismatch");
        }
        resolved.setDataSourceId(Objects.requireNonNullElse(console.getDataSourceId(), param.getDataSourceId()));
        resolved.setDatabaseName(console.getDatabaseName());
        resolved.setSchemaName(console.getSchemaName());
        return resolved;
    }

    private void validateBoundTransactionRequest(DbConnectionContextRequest param, ConnectInfo bound) {
        if (param.getDataSourceId() != null && bound.getDataSourceId() != null
                && !Objects.equals(bound.getDataSourceId(), param.getDataSourceId())) {
            throw new BusinessException("transaction.datasource.mismatch");
        }
    }

    private void validateTransactionRequest(DbConnectionContextRequest param) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(param.getDataSourceId(), false);
        if (dataSource == null) {
            log.info("query datasource failed:{}", param.getDataSourceId());
            throw new BusinessException("datasource.not.found");
        }
        Long boundDataSourceId = ConnectionPool.getBoundDataSourceId(param.getConsoleId());
        if (boundDataSourceId != null && !Objects.equals(boundDataSourceId, param.getDataSourceId())) {
            throw new BusinessException("transaction.datasource.mismatch");
        }
    }

    private static Long requireConsoleId(DbConnectionContextRequest param) {
        if (param == null || param.getConsoleId() == null) {
            throw new BusinessException("transaction.console.required");
        }
        return param.getConsoleId();
    }

    private ConnectInfo buildMcpConnectInfo(McpConnectionContextRequest param) {
        return connectionContextConverter.mcpParam2connectInfo(param, connectionContextConverter.buildMcpDataSourceId(param));
    }

    private void quietlyClose(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Failed to close connection during transaction begin cleanup", e);
        }
    }

    private void discardNewTransactionConnection(ConnectInfo connectInfo, Connection connection) {
        connectInfo.setConsoleOwn(Boolean.FALSE);
        connectInfo.setConnection(null);
        quietlyClose(connection);
    }

}
