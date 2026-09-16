package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * Dynamic concurrency gate for parallel task I/O. Workers acquire a permit around each unit of
 * work; the permit count starts low and is tuned by a throughput observer in an AIMD style: every
 * time {@link #WINDOW_ROWS} rows of data have flowed through since the last evaluation, the gate
 * compares the throughput of the finished window with the previous one and grows by one permit on
 * improvement above 10%, or gives back a quarter of the permits on regression above 10%. The fan-out therefore
 * converges to the level the target system actually tolerates instead of a fixed guess, and it
 * backs off on its own when the source or the target becomes the bottleneck.
 *
 * <p>Tuning never throws into the task: every adjustment runs under its own guard, so an observer
 * failure degrades to keeping the current fan-out instead of failing the import. The
 * total permit count is tracked explicitly and hard-capped at {@code maxPermits}, even while
 * workers hold permits, so the fan-out can never exceed its configured ceiling, and tuning never
 * shrinks it past {@link #MIN_PERMITS}. Waiting workers keep checking task cancellation and
 * never execute without a permit.
 */
@Slf4j
public final class AdaptiveConcurrencyGate extends Semaphore {

    /**
     * Tuning window: the observer evaluates once this much data (rows) has flowed through since
     * the previous evaluation, independent of how many batches that took.
     */
    static final long WINDOW_ROWS = 80_000L;

    /**
     * Hard floor of the fan-out; a gate created below it can still grow, but tuning never shrinks
     * it past this bound (bounded by the configured max when that is smaller). One permit is the
     * contract floor of the fast mode, so the fan-out never reaches zero.
     */
    static final int MIN_PERMITS = 1;

    private final int maxPermits;

    private final int floor;

    /** Total permits in circulation; only the tuning paths change it, and never past maxPermits. */
    private final AtomicInteger totalPermits;

    private static final double GROW_MARGIN = 1.10D;

    private static final double SHRINK_MARGIN = 0.90D;

    private long windowRows;

    private long windowNanos;

    private double lastThroughput = -1.0D;

    private AdaptiveConcurrencyGate(int initialPermits, int maxPermits) {
        super(Math.max(1, Math.min(initialPermits, maxPermits)));
        this.maxPermits = Math.max(1, maxPermits);
        this.floor = Math.min(MIN_PERMITS, this.maxPermits);
        this.totalPermits = new AtomicInteger(Math.max(1, Math.min(initialPermits, maxPermits)));
    }

    public static AdaptiveConcurrencyGate create(int initialPermits, int maxPermits) {
        return new AdaptiveConcurrencyGate(initialPermits, maxPermits);
    }

    /**
     * Records one completed work unit ({@code rows} rows over {@code nanos} wall time); once the
     * observation window fills, the fan-out is retuned. Never throws into the caller.
     */
    public synchronized void record(long rows, long nanos) {
        if (rows <= 0 || nanos <= 0) {
            return;
        }
        windowRows += rows;
        windowNanos += nanos;
        if (windowRows < WINDOW_ROWS) {
            return;
        }
        tuneThroughput(windowRows, windowNanos);
        windowRows = 0L;
        windowNanos = 0L;
    }

    /** Waits for a permit while checking cancellation between bounded waits. */
    public void awaitPermit(Runnable cancellationChecker) throws InterruptedException {
        cancellationChecker.run();
        while (!tryAcquire(200L, TimeUnit.MILLISECONDS)) {
            cancellationChecker.run();
        }
    }

    private void tuneThroughput(long rows, long nanos) {
        try {
            double throughput = rows * 1_000_000.0D / Math.max(1L, nanos);
            if (lastThroughput > 0.0D) {
                if (throughput > lastThroughput * GROW_MARGIN) {
                    // Additive increase, capped by the hard total so growth cannot overshoot the
                    // configured ceiling even while workers hold permits.
                    if (totalPermits.get() < maxPermits) {
                        release();
                        totalPermits.incrementAndGet();
                    }
                } else if (throughput < lastThroughput * SHRINK_MARGIN && totalPermits.get() > floor) {
                    // Multiplicative decrease: a regression cuts fast, growth is careful so a
                    // lucky window cannot oversubscribe the target system.
                    int cut = Math.max(1, totalPermits.get() / 4);
                    int target = Math.max(floor, totalPermits.get() - cut);
                    while (totalPermits.get() > target) {
                        reducePermits(1);
                        totalPermits.decrementAndGet();
                    }
                }
            }
            lastThroughput = throughput;
        } catch (Throwable tuningFailure) {
            // Tuning must never break the task: keep the current fan-out and the next window.
            log.warn("Adaptive gate tuning failed; keeping the current fan-out", tuningFailure);
        }
    }

    int currentPermits() {
        return availablePermits();
    }

    /**
     * Permits currently in circulation; callers that own growable worker capacity compare it with
     * the number of live workers to decide whether more workers are needed.
     */
    public int totalPermits() {
        return totalPermits.get();
    }
}
