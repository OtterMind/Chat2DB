package ai.chat2db.spi.sql;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultCursorHold;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the cursors behind leased large values alive.
 * <p>
 * A driver that hands over a {@code Clob} or a {@code Blob} has not transferred the content yet, so the value can be
 * read from its cursor later instead of being copied during the query. That only works while the cursor and its
 * connection live: the execution announces them with {@link #begin(ConnectInfo, Statement)}, the large value layer
 * takes a {@link #holdForCurrentExecution()} for every snapshot that leases a value, and the execution asks
 * {@link #isPinned()} before it closes anything. The registry closes the cursor and gives the connection back once the
 * last hold is gone, when a lease has been idle for too long, or on shutdown.
 * <p>
 * A response that carries such a value on a path without a consumer is announced as well: its cursor is closed with
 * the connection context of the execution that produced it, see {@link #finishAnnouncedCursor()}.
 */
@Slf4j
public final class ResultCursorLeaseRegistry {

    /**
     * How many leases one process may keep open. Every lease holds a statement and a connection, so the number stays
     * small on purpose: a value that cannot get a lease is captured into the snapshot the usual way instead.
     */
    static final int MAX_LEASES = 4;

    /**
     * A lease nobody read from for this long is closed, so a forgotten tab can never keep a connection forever.
     */
    static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final long SWEEP_INTERVAL_MS = 60 * 1000L;

    /**
     * Cursor the current thread is streaming, announced by the executor for the duration of one execution.
     */
    private static final ThreadLocal<Scope> CURRENT_SCOPE = new ThreadLocal<>();

    private static final Map<String, Lease> LEASES = new ConcurrentHashMap<>();

    static {
        Thread sweeper = new Thread(ResultCursorLeaseRegistry::sweepForever, "chat2db-cursor-lease-sweeper");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    private ResultCursorLeaseRegistry() {
    }

    /**
     * Announces the cursor of the execution running on this thread. A request can run several statements on the same
     * connection before their responses are read, so a statement of the same connection joins the announcement instead
     * of replacing it: every one of their cursors stays open, and they are closed together.
     */
    public static void begin(ConnectInfo connectInfo, Statement statement) {
        Scope scope = CURRENT_SCOPE.get();
        if (scope == null || scope.connectInfo != connectInfo) {
            finishAnnouncedCursor();
            CURRENT_SCOPE.set(new Scope(connectInfo, statement));
            return;
        }
        scope.announce(statement);
    }

    /**
     * Ends the announcement. A cursor that was leased stays registered until its holds are gone.
     */
    public static void end() {
        CURRENT_SCOPE.remove();
    }

    /**
     * Ends the announcement of the execution running on this thread, closing its cursors when nothing leased them.
     * <p>
     * A large value is read after the query that produced it, so the cursors of an announced execution outlive it. This
     * is called when that execution's connection context goes away: the responses it produced are finished by then, so
     * cursors no reader leased are given up instead of keeping their statements.
     */
    static void finishAnnouncedCursor() {
        Scope scope = CURRENT_SCOPE.get();
        if (scope == null) {
            return;
        }
        CURRENT_SCOPE.remove();
        if (scope.liveLease() != null) {
            // The lease closes the cursors and gives back their connection once the last hold is gone
            return;
        }
        for (Statement statement : scope.statements) {
            try {
                statement.close();
            } catch (Exception e) {
                log.warn("Failed to close the announced cursor: {}", e.getMessage());
            }
        }
    }

    /**
     * @return {@code true} while this statement is one the cursor of this thread was announced for, so its execution
     *         must not close it: a reader may still lease a large value that reads from it
     */
    public static boolean isCursorAnnounced(Statement statement) {
        Scope scope = CURRENT_SCOPE.get();
        return scope != null && scope.holds(statement);
    }

    /**
     * @return {@code true} when the execution on this thread leased a value, so it must not close its cursor
     */
    public static boolean isPinned() {
        Scope scope = CURRENT_SCOPE.get();
        return scope != null && scope.lease != null;
    }

    /**
     * Takes a hold on the cursor of the execution on this thread, so a snapshot can read the leased values later.
     *
     * @return the hold the snapshot closes when it is released, or {@code null} when this execution cannot lease (no
     *         cursor announced, no connection, or the process already holds {@link #MAX_LEASES} of them)
     */
    public static ResultCursorHold holdForCurrentExecution() {
        Scope scope = CURRENT_SCOPE.get();
        if (scope == null || scope.connectInfo == null || scope.statements.isEmpty()) {
            return null;
        }
        Lease lease = scope.liveLease();
        if (lease == null) {
            if (LEASES.size() >= MAX_LEASES) {
                log.warn("No cursor lease available, {} are already open", LEASES.size());
                return null;
            }
            lease = new Lease(UUID.randomUUID().toString(), scope.connectInfo, scope.statements);
            scope.lease = lease;
            LEASES.put(lease.getId(), lease);
        }
        lease.touch();
        lease.hold();
        return new LeaseHold(lease);
    }

    /**
     * @return {@code true} while a leased value still reads from this connection, so its teardown must not close it
     */
    static boolean isHeld(ConnectInfo connectInfo) {
        for (Lease lease : LEASES.values()) {
            if (lease.getConnectInfo() == connectInfo) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes every lease, used when the process shuts down.
     */
    public static void releaseAll() {
        for (Lease lease : LEASES.values()) {
            closeLease(lease);
        }
    }

    /**
     * Closes the leases that nobody read from for {@link #IDLE_TIMEOUT_MS}.
     *
     * @return the number of leases that were closed
     */
    static int evictIdle(long now) {
        int closed = 0;
        for (Lease lease : LEASES.values()) {
            if (now - lease.getLastActivityAt() > IDLE_TIMEOUT_MS) {
                closeLease(lease);
                closed++;
            }
        }
        return closed;
    }

    static int openLeases() {
        return LEASES.size();
    }

    private static void closeLease(Lease lease) {
        if (LEASES.remove(lease.getId()) == null) {
            return;
        }
        log.debug("Closing the cursor lease of {}", lease.getConnectInfo().getKey());
        for (Statement statement : lease.getStatements()) {
            try {
                statement.close();
            } catch (Exception e) {
                log.warn("Failed to close the leased statement: {}", e.getMessage());
            }
        }
        ConnectionPool.close(lease.getConnectInfo());
    }

    private static void sweepForever() {
        while (true) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS);
                evictIdle(System.currentTimeMillis());
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("Failed to sweep cursor leases", e);
            }
        }
    }

    /**
     * Cursors the current thread announced, and the lease they produced.
     */
    private static final class Scope {

        private final ConnectInfo connectInfo;
        private final List<Statement> statements = new ArrayList<>();
        private Lease lease;

        private Scope(ConnectInfo connectInfo, Statement statement) {
            this.connectInfo = connectInfo;
            announce(statement);
        }

        /**
         * Adds a cursor whose response may still lease a value from it. One statement can carry several results, and
         * every one of them announces it, so it is only kept once.
         */
        private void announce(Statement statement) {
            if (holds(statement)) {
                return;
            }
            statements.add(statement);
            Lease open = liveLease();
            if (open != null) {
                // A lease still reads from this connection, so it takes this cursor as well and closes it later
                open.addStatement(statement);
            }
        }

        private boolean holds(Statement statement) {
            for (Statement announced : statements) {
                if (announced == statement) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return the lease of this scope while it is still registered, or {@code null} once it was given up: an idle
         *         sweep can close it while the request that announced the cursor still runs
         */
        private Lease liveLease() {
            return lease != null && LEASES.containsKey(lease.getId()) ? lease : null;
        }
    }

    private static final class Lease {

        private final String id;
        private final ConnectInfo connectInfo;
        /**
         * Statements of one request, which the lease keeps open until it is given up.
         */
        private final List<Statement> statements = new CopyOnWriteArrayList<>();
        private final AtomicInteger holds = new AtomicInteger();
        private final AtomicLong lastActivityAt = new AtomicLong(System.currentTimeMillis());

        private Lease(String id, ConnectInfo connectInfo, List<Statement> statements) {
            this.id = id;
            this.connectInfo = connectInfo;
            this.statements.addAll(statements);
        }

        private String getId() {
            return id;
        }

        private ConnectInfo getConnectInfo() {
            return connectInfo;
        }

        private List<Statement> getStatements() {
            return statements;
        }

        private void addStatement(Statement statement) {
            statements.add(statement);
        }

        private long getLastActivityAt() {
            return lastActivityAt.get();
        }

        private void touch() {
            lastActivityAt.set(System.currentTimeMillis());
        }

        private void hold() {
            holds.incrementAndGet();
        }

        private boolean drop() {
            touch();
            return holds.decrementAndGet() <= 0;
        }
    }

    /**
     * One snapshot's hold on a lease: the cursor lives until every snapshot that reads from it is released.
     */
    private static final class LeaseHold implements ResultCursorHold {

        private final Lease lease;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LeaseHold(Lease lease) {
            this.lease = lease;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (lease.drop()) {
                closeLease(lease);
            }
        }
    }
}
