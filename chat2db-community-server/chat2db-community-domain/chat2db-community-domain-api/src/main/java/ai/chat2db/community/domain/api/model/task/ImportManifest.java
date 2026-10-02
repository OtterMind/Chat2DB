package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Immutable-by-convention execution contract produced before preprocessing starts. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportManifest {

    private int schemaVersion;

    private Long taskId;

    private ImportPlanMode mode;

    private String admissionVerdict;

    private String sourceFingerprint;

    private long totalEstimatedRows;

    /** Full SCC-compressed dependency plan used to build and resume this manifest. */
    private ImportDependencyPlan dependencyPlan;

    private List<ImportTableDependency> dependencies;

    private List<ImportManifestShard> shards;

    /** Stable SHA-256 over the semantic plan, used to reject mismatched resume state. */
    private String manifestFingerprint;
}
