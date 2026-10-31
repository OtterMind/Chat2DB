package ai.chat2db.community.domain.api.model.task;

import java.util.Locale;

/**
 * How the parameterized import path reacts to a batch the database rejected.
 *
 * <p>{@link #ABORT} is the historical behavior: the failed batch is reported and the task fails.
 * {@link #SKIP_ERROR_ROWS} retries the rejected rows one by one, writes the still-failing rows to
 * the task's error artifact and keeps going with the next batch.
 */
public enum ImportFailurePolicy {

    /** Fails the task on the first rejected batch. */
    ABORT,

    /** Keeps the import running and records the rejected rows. */
    SKIP_ERROR_ROWS;

    /** Resolves a request value; {@code null} and blank keep the default {@link #ABORT}. */
    public static ImportFailurePolicy from(String value) {
        if (value == null || value.isBlank()) {
            return ABORT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported import failure policy: " + value, e);
        }
    }

    /** True when a rejected batch must be retried row by row instead of failing the task. */
    public boolean toleratesRowFailures() {
        return this == SKIP_ERROR_ROWS;
    }
}
