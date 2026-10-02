package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One explainable rule result from the parallel-import admission gate. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportAdmissionFinding {

    private String code;

    /** BLOCKER or DEGRADATION. */
    private String severity;

    private String message;

    private String evidence;

    private String remediation;
}
