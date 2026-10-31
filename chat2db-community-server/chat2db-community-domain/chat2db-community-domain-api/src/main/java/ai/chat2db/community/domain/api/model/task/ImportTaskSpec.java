package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportTaskSpec implements TaskSpec {

    private String taskType;

    private String taskName;

    private TaskTargetSnapshot target;

    private String sourceFile;

    /** Opaque ID of a server-staged source file, when the import originated from preview. */
    private String importFileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private CsvOptions csvOptions;

    private ExcelOptions excelOptions;

    private JsonOptions jsonOptions;

    private SqlImportOptions sqlImportOptions;

    /** Optional mapping supplied by the import-preview workflow. */
    private List<ImportColumnMapping> columnMappings;

    private UnmappedTargetStrategy unmappedTarget;

    /**
     * Execution mode; {@code null} resolves to {@code STANDARD}.
     */
    private String mode;

    /**
     * Duplicate-key handling of the parameterized insert; {@code null} resolves to {@code INSERT}.
     */
    private String duplicatePolicy;

    /**
     * Rejected-batch handling; {@code null} resolves to {@code ABORT}.
     */
    private String failurePolicy;

    /**
     * Source rows already stored by a previous run of the same source file, set only when a task is
     * created from another task's checkpoint. Rows are skipped by position, so the value is only
     * meaningful while the source file is unchanged.
     */
    private Long resumeFromRow;

    /** Stable lineage for presenting continuation attempts as one task without losing audit logs. */
    private Long resumeRootTaskId;
    private Long resumeParentTaskId;
    private Integer resumeAttempt;
    private String resumeRootTaskName;

}
