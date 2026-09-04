package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.model.runtime.TransactionMode;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.ops.IOpsOperationSavedService;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbConnectionContextServiceImplTransactionTest {

    private static final String TEST_DB_TYPE = "TEST_TX";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        ConnectionPool.releaseAll(true);
        Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
    }

    @Test
    void commitValidatesDatasourceVisibilityBeforeMutatingConsoleTransaction() {
        long consoleId = 9001L;
        AtomicInteger commits = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(commits, new AtomicInteger()));
        DbConnectionContextServiceImpl service = service(Set.of());

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.commitTransaction(request(consoleId, 10L)));

        assertEquals("datasource.not.found", thrown.getCode());
        assertEquals(0, commits.get());
        assertTrue(ConnectionPool.isInTransaction(consoleId));
    }

    @Test
    void rollbackRejectsDatasourceMismatchBeforeMutatingConsoleTransaction() {
        long consoleId = 9002L;
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), rollbacks));
        DbConnectionContextServiceImpl service = service(Set.of(11L));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.rollbackTransaction(request(consoleId, 11L)));

        assertEquals("transaction.datasource.mismatch", thrown.getCode());
        assertEquals(0, rollbacks.get());
        assertTrue(ConnectionPool.isInTransaction(consoleId));
    }

    @Test
    void bindOpenTransactionUsesBoundContextInsteadOfRequestDatabaseAndSchema() {
        long consoleId = 9003L;
        Connection connection = proxyConnection(new AtomicInteger(), new AtomicInteger());
        ConnectInfo bound = registerBound(consoleId, 10L, connection);
        bound.setDatabaseName("trusted_db");
        bound.setSchemaName("trusted_schema");
        DbConnectionContextServiceImpl service = new DbConnectionContextServiceImpl();
        DbConnectionContextRequest taintedRequest = request(consoleId, 10L);
        taintedRequest.setDatabaseName("evil`; DROP DATABASE trusted_db; --");
        taintedRequest.setSchemaName("evil_schema");

        service.bind(taintedRequest);

        ConnectInfo current = Chat2DBContext.getConnectInfo();
        assertEquals("trusted_db", current.getDatabaseName());
        assertEquals("trusted_schema", current.getSchemaName());
        assertSame(connection, current.getConnection());
    }

    @Test
    void transactionStateRejectsDatasourceMismatch() {
        long consoleId = 9004L;
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), new AtomicInteger()));
        DbConnectionContextServiceImpl service = service(Set.of(10L, 11L));

        assertThrows(BusinessException.class, () -> service.getTransactionState(request(consoleId, 11L)));
    }

    @Test
    void transactionStateReturnsBoundIsolationLevel() {
        long consoleId = 9007L;
        registerBound(
                consoleId,
                10L,
                proxyConnection(new AtomicInteger(), new AtomicInteger()),
                TransactionIsolationLevel.READ_COMMITTED
        );
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.getTransactionState(request(consoleId, 10L));

        assertTrue(response.isInTransaction());
        assertEquals(TransactionIsolationLevel.READ_COMMITTED, response.getIsolationLevel());
        assertEquals(List.of(
                TransactionIsolationLevel.DEFAULT,
                TransactionIsolationLevel.READ_COMMITTED
        ), response.getSupportedIsolationLevels());
    }

    @Test
    void supportedIsolationLevelsComeFromJdbcMetadata() throws Exception {
        Connection connection = capabilityConnection(true, Set.of(
                Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ
        ));

        assertEquals(List.of(
                TransactionIsolationLevel.DEFAULT,
                TransactionIsolationLevel.READ_COMMITTED,
                TransactionIsolationLevel.REPEATABLE_READ
        ), DbConnectionContextServiceImpl.supportedTransactionIsolationLevels(connection));
    }

    @Test
    void datasourceWithoutTransactionSupportReturnsNoIsolationLevels() throws Exception {
        Connection connection = capabilityConnection(false, Set.of());

        assertEquals(List.of(), DbConnectionContextServiceImpl.supportedTransactionIsolationLevels(connection));
    }

    @Test
    void releaseReturnsRolledBackOutcomeAndClearsBoundTransaction() {
        long consoleId = 9005L;
        AtomicInteger rollbacks = new AtomicInteger();
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), rollbacks));
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.releaseBoundConnection(request(consoleId, 10L));

        assertFalse(response.isInTransaction());
        assertEquals(TransactionMode.AUTO, response.getMode());
        assertEquals("ROLLED_BACK", response.getOutcome());
        assertEquals(1, rollbacks.get());
        assertFalse(ConnectionPool.isInTransaction(consoleId));
    }

    @Test
    void releaseReturnsUnknownOutcomeWhenRollbackCannotBeConfirmed() {
        long consoleId = 9006L;
        registerBound(consoleId, 10L, rollbackFailingConnection());
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.releaseBoundConnection(request(consoleId, 10L));

        assertFalse(response.isInTransaction());
        assertEquals(TransactionMode.AUTO, response.getMode());
        assertEquals("UNKNOWN", response.getOutcome());
        assertFalse(ConnectionPool.isInTransaction(consoleId));
    }

    @Test
    void commitKeepsCommittedOutcomeAndReportsCleanupError() {
        long consoleId = 9009L;
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        registerBound(
                consoleId,
                10L,
                cleanupFailingConnection(commits, aborts),
                TransactionIsolationLevel.READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ
        );
        DbConnectionContextServiceImpl service = service(Set.of(10L));

        var response = service.commitTransaction(request(consoleId, 10L));

        assertFalse(response.isInTransaction());
        assertEquals(TransactionMode.AUTO, response.getMode());
        assertEquals("COMMITTED", response.getOutcome());
        assertEquals(
                "Failed to restore transaction isolation: restore isolation failed",
                response.getLastError()
        );
        assertEquals(1, commits.get());
        assertEquals(1, aborts.get());
        assertFalse(ConnectionPool.isInTransaction(consoleId));
    }

    @Test
    void selectedIsolationLevelIsAppliedBeforeAutoCommitIsDisabled() throws Exception {
        List<String> calls = new ArrayList<>();
        Connection connection = connectionRecordingConfiguration(calls);

        DbConnectionContextServiceImpl.configureManualTransactionConnection(
                connection,
                TransactionIsolationLevel.READ_COMMITTED
        );

        assertEquals(List.of(
                "setTransactionIsolation:" + Connection.TRANSACTION_READ_COMMITTED,
                "setAutoCommit:false"
        ), calls);
    }

    @Test
    void databaseDefaultIsolationOnlyDisablesAutoCommit() throws Exception {
        List<String> calls = new ArrayList<>();
        Connection connection = connectionRecordingConfiguration(calls, Connection.TRANSACTION_REPEATABLE_READ);

        DbConnectionContextServiceImpl.configureManualTransactionConnection(
                connection,
                TransactionIsolationLevel.DEFAULT
        );

        assertEquals(List.of(
                "setTransactionIsolation:" + Connection.TRANSACTION_REPEATABLE_READ,
                "setAutoCommit:false"
        ), calls);
    }

    @Test
    void beginWaitsForOtherOperationsOnTheSameConsole() throws Exception {
        long consoleId = 9008L;
        registerBound(consoleId, 10L, proxyConnection(new AtomicInteger(), new AtomicInteger()));
        DbConnectionContextServiceImpl service = service(Set.of(10L));
        CountDownLatch lockEntered = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        FutureTask<Void> holder = new FutureTask<>(() -> ConnectionPool.withConsoleLock(consoleId, () -> {
            lockEntered.countDown();
            assertTrue(releaseLock.await(2, TimeUnit.SECONDS));
            return null;
        }));
        new Thread(holder, "transaction-lock-holder").start();
        assertTrue(lockEntered.await(2, TimeUnit.SECONDS));

        FutureTask<Boolean> begin = new FutureTask<>(() ->
                service.beginManualTransaction(request(consoleId, 10L)).isInTransaction());
        new Thread(begin, "transaction-begin").start();

        Thread.sleep(100L);
        assertFalse(begin.isDone());
        releaseLock.countDown();

        holder.get(2, TimeUnit.SECONDS);
        assertTrue(begin.get(2, TimeUnit.SECONDS));
    }

    @Test
    void beginRejectsConnectionWhenDatasourceGenerationChangesBeforeRegister() {
        long consoleId = 9010L;
        long dataSourceId = -9010L;
        AtomicInteger closes = new AtomicInteger();
        installPlugin(generationInvalidatingConnection(dataSourceId, closes));
        DbConnectionContextServiceImpl service = service(Set.of(dataSourceId));
        DbConnectionContextRequest request = request(consoleId, dataSourceId);
        request.setTransactionIsolationLevel(TransactionIsolationLevel.READ_COMMITTED);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.beginManualTransaction(request));

        assertEquals("datasource.not.found", thrown.getCode());
        assertEquals(1, closes.get());
        assertFalse(ConnectionPool.isInTransaction(consoleId));
        assertNull(ConnectionPool.getBoundConnectInfo(consoleId));
    }

    private static DbConnectionContextServiceImpl service(Set<Long> visibleDatasourceIds) {
        DbConnectionContextServiceImpl service = new DbConnectionContextServiceImpl();
        setField(service, "workspaceDataSourceService",
                new VisibleWorkspaceDataSourceService(visibleDatasourceIds));
        setField(service, "connectionContextConverter", new ConnectionContextConverter());
        setField(service, "operationSavedService", Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{IOpsOperationSavedService.class},
                (proxy, method, args) -> null
        ));
        return service;
    }

    private static DbConnectionContextRequest request(long consoleId, long dataSourceId) {
        DbConnectionContextRequest request = new DbConnectionContextRequest();
        request.setConsoleId(consoleId);
        request.setDataSourceId(dataSourceId);
        return request;
    }

    private static ConnectInfo registerBound(long consoleId, long dataSourceId, Connection connection) {
        return registerBound(consoleId, dataSourceId, connection, TransactionIsolationLevel.DEFAULT);
    }

    private static ConnectInfo registerBound(
            long consoleId,
            long dataSourceId,
            Connection connection,
            TransactionIsolationLevel isolationLevel
    ) {
        return registerBound(consoleId, dataSourceId, connection, isolationLevel, null);
    }

    private static ConnectInfo registerBound(
            long consoleId,
            long dataSourceId,
            Connection connection,
            TransactionIsolationLevel isolationLevel,
            Integer originalJdbcIsolationLevel
    ) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConsoleId(consoleId);
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        ConnectionPool.markCurrentGeneration(connectInfo);
        assertTrue(ConnectionPool.registerIfAbsent(
                consoleId,
                connectInfo,
                isolationLevel,
                supportedIsolationLevels(isolationLevel),
                originalJdbcIsolationLevel
        ));
        return connectInfo;
    }

    private static List<TransactionIsolationLevel> supportedIsolationLevels(
            TransactionIsolationLevel isolationLevel
    ) {
        return isolationLevel == TransactionIsolationLevel.DEFAULT
                ? List.of(TransactionIsolationLevel.DEFAULT)
                : List.of(TransactionIsolationLevel.DEFAULT, isolationLevel);
    }

    private static Connection proxyConnection(AtomicInteger commits, AtomicInteger rollbacks) {
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "rollback" -> {
                        rollbacks.incrementAndGet();
                        yield null;
                    }
                    case "setAutoCommit", "close", "abort" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection rollbackFailingConnection() {
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "rollback" -> throw new SQLException("rollback failed");
                    case "close", "abort" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection cleanupFailingConnection(AtomicInteger commits, AtomicInteger aborts) {
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "commit" -> {
                        commits.incrementAndGet();
                        yield null;
                    }
                    case "setTransactionIsolation" -> throw new SQLException("restore isolation failed");
                    case "setAutoCommit", "close" -> null;
                    case "abort" -> {
                        aborts.incrementAndGet();
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection generationInvalidatingConnection(long dataSourceId, AtomicInteger closes) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "supportsTransactions" -> true;
                    case "supportsTransactionIsolationLevel" -> true;
                    case "getDefaultTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    case "getTransactionIsolation" -> {
                        ConnectionPool.removeConnection(dataSourceId);
                        yield Connection.TRANSACTION_REPEATABLE_READ;
                    }
                    case "setTransactionIsolation", "setAutoCommit", "rollback", "abort" -> null;
                    case "close" -> {
                        closes.incrementAndGet();
                        yield null;
                    }
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void installPlugin(Connection connection) {
        DBConfig dbConfig = new DBConfig();
        dbConfig.setDbType(TEST_DB_TYPE);
        dbConfig.setDefaultDriverConfig(new DriverConfig());
        IDbManager dbManager = (IDbManager) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{IDbManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection;
                    default -> defaultValue(method.getReturnType());
                });
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return dbConfig;
            }

            @Override
            public IDbManager getDbManager() {
                return dbManager;
            }

            @Override
            public boolean supportsManualTransactions() {
                return true;
            }
        });
    }

    private static Connection connectionRecordingConfiguration(List<String> calls) {
        return connectionRecordingConfiguration(calls, 0);
    }

    private static Connection connectionRecordingConfiguration(List<String> calls, int defaultIsolation) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDefaultTransactionIsolation" -> defaultIsolation;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setTransactionIsolation" -> {
                        calls.add("setTransactionIsolation:" + args[0]);
                        yield null;
                    }
                    case "setAutoCommit" -> {
                        calls.add("setAutoCommit:" + args[0]);
                        yield null;
                    }
                    case "getMetaData" -> metaData;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Connection capabilityConnection(boolean supportsTransactions, Set<Integer> supportedLevels) {
        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "supportsTransactions" -> supportsTransactions;
                    case "supportsTransactionIsolationLevel" -> supportedLevels.contains((Integer) args[0]);
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                DbConnectionContextServiceImplTransactionTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = DbConnectionContextServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record VisibleWorkspaceDataSourceService(Set<Long> visibleDatasourceIds)
            implements IDbWorkspaceDataSourceService {

        @Override
        public PageResponse<WorkspaceDataSource> listDataSources(DbDataSourcePageQueryRequest request) {
            return PageResponse.empty(1, 10);
        }

        @Override
        public WorkspaceDataSource queryDataSourceById(Long id, Boolean requestPassword) {
            if (!visibleDatasourceIds.contains(id)) {
                return null;
            }
            WorkspaceDataSource dataSource = new WorkspaceDataSource();
            dataSource.setId(id);
            dataSource.setType(TEST_DB_TYPE);
            dataSource.setUrl("jdbc:test");
            dataSource.setDriverConfig(new DriverConfig());
            return dataSource;
        }

        @Override
        public WorkspaceDataSource queryDisplayDataSourceById(Long id, Boolean requestPassword) {
            return queryDataSourceById(id, requestPassword);
        }

        @Override
        public void preConnect(DbDataSourcePreConnectRequest request) {
        }

        @Override
        public WorkspaceDataSource createDataSource(WorkspaceDataSource dataSource) {
            return dataSource;
        }

        @Override
        public WorkspaceDataSource updateDataSource(WorkspaceDataSource dataSource) {
            return dataSource;
        }

        @Override
        public WorkspaceDataSource updateDataSourceIdentityColor(Long id, String identityColor) {
            return queryDataSourceById(id, false);
        }

        @Override
        public void deleteDataSource(Long id) {
        }

        @Override
        public List<WorkspaceDataSource> exportDataSources(List<Long> datasourceIds) {
            return List.of();
        }

        @Override
        public List<WorkspaceDataSource> exportDisplayDataSources(List<Long> datasourceIds) {
            return List.of();
        }
    }
}
