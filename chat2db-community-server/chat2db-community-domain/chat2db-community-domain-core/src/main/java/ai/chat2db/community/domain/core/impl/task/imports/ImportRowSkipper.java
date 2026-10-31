package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;

/**
 * Drops the leading data rows of a resumed import.
 *
 * <p>Rows are counted after the format options (header, data range) selected them and before they
 * are mapped, so the count matches the row numbers the original run reported. The gate keeps the
 * source row numbers untouched, which is what lets a second resume keep counting from the same file
 * positions.
 */
public final class ImportRowSkipper {

    private long remaining;

    public ImportRowSkipper(long skipRows) {
        this.remaining = Math.max(0L, skipRows);
    }

    public ImportRowSkipper(ImportTaskSpec spec) {
        this(ImportResumability.resumeOffset(spec));
    }

    /** True when this data row belongs to the already processed prefix. */
    public boolean skipNext() {
        if (remaining <= 0L) {
            return false;
        }
        remaining--;
        return true;
    }
}
