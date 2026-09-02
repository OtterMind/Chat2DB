package ai.chat2db.community.domain.core.impl.task.export;

import java.util.function.LongConsumer;

/**
 * Global row-and-byte rate limiter for exports, in the spirit of DataX flow control: each
 * dimension (rows, bytes) keeps the earliest time the next unit may be released, and a call waits
 * for the larger of the two delays. Zero or negative disables that dimension.
 *
 * <p>Deliberately process-global: the point is protecting the target database and desktop machine
 * from the sum of all running exports, not one task in isolation.
 */
public final class ExportRateLimiter {

    private static final ExportRateLimiter GLOBAL = new ExportRateLimiter(System::nanoTime, sleepMillis());

    private final java.util.function.LongSupplier nanoTime;

    private final LongConsumer sleep;

    private volatile long rowsPerSecond;

    private volatile long bytesPerSecond;

    private long nextRowNanos;

    private long nextByteNanos;

    ExportRateLimiter(java.util.function.LongSupplier nanoTime, LongConsumer sleep) {
        this.nanoTime = nanoTime;
        this.sleep = sleep;
    }

    private static LongConsumer sleepMillis() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the export rate limit", e);
            }
        };
    }

    public static ExportRateLimiter global() {
        return GLOBAL;
    }

    public static void configure(long rowsPerSecond, long bytesPerSecond) {
        GLOBAL.configureLimits(rowsPerSecond, bytesPerSecond);
    }

    void configureLimits(long rowsPerSecond, long bytesPerSecond) {
        this.rowsPerSecond = rowsPerSecond;
        this.bytesPerSecond = bytesPerSecond;
    }

    /**
     * Blocks until {@code rows} rows and {@code bytes} bytes may be released.
     */
    public synchronized void acquire(int rows, long bytes) {
        long now = nanoTime.getAsLong();
        long delay = 0L;
        if (rowsPerSecond > 0 && rows > 0) {
            long cost = (long) rows * 1_000_000_000L / rowsPerSecond;
            nextRowNanos = Math.max(now, nextRowNanos == 0L ? now : nextRowNanos);
            nextRowNanos += cost;
            delay = Math.max(delay, nextRowNanos - now);
        }
        if (bytesPerSecond > 0 && bytes > 0) {
            long cost = bytes * 1_000_000_000L / bytesPerSecond;
            nextByteNanos = Math.max(now, nextByteNanos == 0L ? now : nextByteNanos);
            nextByteNanos += cost;
            delay = Math.max(delay, nextByteNanos - now);
        }
        if (delay > 0) {
            sleep.accept((delay + 999_999L) / 1_000_000L);
        }
    }
}
