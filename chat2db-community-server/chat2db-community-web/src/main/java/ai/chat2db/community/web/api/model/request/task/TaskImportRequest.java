package ai.chat2db.community.web.api.model.request.task;

import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportFinalizationOptions;
import ai.chat2db.community.domain.api.model.task.ImportRollbackOptions;
import ai.chat2db.community.domain.api.model.task.ImportStagingPolicy;
import ai.chat2db.community.domain.api.model.task.ImportTableDependency;
import ai.chat2db.community.domain.api.model.task.ImportValidationOptions;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class TaskImportRequest extends DataSourceBaseRequest {

    @Size(max = 128)
    private String clientSubmissionId;

    private String taskType;

    private String taskName;

    private String tableName;

    /** TABLE (legacy default), SCHEMA or DATABASE. */
    private String scope;

    private List<TaskImportTableSourceRequest> tableSources;

    private List<ImportTableDependency> logicalDependencies;

    private String sourceKind;

    private String cycleStrategy;

    private ImportStagingPolicy stagingPolicy;

    private ImportValidationOptions validationOptions;

    private ImportFinalizationOptions finalizationOptions;

    private ImportRollbackOptions rollbackOptions;

    private Integer performanceSamplePercent;

    private String sourceFile;

    private String fileId;

    private String displayFileName;

    private String format;

    private String dataTimeFormat;

    private ImportOptions options;

    private ai.chat2db.community.domain.api.model.task.UnmappedTargetStrategy unmappedTarget;

    /** Execution mode: ULTRA_FAST or STANDARD (default when absent). */
    private String mode;

    private Boolean confirmedNoStrongRelations;
}
