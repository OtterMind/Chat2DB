package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEventThrottleTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    void emitsTheFirstEventPerCodeAndSuppressesRepetitionsWithinTheInterval() {
        TaskEventThrottle throttle = new TaskEventThrottle(1000L, clock::get);

        assertTrue(throttle.tryAcquire("BATCH_EXECUTED"));
        assertTrue(throttle.tryAcquire("ROWS_EXPORTED"), "every code has its own budget");
        assertFalse(throttle.tryAcquire("BATCH_EXECUTED"));

        clock.addAndGet(999_999_999L);
        assertFalse(throttle.tryAcquire("BATCH_EXECUTED"));

        clock.addAndGet(1L);
        assertTrue(throttle.tryAcquire("BATCH_EXECUTED"), "the interval boundary emits again");
        assertFalse(throttle.tryAcquire("BATCH_EXECUTED"));
    }

    @Test
    void concurrentCallersEmitOnlyOneEventPerInterval() throws Exception {
        TaskEventThrottle throttle = new TaskEventThrottle(60_000L, System::nanoTime);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger emitted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int call = 0; call < 100; call++) {
                        if (throttle.tryAcquire("BATCH_EXECUTED")) {
                            emitted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, emitted.get());
    }
}
