package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-tuning row-batch size for bulk I/O. Producers report the wall time of each executed batch;
 * the sizer hill-climbs on the measured throughput (rows per second): a batch that beats the
 * running reference by {@link #GROW_MARGIN} doubles the size, one that falls short by
 * {@link #SHRINK_MARGIN} halves it. Sizes therefore follow what the machine and the target
 * database actually sustain, bounded by {@link #MAX_BATCH}. {@link #MIN_BATCH} rows keeps a batch
 * worth sending even on the slowest target.
 */
public final class AdaptiveBatchSizer {

    /** Lowest batch the tuner will settle on (1 thread x 100 rows contract floor). */
    private static final int MIN_BATCH = 100;

    static final int MAX_BATCH = 50_000;

    private static final double GROW_MARGIN = 1.10D;

    private static final double SHRINK_MARGIN = 0.90D;

    /** Smoothing of the reference throughput so one noisy batch cannot flip the direction. */
    private static final double REFERENCE_ALPHA = 0.5D;

    private final AtomicInteger batchSize;

    private double referenceThroughput = -1.0D;

    public AdaptiveBatchSizer(int initialBatch) {
        this.batchSize = new AtomicInteger(clamp(initialBatch));
    }

    public int batchSize() {
        return batchSize.get();
    }

    /**
     * Reports one executed batch of {@code rows} rows that took {@code nanos} wall time; later
     * {@link #batchSize()} calls reflect the tuned size.
     */
    public synchronized void record(int rows, long nanos) {
        if (rows <= 0 || nanos <= 0) {
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

    private int clamp(long value) {
        return (int) Math.max(MIN_BATCH, Math.min(MAX_BATCH, value));
    }
}
