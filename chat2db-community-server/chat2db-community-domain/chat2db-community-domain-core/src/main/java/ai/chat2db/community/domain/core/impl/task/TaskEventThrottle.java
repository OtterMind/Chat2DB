package ai.chat2db.community.domain.core.impl.task;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Rate limits repeated task events that share an event code. A throttled event is dropped, not
 * delayed: the code stays part of the contract, but the event log receives at most one entry per
 * code and interval. Instances are safe to share between the threads that produce events for one
 * task.
 */
public final class TaskEventThrottle {

    public static final long DEFAULT_MIN_INTERVAL_MILLIS = 1000L;

    private final long minIntervalNanos;

    private final LongSupplier nanoClock;

    private final ConcurrentMap<String, Long> lastEmittedNanos = new ConcurrentHashMap<>();

    public TaskEventThrottle() {
        this(DEFAULT_MIN_INTERVAL_MILLIS, System::nanoTime);
    }

    TaskEventThrottle(long minIntervalMillis, LongSupplier nanoClock) {
        if (minIntervalMillis <= 0L) {
            throw new IllegalArgumentException("minIntervalMillis must be positive");
        }
        if (nanoClock == null) {
            throw new IllegalArgumentException("nanoClock is required");
        }
        this.minIntervalNanos = minIntervalMillis * 1_000_000L;
        this.nanoClock = nanoClock;
    }

    /** Returns true when an event with this code may be emitted now. */
    public boolean tryAcquire(String code) {
        String key = code == null ? "" : code;
        while (true) {
            Long lastEmitted = lastEmittedNanos.get(key);
            long now = nanoClock.getAsLong();
            if (lastEmitted == null) {
                if (lastEmittedNanos.putIfAbsent(key, now) == null) {
                    return true;
                }
                continue;
            }
            if (now - lastEmitted < minIntervalNanos) {
                return false;
            }
            if (lastEmittedNanos.replace(key, lastEmitted, now)) {
                return true;
            }
        }
    }
}
