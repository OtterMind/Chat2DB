package ai.chat2db.community.domain.api.model.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DbExplainResult {

    private String requestId;

    private String mode;

    private String normalizedSql;

    private String rawPlan;

    private DbExplainCapability capability;
}
