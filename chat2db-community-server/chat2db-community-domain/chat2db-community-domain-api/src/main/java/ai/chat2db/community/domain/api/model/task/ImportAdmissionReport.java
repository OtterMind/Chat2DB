package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Result of the mandatory, read-only gate that runs before an import starts workers. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportAdmissionReport {

    /** PARALLEL_SAFE, PARALLEL_DEGRADED, or PARALLEL_FORBIDDEN. */
    private String verdict;

    private String requestedMode;

    private String effectiveMode;

    private boolean parallelAllowed;

    private String fileFormat;

    private long fileSizeBytes;

    private long dataRows;

    private boolean fullScan;

    private boolean relationshipRiskAccepted;

    private List<ImportAdmissionFinding> findings;
}
