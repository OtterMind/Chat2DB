package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RunningTaskTest {

    @Test
    void failedInputCleanupRemainsRegisteredForRetry() {
        AtomicInteger attempts = new AtomicInteger();
        RunningTask runningTask = new RunningTask(43L, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("locked");
            }
        });

        runningTask.cleanupInput();
        runningTask.cleanupInput();
        runningTask.cleanupInput();

        assertEquals(2, attempts.get());
    }

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
}
