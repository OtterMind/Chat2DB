package ai.chat2db.community.domain.api.model.task;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * Execution mode of a bulk import/export task.
 *
 * <p>{@code ULTRA_FAST}, and its shorter alias {@code FAST}, enable the parallel machinery
 * (keyset sharding, multi-worker batches, multi-row INSERT merging, adaptive tuning);
 * {@code STANDARD} is the conservative single-threaded path with fixed small batches. Absent or
 * unknown values resolve to {@code STANDARD} so older clients keep a well-defined behaviour.
 *
 * <p>Modes stay plain strings on the wire so a persisted task keeps the value it was submitted
 * with; {@link #names()} is the authoritative list of accepted values.
 */
public final class TaskExecutionMode {

    public static final String ULTRA_FAST = "ULTRA_FAST";

    /** Shorter alias for the same parallel behaviour, accepted from clients that send {@code FAST}. */
    public static final String FAST = "FAST";

    public static final String STANDARD = "STANDARD";

    private TaskExecutionMode() {
    }

    /** Every mode a client may submit, in the order the request contract documents them. */
    public static List<String> names() {
        return List.of(STANDARD, FAST, ULTRA_FAST);
    }

    /** True only for an explicit parallel request; anything else (null, blank, unknown) is standard. */
    public static boolean isUltraFast(String mode) {
        String normalized = StringUtils.trimToEmpty(mode).toUpperCase(Locale.ROOT);
        return ULTRA_FAST.equals(normalized) || FAST.equals(normalized);
    }

    /** Alias of {@link #isUltraFast(String)} kept for callers written against the short name. */
    public static boolean isFast(String mode) {
        return isUltraFast(mode);
    }
}
