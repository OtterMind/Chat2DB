package ai.chat2db.community.domain.core.impl.task.imports;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded deadlock retry policy shared by manifest import workers. */
final class ImportShardRetryPolicy {

    static final int DEFAULT_MAX_RETRIES = 5;
    private static final long DEFAULT_BASE_DELAY_MILLIS = 200L;
    private static final long DEFAULT_MAX_DELAY_MILLIS = 5_000L;

    private final int maxRetries;
    private final long baseDelayMillis;
    private final long maxDelayMillis;
    private final Sleeper sleeper;
    private final Jitter jitter;

    ImportShardRetryPolicy() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_BASE_DELAY_MILLIS, DEFAULT_MAX_DELAY_MILLIS,
                Thread::sleep, bound -> ThreadLocalRandom.current().nextLong(bound + 1L));
    }

    ImportShardRetryPolicy(int maxRetries, long baseDelayMillis, long maxDelayMillis,
            Sleeper sleeper, Jitter jitter) {
        if (maxRetries < 0 || baseDelayMillis < 0L || maxDelayMillis < baseDelayMillis
                || sleeper == null || jitter == null) {
            throw new IllegalArgumentException("Deadlock retry limits and timing functions are invalid");
        }
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    <T> T execute(Attempt<T> attempt) throws Exception {
        int retries = 0;
        while (true) {
            try {
                return attempt.run();
            } catch (Exception failure) {
                if (!isDeadlock(failure) || retries >= maxRetries) {
                    throw failure;
                }
                long exponential = Math.min(maxDelayMillis,
                        saturatingMultiply(baseDelayMillis, 1L << Math.min(retries, 30)));
                long jitterRange = exponential / 4L;
                sleeper.sleep(Math.min(maxDelayMillis, exponential + jitter.next(jitterRange)));
                retries++;
            }
        }
    }

    static boolean isDeadlock(Throwable failure) {
        if (ImportManifestScheduler.isCommitOutcomeUnknown(failure)) {
            return false;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || "40P01".equalsIgnoreCase(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private long saturatingMultiply(long value, long factor) {
        if (value == 0L || factor <= Long.MAX_VALUE / value) {
            return value * factor;
        }
        return Long.MAX_VALUE;
    }

    @FunctionalInterface
    interface Attempt<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    interface Jitter {
        long next(long inclusiveUpperBound);
    }
}
