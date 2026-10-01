package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-tuning row-batch size for bulk I/O. Producers report the wall time of each executed batch;
 * the sizer hill-climbs on the measured throughput (rows per second) against a smoothed reference:
 * a batch that beats the reference by {@link #GROW_MARGIN} doubles the size, one that falls short
 * by {@link #SHRINK_MARGIN} halves it. Measuring throughput rather than wall time is what makes
 * one sizer correct on a loopback target and a remote one alike, and the exponential moving
 * average stops a single slow batch from flipping the direction. Sizes stay inside
 * {@code [MIN_BATCH, MAX_BATCH]} so they remain sane under noisy measurements.
 */
public final class AdaptiveBatchSizer {

    /** Lowest batch the tuner will settle on; one row is still worth sending. */
    private static final int MIN_BATCH = 100;

    private static final int MAX_BATCH = 100_000;

    private static final double GROW_MARGIN = 1.10D;

    private static final double SHRINK_MARGIN = 0.90D;

    /** Smoothing of the reference throughput so one noisy batch cannot flip the direction. */
    private static final double REFERENCE_ALPHA = 0.5D;

    private final AtomicInteger batchSize;

    /** When {@code false} the sizer stays fixed at its initial size (standard mode). */
    private final boolean adaptive;

    private double referenceThroughput = -1.0D;

    public AdaptiveBatchSizer(int initialBatch) {
        this(initialBatch, true);
    }

    public AdaptiveBatchSizer(int initialBatch, boolean adaptive) {
        this.batchSize = new AtomicInteger(clamp(initialBatch));
        this.adaptive = adaptive;
    }

    public int batchSize() {
        return batchSize.get();
    }

    /**
     * Reports one executed batch of {@code rows} rows that took {@code nanos} wall time; later
     * {@link #batchSize()} calls reflect the tuned size. Callers report from worker threads, so
     * the reference is only touched under the instance lock.
     */
    public synchronized void record(int rows, long nanos) {
        if (!adaptive || rows <= 0 || nanos <= 0) {
            return;
        }
        double throughput = rows * 1_000_000_000.0D / nanos;
        int current = batchSize.get();
        if (referenceThroughput > 0.0D) {
            if (throughput > referenceThroughput * GROW_MARGIN) {
                batchSize.set(clamp((long) current * 2));
            } else if (throughput < referenceThroughput * SHRINK_MARGIN) {
                batchSize.set(clamp(current / 2));
            }
        }
        referenceThroughput = referenceThroughput <= 0.0D
                ? throughput
                : REFERENCE_ALPHA * throughput + (1.0D - REFERENCE_ALPHA) * referenceThroughput;
    }

    /** The smoothed reference in rows per second, or {@code -1} before the first report. */
    synchronized double referenceRowsPerSecond() {
        return referenceThroughput;
    }

    private static int clamp(long value) {
        return (int) Math.max(MIN_BATCH, Math.min(MAX_BATCH, value));
    }
}