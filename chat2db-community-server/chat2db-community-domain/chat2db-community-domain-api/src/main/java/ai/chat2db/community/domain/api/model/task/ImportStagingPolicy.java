package ai.chat2db.community.domain.api.model.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Requested staging behavior; execution support is capability-gated by the importer. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStagingPolicy {

    private Boolean enabled;

    private Boolean allVarchar;

    private Boolean twoPhase;
}
