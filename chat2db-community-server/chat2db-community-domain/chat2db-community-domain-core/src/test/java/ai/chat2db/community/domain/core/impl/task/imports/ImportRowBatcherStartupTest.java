package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.core.impl.task.AdaptiveConcurrencyGate;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImportRowBatcherStartupTest {
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final AtomicInteger statements = new AtomicInteger();
    private final TaskExecutionContext context = (TaskExecutionContext) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TaskExecutionContext.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "taskId": return 2908L;
                    case "cancelResources": cancellations.incrementAndGet(); break;
                    case "onStatementCreated": statements.incrementAndGet(); break;
                    default: break;
                }
                return null;
            });

    @BeforeEach
    void setUp() {
        ConnectInfo connection = new ConnectInfo();
        connection.setUrl("jdbc:unused:startup-test");
        connection.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        Thread.interrupted();
        // Also release leaked workers when running this regression test against the old code.
        for (Thread worker : workers) worker.interrupt();
        for (Thread worker : workers) worker.join(5_000);
        Chat2DBContext.removeContext();
    }

    @Test
    void partialStartupErrorWaitsForExistingWorkerAndPreservesInterruption() throws Exception {
        assertPartialStartupFailure(new OutOfMemoryError("simulated native thread creation failure"));
    }

    @Test
    void partialStartupRejectionStillCleansUp() throws Exception {
        assertPartialStartupFailure(new RejectedExecutionException("simulated startup rejection"));
    }

    private void assertPartialStartupFailure(Throwable failure) throws Exception {
        ThreadFactory factory = failingFactory(2, failure, true);
        Throwable actual = assertThrows(failure.getClass(), () -> new ImportRowBatcher(context, 4, factory));
        assertSame(failure, actual);
        assertTrue(Thread.interrupted(), "cleanup must restore the caller's interrupt flag");
        assertEquals(1, workers.size());
        assertWorkersExited();
        assertEquals(0, statements.get());
    }

    @Test
    void expansionErrorDuringAcceptAbortsPendingWrites() throws Exception {
        assertExpansionFailure(true);
    }

    @Test
    void expansionErrorDuringFlushAbortsPendingWrites() throws Exception {
        assertExpansionFailure(false);
    }

    private void assertExpansionFailure(boolean duringAccept) throws Exception {
        OutOfMemoryError failure = new OutOfMemoryError("simulated worker expansion failure");
        ImportRowBatcher batcher = new ImportRowBatcher(context, 6, failingFactory(5, failure, false));
        try {
            batcher.accept(1, "INSERT INTO unused VALUES (1)");
            // Drive the existing tuning input deterministically, without timing real database writes.
            var gateField = ImportRowBatcher.class.getDeclaredField("gate");
            gateField.setAccessible(true);
            AdaptiveConcurrencyGate gate = (AdaptiveConcurrencyGate) gateField.get(batcher);
            gate.record(80_000, 2_000_000);
            gate.record(80_000, 1_000_000);
            assertEquals(5, gate.totalPermits());

            assertSame(failure, assertThrows(OutOfMemoryError.class, () -> {
                if (duringAccept) {
                    // The text limit flushes the pending batch before adding this row.
                    batcher.accept(2, "x".repeat(2 * 1024 * 1024));
                } else {
                    batcher.flush();
                }
            }));
            assertEquals(1, cancellations.get(), "the failure must abort, not leave close() on the success path");
        } finally {
            batcher.close();
        }
        assertEquals(4, workers.size());
        assertWorkersExited();
        assertEquals(0, statements.get(), "failed expansion must not execute any pending SQL");
    }

    private ThreadFactory failingFactory(int failAt, Throwable failure, boolean interruptCaller) {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        return runnable -> {
            if (attempts.incrementAndGet() == failAt) {
                try {
                    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                if (interruptCaller) Thread.currentThread().interrupt();
                if (failure instanceof Error error) throw error;
                throw (RuntimeException) failure;
            }
            Thread worker = new Thread(() -> {
                firstStarted.countDown();
                runnable.run();
            });
            worker.setDaemon(true);
            workers.add(worker);
            return worker;
        };
    }

    private void assertWorkersExited() throws InterruptedException {
        for (Thread worker : workers) {
            worker.join(1_000);
            assertFalse(worker.isAlive(), "a previously started worker leaked after thread creation failed");
        }
    }
}
