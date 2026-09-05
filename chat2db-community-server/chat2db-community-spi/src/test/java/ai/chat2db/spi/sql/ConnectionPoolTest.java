package ai.chat2db.spi.sql;

import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionPoolTest {

    @Test
    void cleanupThreadShouldBeNamedDaemon() throws ClassNotFoundException {
        Class.forName(ConnectionPool.class.getName());

        Thread cleanupThread = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "chat2db-conn-pool-cleanup".equals(thread.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("connection-pool cleanup thread was not started"));

        assertTrue(cleanupThread.isDaemon());
    }

    @Test
    void cleanupShouldCloseAndRemoveInvalidIdleConnection() {
        AtomicBoolean closed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(false, closed));
        LinkedBlockingQueue<ConnectInfo> queue = new LinkedBlockingQueue<>();
        queue.offer(connectInfo);

        ConnectionPool.cleanupQueue(queue);

        assertTrue(queue.isEmpty());
        assertTrue(closed.get());
        assertNull(connectInfo.getConnection());
    }

    @Test
    void cleanupShouldCloseExpiredConnectionEvenWhenItIsValid() {
        AtomicBoolean closed = new AtomicBoolean();
        ConnectInfo connectInfo = connectInfo(connection(true, closed));
        connectInfo.setLastAccessTime(new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31)));
        LinkedBlockingQueue<ConnectInfo> queue = ConnectionPool.newConnectionQueue();
        queue.offer(connectInfo);

        ConnectionPool.cleanupQueue(queue);

        assertTrue(queue.isEmpty());
        assertTrue(closed.get());
        assertNull(connectInfo.getConnection());
    }

    @Test
    void cleanupShouldLeaveBorrowedConnectionInQueue() {
        ConnectInfo connectInfo = connectInfo(connection(true, new AtomicBoolean()));
        assertTrue(connectInfo.trySetInUse());
        LinkedBlockingQueue<ConnectInfo> queue = new LinkedBlockingQueue<>();
        queue.offer(connectInfo);

        ConnectionPool.cleanupQueue(queue);

        assertSame(connectInfo, queue.peek());
        connectInfo.releaseInUse();
    }

    @Test
    void failedBorrowShouldReturnConnectionInfoToQueue() {
        ConnectInfo pooled = connectInfo(connection(true, new AtomicBoolean()));
        assertTrue(pooled.trySetInUse());
        LinkedBlockingQueue<ConnectInfo> queue = new LinkedBlockingQueue<>();
        queue.offer(pooled);

        assertNull(ConnectionPool.tryBorrowConnection(new ConnectInfo(), queue));
        assertSame(pooled, queue.peek());
        pooled.releaseInUse();
    }

    @Test
    void failedBorrowShouldNotCloseConnectionOwnedByAnotherThreadWhenQueueRefills() {
        AtomicBoolean pooledClosed = new AtomicBoolean();
        ConnectInfo pooled = connectInfo(connection(true, pooledClosed));
        ConnectInfo replacement = connectInfo(connection(true, new AtomicBoolean()));
        assertTrue(pooled.trySetInUse());
        LinkedBlockingQueue<ConnectInfo> queue = new LinkedBlockingQueue<>(1) {
            private boolean refillAfterPoll = true;

            @Override
            public ConnectInfo poll() {
                ConnectInfo result = super.poll();
                if (refillAfterPoll) {
                    refillAfterPoll = false;
                    assertTrue(super.offer(replacement));
                }
                return result;
            }
        };
        queue.offer(pooled);

        assertNull(ConnectionPool.tryBorrowConnection(new ConnectInfo(), queue));

        assertSame(replacement, queue.peek());
        assertFalse(pooledClosed.get());
        assertNotNull(pooled.getConnection());
        pooled.releaseInUse();
    }

    @Test
    void staleConnectionShouldBeValidatedBeforeBorrow() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger validationCalls = new AtomicInteger();
        ConnectInfo pooled = connectInfo(connection(false, closed, validationCalls));
        pooled.setLastAccessTime(new Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1)));
        LinkedBlockingQueue<ConnectInfo> queue = ConnectionPool.newConnectionQueue();
        queue.offer(pooled);

        assertNull(ConnectionPool.tryBorrowConnection(new ConnectInfo(), queue));

        assertEquals(1, validationCalls.get());
        assertTrue(closed.get());
        assertNull(pooled.getConnection());
    }

    @Test
    void concurrentReturnsShouldKeepQueueBoundedAndCloseOverflowConnections() throws Exception {
        int connectionCount = 32;
        LinkedBlockingQueue<ConnectInfo> queue = ConnectionPool.newConnectionQueue();
        List<ConnectInfo> connectInfos = new ArrayList<>(connectionCount);
        List<AtomicBoolean> closedConnections = new ArrayList<>(connectionCount);
        for (int i = 0; i < connectionCount; i++) {
            AtomicBoolean closed = new AtomicBoolean();
            closedConnections.add(closed);
            connectInfos.add(connectInfo(connection(true, closed)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(connectionCount);
        CountDownLatch ready = new CountDownLatch(connectionCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>(connectionCount);
            for (ConnectInfo connectInfo : connectInfos) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    ConnectionPool.offerOrClose(queue, connectInfo);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(2, queue.size());
            Set<ConnectInfo> retained = Collections.newSetFromMap(new IdentityHashMap<>());
            retained.addAll(queue);
            for (int i = 0; i < connectionCount; i++) {
                assertEquals(!retained.contains(connectInfos.get(i)), closedConnections.get(i).get());
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void consoleScopedPoolSeparatesConsoleKeysButDoesNotProvideSingleFixedSession() {
        ConnectInfo firstConsole = scopedConnectInfo(71L);
        ConnectInfo secondConsole = scopedConnectInfo(72L);
        assertNotEquals(firstConsole.getKey(), secondConsole.getKey());

        LinkedBlockingQueue<ConnectInfo> firstQueue =
                ConnectionPool.getOrCreateConnectionQueue(firstConsole.getDataSourceId(), firstConsole.getKey());
        LinkedBlockingQueue<ConnectInfo> secondQueue =
                ConnectionPool.getOrCreateConnectionQueue(secondConsole.getDataSourceId(), secondConsole.getKey());
        assertNotSame(firstQueue, secondQueue);

        AtomicBoolean firstClosed = new AtomicBoolean();
        AtomicBoolean secondClosed = new AtomicBoolean();
        ConnectInfo firstLease = scopedConnectInfo(71L, connection(true, firstClosed));
        ConnectInfo secondLease = scopedConnectInfo(71L, connection(true, secondClosed));

        ConnectionPool.offerOrClose(firstQueue, firstLease);
        ConnectionPool.offerOrClose(firstQueue, secondLease);

        assertEquals(2, firstQueue.size());
        assertTrue(secondQueue.isEmpty());
        assertFalse(firstClosed.get());
        assertFalse(secondClosed.get());
        ConnectionPool.removeConnection(firstConsole.getDataSourceId());
    }

    @Test
    void cleanupShouldCloseConnectionWhenDatasourceIsRemovedDuringValidation() throws Exception {
        long datasourceId = -1924L;
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch finishValidation = new CountDownLatch(1);
        ConnectInfo connectInfo = connectInfo(blockingValidationConnection(
                closed, validationStarted, finishValidation));
        connectInfo.setDataSourceId(datasourceId);
        connectInfo.updatePoolGeneration(ConnectionPool.currentGeneration(datasourceId));
        LinkedBlockingQueue<ConnectInfo> queue =
                ConnectionPool.getOrCreateConnectionQueue(datasourceId, connectInfo.getKey());
        assertTrue(queue.offer(connectInfo));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> cleanup = executor.submit(ConnectionPool::cleanupConnections);
        try {
            assertTrue(validationStarted.await(5, TimeUnit.SECONDS));
            ConnectionPool.removeConnection(datasourceId);
            finishValidation.countDown();
            cleanup.get(5, TimeUnit.SECONDS);

            assertTrue(closed.get());
            assertNull(connectInfo.getConnection());
        } finally {
            finishValidation.countDown();
            ConnectionPool.removeConnection(datasourceId);
            executor.shutdownNow();
        }
    }

    @Test
    void returnedLeaseShouldCloseWhenDatasourceWasRemovedAndPoolWasRecreated() {
        long datasourceId = -1925L;
        AtomicBoolean oldClosed = new AtomicBoolean();
        ConnectInfo pooled = connectInfo(connection(true, oldClosed));
        pooled.setDataSourceId(datasourceId);
        pooled.updatePoolGeneration(ConnectionPool.currentGeneration(datasourceId));
        LinkedBlockingQueue<ConnectInfo> oldQueue =
                ConnectionPool.getOrCreateConnectionQueue(datasourceId, pooled.getKey());
        assertTrue(oldQueue.offer(pooled));

        ConnectInfo oldLease = new ConnectInfo();
        oldLease.setDataSourceId(datasourceId);
        assertNotNull(ConnectionPool.getConnection(oldLease));

        ConnectionPool.removeConnection(datasourceId);

        AtomicBoolean replacementClosed = new AtomicBoolean();
        ConnectInfo replacement = connectInfo(connection(true, replacementClosed));
        replacement.setDataSourceId(datasourceId);
        LinkedBlockingQueue<ConnectInfo> replacementQueue =
                ConnectionPool.getOrCreateConnectionQueue(datasourceId, replacement.getKey());
        assertTrue(replacementQueue.offer(replacement));
        ConnectionPool.close(oldLease);

        assertTrue(oldClosed.get());
        assertNull(oldLease.getConnection());
        assertFalse(replacementClosed.get());
        assertSame(replacement, replacementQueue.peek());
        ConnectionPool.removeConnection(datasourceId);
        assertTrue(replacementClosed.get());
    }

    private static ConnectInfo connectInfo(Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setConnection(connection);
        connectInfo.setLastAccessTime(new Date());
        return connectInfo;
    }

    private static ConnectInfo scopedConnectInfo(Long consoleId) {
        return scopedConnectInfo(consoleId, null);
    }

    private static ConnectInfo scopedConnectInfo(Long consoleId, Connection connection) {
        ConnectInfo connectInfo = connectInfo(connection);
        connectInfo.setLoginUser("tester");
        connectInfo.setDataSourceId(-2634L);
        connectInfo.setDatabaseName("app");
        connectInfo.setSchemaName("public");
        connectInfo.setConsoleId(consoleId);
        connectInfo.updatePoolGeneration(ConnectionPool.currentGeneration(connectInfo.getDataSourceId()));
        return connectInfo;
    }

    private static Connection connection(boolean valid, AtomicBoolean closed) {
        return connection(valid, closed, new AtomicInteger());
    }

    private static Connection connection(boolean valid, AtomicBoolean closed, AtomicInteger validationCalls) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionPoolTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return closed.get();
                        case "isValid":
                            validationCalls.incrementAndGet();
                            return valid;
                        case "close":
                            closed.set(true);
                            return null;
                        case "toString":
                            return "TestConnection";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Connection blockingValidationConnection(AtomicBoolean closed,
                                                           CountDownLatch validationStarted,
                                                           CountDownLatch finishValidation) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionPoolTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isClosed":
                            return closed.get();
                        case "isValid":
                            validationStarted.countDown();
                            if (!finishValidation.await(5, TimeUnit.SECONDS)) {
                                throw new SQLException("Timed out waiting to finish validation");
                            }
                            return true;
                        case "close":
                            closed.set(true);
                            return null;
                        case "toString":
                            return "BlockingValidationConnection";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
