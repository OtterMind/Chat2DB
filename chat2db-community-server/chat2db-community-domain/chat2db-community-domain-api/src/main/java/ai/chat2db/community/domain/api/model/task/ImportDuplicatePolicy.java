package ai.chat2db.community.domain.api.model.task;

import java.util.Locale;

/**
 * How the parameterized import path reacts to rows whose key already exists in the target table.
 *
 * <p>The policy is applied to the generated INSERT template, so it works at batch granularity
 * without a pre-check query. {@link #INSERT} is the historical behavior and stays valid on every
 * dialect; {@link #IGNORE} is only generated for dialects whose INSERT syntax supports it, and an
 * unsupported dialect is rejected instead of silently degrading to a plain INSERT.
 */
public enum ImportDuplicatePolicy {

    /** Plain INSERT: a duplicate key fails the batch. */
    INSERT,

    /** Skips existing rows: MySQL-family {@code INSERT IGNORE} or PostgreSQL {@code ON CONFLICT DO NOTHING}. */
    IGNORE;

    /** Resolves a request value; {@code null} and blank keep the default {@link #INSERT}. */
    public static ImportDuplicatePolicy from(String value) {
        if (value == null || value.isBlank()) {
            return INSERT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported import duplicate policy: " + value, e);
        }
    }
}
