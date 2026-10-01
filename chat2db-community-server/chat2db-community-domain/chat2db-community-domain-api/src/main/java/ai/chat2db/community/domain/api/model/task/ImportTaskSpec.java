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

    /** TABLE (legacy default), SCHEMA or DATABASE. */
    private String scope;

    /** Per-table sources for schema/database imports; absent for the legacy single-table contract. */
    private List<ImportTableSource> tableSources;

    /** Operator-supplied relationships that are not represented by database constraints. */
    private List<ImportTableDependency> logicalDependencies;

    /** TRUSTED or THIRD_PARTY; absent retains the legacy trusted-source behavior. */
    private String sourceKind;

    /** REJECT, DEFER_CONSTRAINTS or STAGING_TWO_PHASE. */
    private String cycleStrategy;

    private ImportStagingPolicy stagingPolicy;

    private ImportValidationOptions validationOptions;

    private ImportFinalizationOptions finalizationOptions;

    private ImportRollbackOptions rollbackOptions;

    private Integer performanceSamplePercent;

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
     * Optional behaviour overrides (encoding, delimiters, column mapping, error tolerance).
     */
    private ImportOptions options;

    /**
     * Execution mode; {@code null} resolves to {@code STANDARD}.
     */
    private String mode;

    /**
     * Explicit operator assertion required for parallel row imports. It covers relationship and
     * ordering semantics that metadata alone cannot prove (logical foreign keys, triggers and
     * application-level parent/child ID dependencies); it does not bypass file-format blockers.
     */
    private Boolean confirmedNoStrongRelations;
}

