package ai.chat2db.community.domain.api.model.result;

import ai.chat2db.community.domain.api.enums.agent.AgentRiskLevelEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentSqlPermitDecisionEnum;
import lombok.Data;

/**
 * SQL tool result with typed approval metadata for external Agent runtimes.
 */
@Data
public class AiExecuteSqlResult {

    private String content;

    private AgentSqlPermitDecisionEnum decision;

    private String approvalId;

    private Integer proposalVersion;

    private AgentRiskLevelEnum riskLevel;
}
