package ai.chat2db.community.domain.core.impl.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * AIMD tuning of the concurrency gate: permits grow on throughput improvement, shrink fast on
 * regression, and stay hard-capped at the configured max even while workers hold permits. Tuning windows are row-based: one
 * observation with at least {@link AdaptiveConcurrencyGate#WINDOW_ROWS} rows triggers one
 * evaluation, so successive windows model faster execution with smaller wall times.
 */
class AdaptiveConcurrencyGateTest {

    private static final long MILLI = 1_000_000L;

    /** Feeds one full tuning window executed in {@code millis} (rows are the window size). */
    private void tune(AdaptiveConcurrencyGate gate, long millis) {
        gate.record(AdaptiveConcurrencyGate.WINDOW_ROWS, millis * MILLI);
    }

    @Test
    void growsOnImprovementUpToTheCap() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        assertEquals(2, gate.currentPermits());
        tune(gate, 10); // first window only establishes the baseline
        assertEquals(2, gate.currentPermits());
        tune(gate, 5);
        assertEquals(3, gate.currentPermits());
        tune(gate, 2);
        assertEquals(4, gate.currentPermits());
        tune(gate, 1);
        assertEquals(4, gate.currentPermits(), "permits must never exceed the configured max");
    }

    @Test
    void growsFromBelowTheFloor() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 4);
        assertEquals(1, gate.currentPermits());
        tune(gate, 10); // baseline
        tune(gate, 5);
        assertEquals(2, gate.currentPermits());
        tune(gate, 2);
        assertEquals(3, gate.currentPermits());
        tune(gate, 1);
        assertEquals(4, gate.currentPermits());
    }

    @Test
    void hardCapHoldsEvenWhileWorkersHoldPermits() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        gate.tryAcquire();
        gate.tryAcquire(); // both initial permits are held by workers now
        assertEquals(0, gate.currentPermits());
        tune(gate, 10); // baseline
        tune(gate, 5);
        tune(gate, 2);
        tune(gate, 1);
        gate.release();
        gate.release();
        // The old available-permits-based guard let the total drift one past the max; the hard
        // total cap must keep it at exactly the configured ceiling.
        assertEquals(4, gate.currentPermits());
    }

    @Test
    void shrinksFastOnRegressionAndStaysAtTheFloor() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(4, 4);
        tune(gate, 2); // baseline (fast windows: high throughput)
        tune(gate, 10); // regression: a quarter of 4 permits cut
        assertEquals(3, gate.currentPermits());
        tune(gate, 20); // regression: at least one permit cut
        assertEquals(2, gate.currentPermits());
        tune(gate, 40);
        tune(gate, 80);
        tune(gate, 100);
        assertEquals(1, gate.currentPermits(), "the fan-out must never drop below the floor");
    }

    @Test
    void keepsAStableThroughputFlat() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        tune(gate, 10); // baseline
        tune(gate, 10); // identical throughput: neither grow nor cut
        assertEquals(2, gate.currentPermits());
    }

    @Test
    void ignoresThroughputChangesWithinTenPercentIncludingBoundaries() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        gate.record(100_000, 100 * MILLI);
        gate.record(110_000, 100 * MILLI);
        assertEquals(2, gate.totalPermits());
        gate.record(100_000, 100 * MILLI);
        gate.record(90_000, 100 * MILLI);
        assertEquals(2, gate.totalPermits());
        gate.record(100_000, 100 * MILLI);
        assertEquals(3, gate.totalPermits());
    }

    @Test
    void concurrentSamplesWithEqualEfficiencyDoNotChangePermits() throws Exception {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        var executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int worker = 1; worker <= 4; worker++) {
                long rows = worker * 10_000L;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int sample = 0; sample < 2_000; sample++) {
                        gate.record(rows, rows * MILLI);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(2, gate.totalPermits());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ignoresInvalidObservations() {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(2, 4);
        gate.record(0, MILLI);
        gate.record(100, 0L);
        assertEquals(2, gate.currentPermits());
    }

    @Test
    void repeatedTimeoutsNeverLetWorkProceedWithoutAPermit() throws Exception {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 1);
        gate.acquire();
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch timedOutTwice = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        try {
            var waiting = executor.submit(() -> {
                gate.awaitPermit(() -> {
                    if (checks.incrementAndGet() >= 3) {
                        timedOutTwice.countDown();
                    }
                });
                return null;
            });

            assertTrue(timedOutTwice.await(5, TimeUnit.SECONDS));
            assertFalse(waiting.isDone(), "timeouts must not bypass the concurrency limit");
            assertEquals(0, gate.currentPermits());

            gate.release();
            waiting.get(5, TimeUnit.SECONDS);
            assertEquals(0, gate.currentPermits(), "proceeding work owns the released permit");
        } finally {
            executor.shutdownNow();
            gate.release();
        }
    }

    @Test
    void taskCancellationStopsWaitingWithoutConsumingAPermit() throws Exception {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 1);
        gate.acquire();
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            var waiting = executor.submit(() -> {
                gate.awaitPermit(() -> {
                    started.countDown();
                    if (cancelled.get()) {
                        throw new CancellationException("task cancelled");
                    }
                });
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> waiting.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertEquals(0, gate.currentPermits());
        } finally {
            executor.shutdownNow();
            gate.release();
        }
    }

    @Test
    void threadInterruptionStopsWaitingWithoutConsumingAPermit() throws Exception {
        AdaptiveConcurrencyGate gate = AdaptiveConcurrencyGate.create(1, 1);
        gate.acquire();
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        try {
            var waiting = executor.submit(() -> {
                try {
                    gate.awaitPermit(started::countDown);
                    return null;
                } finally {
                    exited.countDown();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(waiting.cancel(true));

            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertEquals(0, gate.currentPermits());
        } finally {
            executor.shutdownNow();
            gate.release();
        }
    }
}
