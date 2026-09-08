package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-tuning row-batch size for bulk I/O. Producers report the wall time of each executed batch;
 * the sizer doubles the batch when execution comes back cheap and halves it when execution is
 * expensive, which keeps per-batch latency inside a band where the per-statement overhead is
 * amortized without building oversized memory structures or holding long-running batches. The
 * contract keeps the size inside [100, 100_000] rows so it stays sane under noisy measurements.
 */
public final class AdaptiveBatchSizer {

    private static final int MIN_BATCH = 100;

    private static final int MAX_BATCH = 100_000;

    private static final long FAST_NANOS = 4L * 1_000_000L;

    private static final long SLOW_NANOS = 40L * 1_000_000L;

    private final AtomicInteger batchSize;

    /** When {@code false} the sizer stays fixed at its initial size (standard mode). */
    private final boolean adaptive;

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
     * {@link #batchSize()} calls reflect the tuned size.
     */
    public void record(int rows, long nanos) {
        if (!adaptive || rows <= 0 || nanos <= 0) {
            return;
        }
        int current = batchSize.get();
        int next = nanos < FAST_NANOS ? current * 2
                : nanos > SLOW_NANOS ? current / 2
                : current;
        if (next != current) {
            batchSize.compareAndSet(current, clamp(next));
        }
    }

    private static int clamp(int value) {
        return Math.max(MIN_BATCH, Math.min(MAX_BATCH, value));
    }
}
