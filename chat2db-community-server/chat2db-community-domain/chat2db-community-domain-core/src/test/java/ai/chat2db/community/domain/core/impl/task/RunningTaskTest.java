package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RunningTaskTest {

    @Test
    void blockingJdbcCancellationDoesNotBlockTheCancellationRequest() throws Exception {
        RunningTask runningTask = new RunningTask(42L);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        FutureTask<Void> future = new FutureTask<>(() -> null);
        runningTask.setFuture(future);
        runningTask.registerCancelable(() -> {
            cancelStarted.countDown();
            releaseCancel.await();
        });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertTrue(runningTask.requestCancellation(true)));
            assertTrue(future.isCancelled());
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS));
            assertFalse(runningTask.requestCancellation(true));
        } finally {
            releaseCancel.countDown();
        }
    }

    @Test
    void preCancelledTaskCancelsNewStatementOnceWithoutBlockingRegistration() throws Exception {
        RunningTask runningTask = new RunningTask(42L);
        assertTrue(runningTask.requestCancellation(true));
        TaskExecutionContextImpl context = new TaskExecutionContextImpl(42L, runningTask, null, null);
        AtomicInteger cancellationCount = new AtomicInteger();
        AtomicReference<Thread> registrationThread = new AtomicReference<>();
        AtomicReference<Thread> cancellationThread = new AtomicReference<>();
        CountDownLatch cancelStarted = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class}, (proxy, method, args) -> {
                    if ("cancel".equals(method.getName())) {
                        cancellationCount.incrementAndGet();
                        cancellationThread.set(Thread.currentThread());
                        cancelStarted.countDown();
                        releaseCancel.await();
                    }
                    return null;
                });

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                registrationThread.set(Thread.currentThread());
                context.onStatementCreated(statement);
            });
            assertTrue(cancelStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, cancellationCount.get());
            assertNotSame(registrationThread.get(), cancellationThread.get());
        } finally {
            releaseCancel.countDown();
        }
    }

    @Test
    void concurrentCancellationAndRegistrationScheduleResourceOnce() throws Exception {
        List<Runnable> scheduledCancellations = new CopyOnWriteArrayList<>();
        RunningTask runningTask = new RunningTask(42L, scheduledCancellations::add);
        CountDownLatch futureCancellationStarted = new CountDownLatch(1);
        CountDownLatch releaseFutureCancellation = new CountDownLatch(1);
        FutureTask<Void> future = new FutureTask<>(() -> null) {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                futureCancellationStarted.countDown();
                try {
                    if (!releaseFutureCancellation.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to resume future cancellation");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        runningTask.setFuture(future);
        AtomicInteger cancellationCount = new AtomicInteger();
        FutureTask<Boolean> cancellationRequest = new FutureTask<>(() -> runningTask.requestCancellation(true));
        Thread cancellationThread = new Thread(cancellationRequest, "running-task-cancellation-test");
        cancellationThread.start();

        try {
            assertTrue(futureCancellationStarted.await(1, TimeUnit.SECONDS));
            runningTask.registerCancelable(cancellationCount::incrementAndGet);
        } finally {
            releaseFutureCancellation.countDown();
        }

        assertTrue(cancellationRequest.get(1, TimeUnit.SECONDS));
        assertEquals(1, scheduledCancellations.size());
        scheduledCancellations.get(0).run();
        assertEquals(1, cancellationCount.get());
    }
}
