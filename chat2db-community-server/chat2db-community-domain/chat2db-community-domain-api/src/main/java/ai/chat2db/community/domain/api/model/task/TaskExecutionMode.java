package ai.chat2db.community.domain.api.model.task;

import org.apache.commons.lang3.StringUtils;

/**
 * Execution mode of a bulk import/export task. {@code ULTRA_FAST} enables the parallel machinery
 * (keyset sharding, multi-worker batches, multi-row INSERT merging, adaptive tuning);
 * {@code STANDARD} is the conservative single-threaded path with fixed small batches. Absent or
 * unknown values resolve to {@code STANDARD} so older clients keep a well-defined behaviour.
 */
public final class TaskExecutionMode {

    public static final String ULTRA_FAST = "ULTRA_FAST";

    public static final String STANDARD = "STANDARD";

    private TaskExecutionMode() {
    }

    /** True only for an explicit {@code ULTRA_FAST}; anything else (null, blank, unknown) is standard. */
    public static boolean isUltraFast(String mode) {
        return ULTRA_FAST.equalsIgnoreCase(StringUtils.trimToEmpty(mode));
    }
}
