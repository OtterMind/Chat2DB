package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.model.runtime.TransactionIsolationLevel;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ConnectionPool {

    private static final int MAX_CONNECTIONS = 2;


    private static final int VALIDATION_TIMEOUT_SECONDS = 2;


    private static final long SKIP_VALIDATION_IF_RECENTLY_USED_MS = 30 * 1000L;

    private static final String DEFAULT_VALIDATION_SQL = "select 1";

    private static final String ORACLE_VALIDATION_SQL = "SELECT 1 FROM DUAL";

    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, LinkedBlockingQueue<ConnectInfo>>> CONNECTION_MAP = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Long> CONNECTION_GENERATIONS = new ConcurrentHashMap<>();

    private static final long CONSOLE_TRANSACTION_IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static final long CONSOLE_TRANSACTION_CLEANUP_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Executor DIRECT_ABORT_EXECUTOR = Runnable::run;

    private static final ConcurrentHashMap<Long, BoundTransaction> CONSOLE_TRANSACTIONS = new ConcurrentHashMap<>();

    private static final ThreadLocal<String> LAST_TRANSACTION_CLEANUP_ERROR = new ThreadLocal<>();

    private static final Object[] CONSOLE_LOCKS = new Object[1024];


    static {
        for (int i = 0; i < CONSOLE_LOCKS.length; i++) {
            CONSOLE_LOCKS[i] = new Object();
        }
        // Daemon + named so the periodic cleanup loop never blocks JVM exit and is
        // attributable in thread dumps/monitoring. A user (non-daemon) thread here
        // would hang the process on shutdown once the pool class is loaded.
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000 * 60 * 1);
                    cleanupConnections();
                    evictIdleConsoleTransactions();
                } catch (Exception e) {
                    log.error("close connection error", e);
                }
            }
        }, "chat2db-conn-pool-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    public enum TransactionState {
        IN_TRANSACTION,
        COMMITTING,
        ROLLING_BACK
    }

    public enum TransactionOutcome {
        COMMITTED,
        ROLLED_BACK,
        RELEASED_WITHOUT_TRANSACTION,
        UNKNOWN
    }

    public static class BoundTransaction {
        private final ConnectInfo connectInfo;
        private final TransactionIsolationLevel isolationLevel;
        private final List<TransactionIsolationLevel> supportedIsolationLevels;
        private final Integer originalJdbcIsolationLevel;
        private volatile TransactionState state = TransactionState.IN_TRANSACTION;
        private volatile long lastUsedMillis = System.currentTimeMillis();

        BoundTransaction(
                ConnectInfo connectInfo,
                TransactionIsolationLevel isolationLevel,
                List<TransactionIsolationLevel> supportedIsolationLevels
        ) {
            this(connectInfo, isolationLevel, supportedIsolationLevels, null);
        }

        BoundTransaction(
                ConnectInfo connectInfo,
                TransactionIsolationLevel isolationLevel,
                List<TransactionIsolationLevel> supportedIsolationLevels,
                Integer originalJdbcIsolationLevel
        ) {
            this.connectInfo = connectInfo;
            this.isolationLevel = isolationLevel;
            this.supportedIsolationLevels = List.copyOf(supportedIsolationLevels);
            this.originalJdbcIsolationLevel = originalJdbcIsolationLevel;
        }

        public ConnectInfo getConnectInfo() {
            return connectInfo;
        }

        public TransactionState getState() {
            return state;
        }

        public TransactionIsolationLevel getIsolationLevel() {
            return isolationLevel;
        }

        public List<TransactionIsolationLevel> getSupportedIsolationLevels() {
            return supportedIsolationLevels;
        }

        Integer getOriginalJdbcIsolationLevel() {
            return originalJdbcIsolationLevel;
        }

        void setState(TransactionState state) {
            this.state = state;
        }

        void touch() {
            lastUsedMillis = System.currentTimeMillis();
        }

        void touch(long timestamp) {
            lastUsedMillis = timestamp;
        }

        long lastUsedMillis() {
            return lastUsedMillis;
        }
    }

    static void cleanupConnections() {
        log.info("CONNECTION_MAP size:{}", CONNECTION_MAP.size());
        for (Map.Entry<Long, ConcurrentHashMap<String, LinkedBlockingQueue<ConnectInfo>>> entry : CONNECTION_MAP.entrySet()) {
            ConcurrentHashMap<String, LinkedBlockingQueue<ConnectInfo>> map = entry.getValue();
            if (map == null) {
                continue;
            }
            for (Map.Entry<String, LinkedBlockingQueue<ConnectInfo>> queueEntry : map.entrySet()) {
                LinkedBlockingQueue<ConnectInfo> queue = queueEntry.getValue();
                if (queue == null) {
                    continue;
                }
                log.info(queueEntry.getKey() + " queue size:{}", queue.size());
                cleanupQueue(entry.getKey(), queueEntry.getKey(), queue);
            }
        }
    }

    static void cleanupQueue(LinkedBlockingQueue<ConnectInfo> queue) {
        cleanupQueue(null, null, queue);
    }

    private static void cleanupQueue(Long datasourceId, String connectionKey,
                                     LinkedBlockingQueue<ConnectInfo> queue) {
        int connectionsToCheck = queue.size();
        for (int i = 0; i < connectionsToCheck; i++) {
            ConnectInfo connectInfo = queue.poll();
            if (connectInfo == null) {
                return;
            }
            log.info("check connection:{},usage:{}", connectInfo.getKey(), connectInfo.isInUse());
            Date lastAccessTime = connectInfo.getLastAccessTime();
            if (!connectInfo.trySetInUse()) {
                returnToQueue(datasourceId, connectionKey, queue, connectInfo, false);
                continue;
            }
            boolean reusable = true;
            try {
                boolean expired = lastAccessTime != null
                        && lastAccessTime.getTime() + 1000 * 60 * 30 < System.currentTimeMillis();
                if (expired || !checkConnectionIsActive(connectInfo)) {
                    closeQuietly(connectInfo);
                    reusable = false;
                }
            } finally {
                connectInfo.releaseInUse();
                if (reusable) {
                    returnToQueue(datasourceId, connectionKey, queue, connectInfo, true);
                }
            }
        }
    }

    static LinkedBlockingQueue<ConnectInfo> newConnectionQueue() {
        return new LinkedBlockingQueue<>(MAX_CONNECTIONS);
    }

    static LinkedBlockingQueue<ConnectInfo> getOrCreateConnectionQueue(Long datasourceId,
                                                                        String connectionKey) {
        ConcurrentHashMap<String, LinkedBlockingQueue<ConnectInfo>> map =
                CONNECTION_MAP.computeIfAbsent(datasourceId, key -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(connectionKey, key -> newConnectionQueue());
    }

    static void offerOrClose(LinkedBlockingQueue<ConnectInfo> queue, ConnectInfo connectInfo) {
        if (!queue.offer(connectInfo)) {
            closeQuietly(connectInfo);
        }
    }

    private static void returnToQueue(Long datasourceId, String connectionKey,
                                      LinkedBlockingQueue<ConnectInfo> queue,
                                      ConnectInfo connectInfo, boolean closeIfRejected) {
        if (datasourceId == null) {
            if (!queue.offer(connectInfo)) {
                handleRejectedReturn(connectInfo, closeIfRejected);
            }
            return;
        }
        boolean[] returnedToCurrentQueue = {false};
        CONNECTION_MAP.computeIfPresent(datasourceId, (key, keyMap) -> {
            if (keyMap.get(connectionKey) == queue) {
                returnedToCurrentQueue[0] = queue.offer(connectInfo);
            }
            return keyMap;
        });
        if (!returnedToCurrentQueue[0]) {
            handleRejectedReturn(connectInfo, closeIfRejected);
        }
    }

    private static void handleRejectedReturn(ConnectInfo connectInfo, boolean closeIfRejected) {
        if (closeIfRejected) {
            closeQuietly(connectInfo);
        } else {
            log.warn("Dropped duplicate pooled connection reference for {}", connectInfo.getKey());
        }
    }

    private static boolean checkConnectionIsActive(ConnectInfo connectInfo) {
        try {
            Connection connection = connectInfo.getConnection();
            if (connection == null || connection.isClosed()) {
                return false;
            }
            try {
                return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
            } catch (Throwable ignore) {
                return validateByProbeSql(connectInfo, connection);
            }
        } catch (Exception e) {
            log.error("check connection error,connectInfo:{}", connectInfo.getKey(), e);
            return false;
        }
    }

    private static boolean validateByProbeSql(ConnectInfo connectInfo, Connection connection) {
        String sql = DEFAULT_VALIDATION_SQL;
        try {
            if (DatabaseTypeEnum.HIVE.name().equals(connectInfo.getDbType())
                    || DatabaseTypeEnum.KYLIN.name().equals(connectInfo.getDbType())
                    || DatabaseTypeEnum.PRESTO.name().equals(connectInfo.getDbType())
                    || DatabaseTypeEnum.SUNDB.name().equals(connectInfo.getDbType())
            ) {
                connection.getMetaData().getCatalogs();
                return true;
            }
            if (DatabaseTypeEnum.ORACLE.name().equals(connectInfo.getDbType())
                || DatabaseTypeEnum.OSCAR.name().equals(connectInfo.getDbType())
                || DatabaseTypeEnum.GBASE8S.name().equals(connectInfo.getDbType())
            ) {
                sql = ORACLE_VALIDATION_SQL;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(VALIDATION_TIMEOUT_SECONDS);
                statement.execute();
                return true;
            }
        } catch (Exception e) {
            log.error("check connection error,sql:{},connectInfo:{}", sql, connectInfo.getKey(), e);
            return false;
        }
    }


    private static boolean isRecentlyUsed(Date lastAccessTime) {
        return lastAccessTime != null
                && System.currentTimeMillis() - lastAccessTime.getTime() < SKIP_VALIDATION_IF_RECENTLY_USED_MS;
    }


    private static Connection tryGetExistingConnection(ConnectInfo connectInfo) {
        try {
            Connection conn = connectInfo.getConnection();
            if (conn != null && !conn.isClosed()) {
                return conn;
            }
        } catch (SQLException e) {
            log.warn("Error checking existing connection", e);
        }
        return null;
    }

    static long currentGeneration(Long datasourceId) {
        return datasourceId == null ? 0L : CONNECTION_GENERATIONS.getOrDefault(datasourceId, 0L);
    }

    public static boolean isCurrentGeneration(ConnectInfo connectInfo) {
        if (connectInfo == null) {
            return false;
        }
        Long datasourceId = connectInfo.getDataSourceId();
        if (datasourceId == null) {
            return true;
        }
        return Objects.equals(connectInfo.poolGeneration(), currentGeneration(datasourceId));
    }

    public static void markCurrentGeneration(ConnectInfo connectInfo) {
        if (connectInfo != null && connectInfo.getDataSourceId() != null) {
            connectInfo.updatePoolGeneration(currentGeneration(connectInfo.getDataSourceId()));
        }
    }

    public static Connection createNewConnection(ConnectInfo connectInfo) {
        return createNewConnection(connectInfo, currentGeneration(connectInfo.getDataSourceId()));
    }

    private static Connection createNewConnection(ConnectInfo connectInfo, long generation) {
        log.info("Creating new individual connection");
        Connection connection = Chat2DBContext.getDbManager(connectInfo.getDbType()).getConnection(connectInfo);
        connectInfo.setConnection(connection);
        connectInfo.updatePoolGeneration(generation);
        return connection;
    }

    public static Connection getConnection(ConnectInfo connectInfo) {
        Connection connection = tryGetExistingConnection(connectInfo);
        if (connection != null) {
            return connection;
        }
        Long datasourceId = connectInfo.getDataSourceId();
        if (datasourceId == null) {
            return createNewConnection(connectInfo);
        }
        long acquisitionGeneration = currentGeneration(datasourceId);
        LinkedBlockingQueue<ConnectInfo> queue =
                getOrCreateConnectionQueue(datasourceId, connectInfo.getKey());
        Connection pooledConnection = tryBorrowConnection(connectInfo, datasourceId,
                connectInfo.getKey(), queue, acquisitionGeneration);
        if (pooledConnection != null) {
            return pooledConnection;
        }
        return createNewConnection(connectInfo, acquisitionGeneration);
    }

    static Connection tryBorrowConnection(ConnectInfo connectInfo, LinkedBlockingQueue<ConnectInfo> queue) {
        return tryBorrowConnection(connectInfo, null, null, queue, 0L);
    }

    private static Connection tryBorrowConnection(ConnectInfo connectInfo, Long datasourceId, String connectionKey,
                                                   LinkedBlockingQueue<ConnectInfo> queue,
                                                   long acquisitionGeneration) {
        ConnectInfo pooledConnectInfo = queue.poll();
        if (pooledConnectInfo == null) {
            return null;
        }
        Date lastAccessTime = pooledConnectInfo.getLastAccessTime();
        if (!pooledConnectInfo.trySetInUse()) {
            returnToQueue(datasourceId, connectionKey, queue, pooledConnectInfo, false);
            return null;
        }
        if (datasourceId != null
                && !Objects.equals(pooledConnectInfo.poolGeneration(), acquisitionGeneration)) {
            closeQuietly(pooledConnectInfo);
            return null;
        }
        Connection connection = tryGetExistingConnection(pooledConnectInfo);
        if (connection != null
                && (isRecentlyUsed(lastAccessTime) || checkConnectionIsActive(pooledConnectInfo))) {
            connectInfo.setConnection(connection);
            connectInfo.updatePoolGeneration(pooledConnectInfo.poolGeneration());
            log.info("Got connection from pool");
            return connection;
        }
        closeQuietly(pooledConnectInfo);
        return null;
    }

    public static void removeConnection(Long datasourceId) {
        if (datasourceId == null) {
            return;
        }
        // Invalidate first so a begin path that is between acquire/configure/register cannot
        // publish a stale console-bound connection after this sweep starts.
        CONNECTION_GENERATIONS.merge(datasourceId, 1L, Long::sum);
        ConnectionPool.releaseByDataSourceId(datasourceId);
        CONNECTION_MAP.computeIfPresent(datasourceId, (key, keyMap) -> {
            for (Map.Entry<String, LinkedBlockingQueue<ConnectInfo>> entry : keyMap.entrySet()) {
                LinkedBlockingQueue<ConnectInfo> queue = entry.getValue();
                ConnectInfo connectInfo = queue.poll();
                while (connectInfo != null) {
                    closeQuietly(connectInfo);
                    connectInfo = queue.poll();
                }
            }
            return null;
        });
    }


    private static void closeQuietly(ConnectInfo connectInfo) {
        if (connectInfo == null) {
            return;
        }
        try {
            Connection connection = connectInfo.getConnection();
            if (connection != null && !connection.isClosed()) {
                connectInfo.setConnection(null);
                connection.close();
            }
        } catch (Exception e) {
            log.error("close connection error", e);
        }
    }


    public static void close(ConnectInfo connectInfo) {
        // Console-bound connections (manual transaction mode) are owned by
        // ConnectionPool. They must NOT be returned to the pool queue while a
        // transaction is open; their lifecycle is resolved by commit/rollback/release.
        if (Boolean.TRUE.equals(connectInfo.getConsoleOwn())) {
            return;
        }
        connectInfo.setLastAccessTime(new Date());
        connectInfo.releaseInUse();

        if (DatabaseTypeEnum.MONGODB.name().equals(connectInfo.getDbType())
                || DatabaseTypeEnum.REDIS.name().equals(connectInfo.getDbType())
                || connectInfo.getConnection() == null
        ) {
            closeQuietly(connectInfo);
            return;
        }
        try {
            if (connectInfo.getConnection().isClosed()) {
                closeQuietly(connectInfo);
                return;
            }
        } catch (Exception e) {
            closeQuietly(connectInfo);
            return;
        }

        Long datasourceId = connectInfo.getDataSourceId();
        if (datasourceId == null) {
            closeQuietly(connectInfo);
            return;
        }
        Long leaseGeneration = connectInfo.poolGeneration();
        if (leaseGeneration == null) {
            closeQuietly(connectInfo);
            return;
        }
        long expectedGeneration = leaseGeneration;
        String connectionKey = connectInfo.getKey();
        CONNECTION_MAP.compute(datasourceId, (key, keyMap) -> {
            if (expectedGeneration != currentGeneration(datasourceId) || keyMap == null) {
                closeQuietly(connectInfo);
                return keyMap;
            }
            LinkedBlockingQueue<ConnectInfo> queue = keyMap.get(connectionKey);
            if (queue == null) {
                closeQuietly(connectInfo);
            } else {
                offerOrClose(queue, connectInfo);
            }
            return keyMap;
        });
    }

    /**
     * Registers one exclusive connection lease for a Console. The lease is owned by this
     * connection pool, but is withheld from the shared queue until the transaction resolves.
     */
    public static boolean registerIfAbsent(Long consoleId, ConnectInfo connectInfo) {
        return registerIfAbsent(
                consoleId,
                connectInfo,
                TransactionIsolationLevel.DEFAULT,
                List.of(TransactionIsolationLevel.DEFAULT)
        );
    }

    public static boolean registerIfAbsent(
            Long consoleId,
            ConnectInfo connectInfo,
            TransactionIsolationLevel isolationLevel
    ) {
        List<TransactionIsolationLevel> supportedIsolationLevels = isolationLevel == null
                || isolationLevel == TransactionIsolationLevel.DEFAULT
                ? List.of(TransactionIsolationLevel.DEFAULT)
                : List.of(TransactionIsolationLevel.DEFAULT, isolationLevel);
        return registerIfAbsent(consoleId, connectInfo, isolationLevel, supportedIsolationLevels);
    }

    public static boolean registerIfAbsent(
            Long consoleId,
            ConnectInfo connectInfo,
            TransactionIsolationLevel isolationLevel,
            List<TransactionIsolationLevel> supportedIsolationLevels
    ) {
        return registerIfAbsent(consoleId, connectInfo, isolationLevel, supportedIsolationLevels, null);
    }

    public static boolean registerIfAbsent(
            Long consoleId,
            ConnectInfo connectInfo,
            TransactionIsolationLevel isolationLevel,
            List<TransactionIsolationLevel> supportedIsolationLevels,
            Integer originalJdbcIsolationLevel
    ) {
        if (consoleId == null || connectInfo == null) {
            return false;
        }
        if (!isCurrentGeneration(connectInfo)) {
            return false;
        }
        TransactionIsolationLevel effectiveIsolationLevel = isolationLevel == null
                ? TransactionIsolationLevel.DEFAULT
                : isolationLevel;
        List<TransactionIsolationLevel> effectiveSupportedIsolationLevels = supportedIsolationLevels == null
                ? List.of()
                : supportedIsolationLevels;
        BoundTransaction bound = new BoundTransaction(
                connectInfo,
                effectiveIsolationLevel,
                effectiveSupportedIsolationLevels,
                originalJdbcIsolationLevel
        );
        if (CONSOLE_TRANSACTIONS.putIfAbsent(consoleId, bound) != null) {
            return false;
        }
        if (!isCurrentGeneration(connectInfo)) {
            CONSOLE_TRANSACTIONS.remove(consoleId, bound);
            return false;
        }
        return true;
    }

    public static String consumeLastTransactionCleanupError() {
        String lastError = LAST_TRANSACTION_CLEANUP_ERROR.get();
        LAST_TRANSACTION_CLEANUP_ERROR.remove();
        return lastError;
    }

    public static void touchTransaction(Long consoleId) {
        if (consoleId == null) {
            return;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        if (bound != null && bound.getState() == TransactionState.IN_TRANSACTION) {
            bound.touch();
        }
    }

    private static void clearLastTransactionCleanupError() {
        LAST_TRANSACTION_CLEANUP_ERROR.remove();
    }

    private static void recordLastTransactionCleanupError(String message) {
        if (message != null) {
            LAST_TRANSACTION_CLEANUP_ERROR.set(message);
        }
    }

    public static ConnectInfo getBoundConnectInfo(Long consoleId) {
        if (consoleId == null) {
            return null;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        if (bound == null || bound.getState() != TransactionState.IN_TRANSACTION) {
            return null;
        }
        bound.touch();
        return bound.getConnectInfo();
    }

    static BoundTransaction getBoundTransaction(Long consoleId) {
        return consoleId == null ? null : CONSOLE_TRANSACTIONS.get(consoleId);
    }

    public static boolean isInTransaction(Long consoleId) {
        if (consoleId == null) {
            return false;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        return bound != null && bound.getState() == TransactionState.IN_TRANSACTION;
    }

    public static TransactionState getState(Long consoleId) {
        if (consoleId == null) {
            return null;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        return bound == null ? null : bound.getState();
    }

    public static Long getBoundDataSourceId(Long consoleId) {
        if (consoleId == null) {
            return null;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        return bound == null ? null : bound.getConnectInfo().getDataSourceId();
    }

    public static TransactionIsolationLevel getIsolationLevel(Long consoleId) {
        if (consoleId == null) {
            return TransactionIsolationLevel.DEFAULT;
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        return bound == null ? TransactionIsolationLevel.DEFAULT : bound.getIsolationLevel();
    }

    public static List<TransactionIsolationLevel> getSupportedIsolationLevels(Long consoleId) {
        if (consoleId == null) {
            return List.of();
        }
        BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
        return bound == null ? List.of() : bound.getSupportedIsolationLevels();
    }

    public static <T> T withConsoleLock(Long consoleId, Callable<T> action) throws Exception {
        if (consoleId == null) {
            return action.call();
        }
        synchronized (lockFor(consoleId)) {
            try {
                return action.call();
            } finally {
                touchTransaction(consoleId);
            }
        }
    }

    public static TransactionOutcome commit(Long consoleId) {
        clearLastTransactionCleanupError();
        if (consoleId == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        synchronized (lockFor(consoleId)) {
            BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
            if (bound == null) {
                return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
            }
            bound.setState(TransactionState.COMMITTING);
            Connection connection = bound.getConnectInfo().getConnection();
            try {
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                log.error("Commit failed for consoleId={}, discarding connection", consoleId, e);
                CONSOLE_TRANSACTIONS.remove(consoleId);
                discardConsoleConnection(bound.getConnectInfo());
                bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
                return TransactionOutcome.UNKNOWN;
            }
            CONSOLE_TRANSACTIONS.remove(consoleId);
            cleanupResolvedTransaction(consoleId, bound);
            return TransactionOutcome.COMMITTED;
        }
    }

    public static TransactionOutcome rollback(Long consoleId) {
        clearLastTransactionCleanupError();
        if (consoleId == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        synchronized (lockFor(consoleId)) {
            BoundTransaction bound = CONSOLE_TRANSACTIONS.get(consoleId);
            if (bound == null) {
                return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
            }
            bound.setState(TransactionState.ROLLING_BACK);
            Connection connection = bound.getConnectInfo().getConnection();
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException e) {
                log.error("Rollback failed for consoleId={}, discarding connection", consoleId, e);
                CONSOLE_TRANSACTIONS.remove(consoleId);
                discardConsoleConnection(bound.getConnectInfo());
                bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
                return TransactionOutcome.UNKNOWN;
            }
            CONSOLE_TRANSACTIONS.remove(consoleId);
            cleanupResolvedTransaction(consoleId, bound);
            return TransactionOutcome.ROLLED_BACK;
        }
    }

    public static TransactionOutcome release(Long consoleId, boolean rollbackIfInTransaction) {
        clearLastTransactionCleanupError();
        if (consoleId == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        synchronized (lockFor(consoleId)) {
            BoundTransaction bound = CONSOLE_TRANSACTIONS.remove(consoleId);
            if (bound == null) {
                return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
            }
            TransactionOutcome outcome = TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
            Connection connection = bound.getConnectInfo().getConnection();
            if (rollbackIfInTransaction && bound.getState() == TransactionState.IN_TRANSACTION) {
                try {
                    connection.rollback();
                    outcome = TransactionOutcome.ROLLED_BACK;
                } catch (SQLException | RuntimeException e) {
                    log.error("Release-time rollback failed for consoleId={}, discarding connection", consoleId, e);
                    discardConsoleConnection(bound.getConnectInfo());
                    bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
                    return TransactionOutcome.UNKNOWN;
                }
            }
            cleanupResolvedTransaction(consoleId, bound);
            return outcome;
        }
    }

    private static void cleanupResolvedTransaction(Long consoleId, BoundTransaction bound) {
        ConnectInfo connectInfo = bound.getConnectInfo();
        Connection connection = connectInfo.getConnection();
        String cleanupError = restoreConnectionState(connection, bound.getOriginalJdbcIsolationLevel());
        if (cleanupError != null) {
            log.error("Transaction cleanup failed for consoleId={}, discarding connection: {}",
                    consoleId, cleanupError);
            recordLastTransactionCleanupError(cleanupError);
            discardConsoleConnection(connectInfo);
            connectInfo.setConsoleOwn(Boolean.FALSE);
            return;
        }
        connectInfo.setConsoleOwn(Boolean.FALSE);
        close(connectInfo);
    }

    private static String restoreConnectionState(Connection connection, Integer originalJdbcIsolationLevel) {
        List<String> errors = new ArrayList<>();
        if (connection == null) {
            return "Connection unavailable during transaction cleanup";
        }
        if (originalJdbcIsolationLevel != null) {
            try {
                connection.setTransactionIsolation(originalJdbcIsolationLevel);
            } catch (SQLException | RuntimeException e) {
                errors.add("Failed to restore transaction isolation: " + e.getMessage());
                log.error("Failed to restore transaction isolation after transaction resolution", e);
            }
        }
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException e) {
            errors.add("Failed to restore autoCommit: " + e.getMessage());
            log.error("Failed to restore autoCommit after transaction resolution", e);
        }
        return errors.isEmpty() ? null : String.join("; ", errors);
    }

    public static void releaseByDataSourceId(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        List<Long> toRelease = new ArrayList<>();
        for (Map.Entry<Long, BoundTransaction> entry : CONSOLE_TRANSACTIONS.entrySet()) {
            if (Objects.equals(entry.getValue().getConnectInfo().getDataSourceId(), dataSourceId)) {
                toRelease.add(entry.getKey());
            }
        }
        for (Long consoleId : toRelease) {
            release(consoleId, true);
        }
    }

    public static void releaseAll(boolean rollbackIfInTransaction) {
        List<Long> toRelease = new ArrayList<>(CONSOLE_TRANSACTIONS.keySet());
        for (Long consoleId : toRelease) {
            try {
                release(consoleId, rollbackIfInTransaction);
            } catch (Throwable e) {
                log.error("Release-all failed for consoleId={}", consoleId, e);
            }
        }
    }

    public static int size() {
        return CONSOLE_TRANSACTIONS.size();
    }

    private static void discardConsoleConnection(ConnectInfo connectInfo) {
        if (connectInfo == null) {
            return;
        }
        Connection connection = connectInfo.getConnection();
        if (connection == null) {
            return;
        }
        connectInfo.setConnection(null);
        try {
            connection.abort(DIRECT_ABORT_EXECUTOR);
            return;
        } catch (Throwable abortEx) {
            log.debug("Connection.abort failed, falling back to close", abortEx);
        }
        try {
            connection.close();
        } catch (Throwable closeEx) {
            log.debug("Connection.close failed during discard", closeEx);
        }
    }

    static void evictIdleConsoleTransactions() {
        long now = System.currentTimeMillis();
        List<Map.Entry<Long, BoundTransaction>> toEvict = new ArrayList<>();
        for (Map.Entry<Long, BoundTransaction> entry : CONSOLE_TRANSACTIONS.entrySet()) {
            BoundTransaction bound = entry.getValue();
            if (isIdleForEviction(bound, now)) {
                toEvict.add(Map.entry(entry.getKey(), bound));
            }
        }
        for (Map.Entry<Long, BoundTransaction> candidate : toEvict) {
            evictIdleCandidate(candidate.getKey(), candidate.getValue());
        }
    }

    static boolean evictIdleCandidate(Long consoleId, BoundTransaction expected) {
        if (consoleId == null || expected == null) {
            return false;
        }
        synchronized (lockFor(consoleId)) {
            BoundTransaction current = CONSOLE_TRANSACTIONS.get(consoleId);
            if (current != expected || !isIdleForEviction(current, System.currentTimeMillis())) {
                return false;
            }
            log.warn("Evicting idle console transaction for consoleId={}", consoleId);
            release(consoleId, true);
            return true;
        }
    }

    private static boolean isIdleForEviction(BoundTransaction bound, long now) {
        long idle = now - bound.lastUsedMillis();
        long timeout = bound.getState() == TransactionState.IN_TRANSACTION
                ? CONSOLE_TRANSACTION_IDLE_TIMEOUT_MILLIS
                : CONSOLE_TRANSACTION_IDLE_TIMEOUT_MILLIS * 2;
        return idle > timeout;
    }

    private static Object lockFor(Long consoleId) {
        int index = consoleId == null ? 0 : Math.floorMod(consoleId.hashCode(), CONSOLE_LOCKS.length);
        return CONSOLE_LOCKS[index];
    }

}
