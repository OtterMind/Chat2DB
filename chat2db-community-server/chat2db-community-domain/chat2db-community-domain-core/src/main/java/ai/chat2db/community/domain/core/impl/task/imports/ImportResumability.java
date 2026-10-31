package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.ImportDuplicatePolicy;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskType;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Which tasks can be continued from a row checkpoint.
 *
 * <p>Only data file imports of a format whose rows are read in file order qualify: a SQL script has
 * no meaningful row position, and an export has nothing to resume. The predicate also decides which
 * failed or cancelled tasks keep their staged source file and error artifact.
 */
public final class ImportResumability {

    private static final Set<String> RESUMABLE_FORMATS = Set.of(
            TaskFileFormat.CSV.name(), TaskFileFormat.XLS.name(),
            TaskFileFormat.XLSX.name(), TaskFileFormat.JSON.name());

    private ImportResumability() {
    }

    /** True when the spec is a data file import whose rows can be skipped by position. */
    public static boolean isResumable(TaskSpec spec) {
        if (!(spec instanceof ImportTaskSpec importSpec)) {
            return false;
        }
        return TaskType.DATA_FILE_IMPORT.name().equals(importSpec.getTaskType())
                && StringUtils.isNotBlank(importSpec.getSourceFile())
                && RESUMABLE_FORMATS.contains(format(importSpec));
    }

    /** Source rows a resumed task skips; 0 for an ordinary first run. */
    public static long resumeOffset(ImportTaskSpec spec) {
        Long resumeFromRow = spec.getResumeFromRow();
        return resumeFromRow == null || resumeFromRow < 0L ? 0L : resumeFromRow;
    }

    /** Duplicate policy of the spec, normalized to its default. */
    public static ImportDuplicatePolicy duplicatePolicy(ImportTaskSpec spec) {
        return ImportDuplicatePolicy.from(spec.getDuplicatePolicy());
    }

    private static String format(ImportTaskSpec spec) {
        return spec.getFormat() == null ? "" : spec.getFormat().trim().toUpperCase(Locale.ROOT);
    }
}
