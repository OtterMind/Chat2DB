package ai.chat2db.spi.sql;

import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Server-side registry that pins one isolated JDBC connection to a SQL Console so that
 * repeated executions inside a manual transaction share the same connection and the same
 * transaction, instead of borrowing and returning a pooled connection per request.
 *
 * <p>The registry is the single source of truth for console-bound transactions. A bound
 * {@link ConnectInfo} carries {@code consoleOwn=true} which tells {@link ConnectionPool}
 * and {@link Chat2DBContext} that the connection must NOT be returned to the pool while a
 * transaction is open; its lifecycle is owned here.
 *
 * <p>Bound connections are released on explicit commit/rollback, console close, connection
 * switch, datasource edit/close (swept via {@link #releaseByDataSourceId(Long)}), application
 * shutdown ({@link #releaseAll(boolean)}), and as a safety net after the idle timeout
 * ({@link #IDLE_TIMEOUT_MILLIS}) for consoles abandoned without a close event.
 */
@Slf4j
public class ConsoleTransactionRegistry {

    /**
     * Idle bound connections older than this are rolled back and released by the cleanup
     * daemon. Matches the {@link ConnectionPool} eviction window so abandoned console
     * transactions do not outlive pooled connections by much.
     */
    static final long IDLE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private static final long CLEANUP_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Runs {@link Connection#abort(Executor)} synchronously, mirroring
     * {@code DefaultSQLExecutor.DIRECT_ABORT_EXECUTOR}. Aborting is preferred over
     * {@link Connection#close()} because it forcefully releases server-side resources even
     * when the connection is stuck mid-operation.
     */
    private static final Executor DIRECT_ABORT_EXECUTOR = Runnable::run;

    private static final ConcurrentHashMap<Long, BoundTransaction> BOUND = new ConcurrentHashMap<>();

    static {
        // Daemon + named so the periodic eviction never blocks JVM exit and is attributable.
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(CLEANUP_INTERVAL_MILLIS);
                    evictIdleBoundTransactions();
                } catch (Throwable e) {
                    log.error("console transaction cleanup error", e);
                }
            }
        }, "chat2db-console-tx-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private ConsoleTransactionRegistry() {
    }

    public enum TransactionState {
        IN_TRANSACTION,
        COMMITTING,
        ROLLING_BACK
    }

    /**
     * Outcome of a commit/rollback/release operation, surfaced to the frontend so it can
     * tell the user whether the transaction was cleanly resolved or its outcome is unknown.
     */
    public enum TransactionOutcome {
        COMMITTED,
        ROLLED_BACK,
        RELEASED_WITHOUT_TRANSACTION,
        UNKNOWN
    }

    public static class BoundTransaction {
        private final ConnectInfo connectInfo;
        private volatile TransactionState state;
        private volatile long lastUsedMillis;

        BoundTransaction(ConnectInfo connectInfo) {
            this.connectInfo = connectInfo;
            this.state = TransactionState.IN_TRANSACTION;
            this.lastUsedMillis = System.currentTimeMillis();
        }

        public ConnectInfo getConnectInfo() {
            return connectInfo;
        }

        public TransactionState getState() {
            return state;
        }

        void setState(TransactionState state) {
            this.state = state;
        }

        void touch() {
            this.lastUsedMillis = System.currentTimeMillis();
        }

        long lastUsedMillis() {
            return lastUsedMillis;
        }
    }

    /**
     * Registers a console-bound transaction. The supplied {@link ConnectInfo} must already
     * hold a live connection with {@code autoCommit=false} and {@code consoleOwn=true}.
     * Replaces any prior binding for the same console (which should have been released first).
     */
    public static void register(Long consoleId, ConnectInfo connectInfo) {
        if (consoleId == null || connectInfo == null) {
            return;
        }
        BoundTransaction previous = BOUND.put(consoleId, new BoundTransaction(connectInfo));
        if (previous != null) {
            log.warn("Replaced an existing bound transaction for consoleId={}; releasing the stale one", consoleId);
            discardQuietly(previous.getConnectInfo());
        }
    }

    /**
     * Returns the bound {@link ConnectInfo} for the console, or null when no transaction is
     * open. Callers that need to reuse the connection across requests use this to feed the
     * already-open connection back into the ThreadLocal context so
     * {@link ConnectionPool#getConnection(ConnectInfo)} hits its
     * {@code tryGetExistingConnection} fast path.
     */
    public static ConnectInfo getBoundConnectInfo(Long consoleId) {
        if (consoleId == null) {
            return null;
        }
        BoundTransaction bound = BOUND.get(consoleId);
        if (bound == null) {
            return null;
        }
        bound.touch();
        return bound.getConnectInfo();
    }

    public static boolean isInTransaction(Long consoleId) {
        BoundTransaction bound = BOUND.get(consoleId);
        return bound != null && bound.getState() == TransactionState.IN_TRANSACTION;
    }

    public static TransactionState getState(Long consoleId) {
        BoundTransaction bound = BOUND.get(consoleId);
        return bound == null ? null : bound.getState();
    }

    /**
     * Commits the console's open transaction, restores auto-commit, and releases the bound
     * connection back to the pool. Returns {@link TransactionOutcome#UNKNOWN} when the
     * commit itself failed and the connection had to be discarded (outcome unknown to the
     * server).
     */
    public static TransactionOutcome commit(Long consoleId) {
        BoundTransaction bound = BOUND.get(consoleId);
        if (bound == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        bound.setState(TransactionState.COMMITTING);
        Connection connection = bound.getConnectInfo().getConnection();
        boolean discardRequired = false;
        try {
            connection.commit();
        } catch (SQLException e) {
            log.error("Commit failed for consoleId={}, discarding connection", consoleId, e);
            discardRequired = true;
        }
        if (!discardRequired) {
            discardRequired = !restoreAutoCommit(connection);
        }
        if (discardRequired) {
            discardQuietly(bound.getConnectInfo());
            BOUND.remove(consoleId);
            return TransactionOutcome.UNKNOWN;
        }
        bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
        BOUND.remove(consoleId);
        ConnectionPool.close(bound.getConnectInfo());
        return TransactionOutcome.COMMITTED;
    }

    /**
     * Rolls back the console's open transaction, restores auto-commit, and releases the
     * bound connection. As with {@link #commit(Long)}, a rollback failure discards the
     * connection and returns {@link TransactionOutcome#UNKNOWN}.
     *
     * <p>Note: non-transactional engines (e.g. MyISAM) are not affected by rollback; the
     * caller is responsible for informing the user.
     */
    public static TransactionOutcome rollback(Long consoleId) {
        BoundTransaction bound = BOUND.get(consoleId);
        if (bound == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        bound.setState(TransactionState.ROLLING_BACK);
        Connection connection = bound.getConnectInfo().getConnection();
        boolean discardRequired = false;
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.error("Rollback failed for consoleId={}, discarding connection", consoleId, e);
            discardRequired = true;
        }
        if (!discardRequired) {
            discardRequired = !restoreAutoCommit(connection);
        }
        if (discardRequired) {
            discardQuietly(bound.getConnectInfo());
            BOUND.remove(consoleId);
            return TransactionOutcome.UNKNOWN;
        }
        bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
        BOUND.remove(consoleId);
        ConnectionPool.close(bound.getConnectInfo());
        return TransactionOutcome.ROLLED_BACK;
    }

    /**
     * Releases a console's bound connection. When {@code rollbackIfInTransaction} is true
     * and a transaction is open, it is rolled back first; otherwise the connection is
     * released as-is (auto-commit already restored). Used on console close and connection
     * switch.
     */
    public static TransactionOutcome release(Long consoleId, boolean rollbackIfInTransaction) {
        BoundTransaction bound = BOUND.remove(consoleId);
        if (bound == null) {
            return TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        }
        TransactionOutcome outcome = TransactionOutcome.RELEASED_WITHOUT_TRANSACTION;
        if (rollbackIfInTransaction && bound.getState() == TransactionState.IN_TRANSACTION) {
            Connection connection = bound.getConnectInfo().getConnection();
            boolean discardRequired = false;
            try {
                connection.rollback();
                outcome = TransactionOutcome.ROLLED_BACK;
            } catch (SQLException e) {
                log.error("Release-time rollback failed for consoleId={}, discarding connection", consoleId, e);
                discardRequired = true;
            }
            if (!discardRequired) {
                discardRequired = !restoreAutoCommit(connection);
            }
            if (discardRequired) {
                discardQuietly(bound.getConnectInfo());
                return TransactionOutcome.UNKNOWN;
            }
        } else {
            // No open transaction or caller asked not to roll back; ensure auto-commit is on.
            restoreAutoCommit(bound.getConnectInfo().getConnection());
        }
        bound.getConnectInfo().setConsoleOwn(Boolean.FALSE);
        ConnectionPool.close(bound.getConnectInfo());
        return outcome;
    }

    /**
     * Sweeps all bound connections belonging to a datasource (identified by the bound
     * ConnectInfo's dataSourceId), rolling back and releasing them. Called from
     * {@link ConnectionPool#removeConnection(Long)} so editing/closing a datasource also
     * reclaims console-bound connections that are held outside the pool queue.
     */
    public static void releaseByDataSourceId(Long dataSourceId) {
        if (dataSourceId == null) {
            return;
        }
        List<Long> toRelease = new ArrayList<>();
        for (Map.Entry<Long, BoundTransaction> entry : BOUND.entrySet()) {
            if (Objects.equals(entry.getValue().getConnectInfo().getDataSourceId(), dataSourceId)) {
                toRelease.add(entry.getKey());
            }
        }
        for (Long consoleId : toRelease) {
            release(consoleId, true);
        }
    }

    /**
     * Releases every bound connection. Called on application shutdown. When
     * {@code rollbackIfInTransaction} is true, open transactions are rolled back first.
     */
    public static void releaseAll(boolean rollbackIfInTransaction) {
        List<Long> toRelease = new ArrayList<>(BOUND.keySet());
        for (Long consoleId : toRelease) {
            try {
                release(consoleId, rollbackIfInTransaction);
            } catch (Throwable e) {
                log.error("Release-all failed for consoleId={}", consoleId, e);
            }
        }
    }

    /**
     * Returns the number of currently bound transactions. Primarily for tests and
     * diagnostics.
     */
    public static int size() {
        return BOUND.size();
    }

    private static boolean restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
            return true;
        } catch (SQLException e) {
            log.error("Failed to restore autoCommit after transaction resolution", e);
            return false;
        }
    }

    private static void discardQuietly(ConnectInfo connectInfo) {
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

    static void evictIdleBoundTransactions() {
        long now = System.currentTimeMillis();
        List<Long> toEvict = new ArrayList<>();
        for (Map.Entry<Long, BoundTransaction> entry : BOUND.entrySet()) {
            BoundTransaction bound = entry.getValue();
            if (bound.getState() != TransactionState.IN_TRANSACTION) {
                // A commit/rollback is in flight on another thread; leave it alone.
                continue;
            }
            if (now - bound.lastUsedMillis() > IDLE_TIMEOUT_MILLIS) {
                toEvict.add(entry.getKey());
            }
        }
        for (Long consoleId : toEvict) {
            log.warn("Evicting idle bound transaction for consoleId={} after {}ms", consoleId, IDLE_TIMEOUT_MILLIS);
            release(consoleId, true);
        }
    }
}
