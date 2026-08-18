package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentSqlPermitDecisionEnum;
import lombok.Data;

@Data
public class AgentSqlExecutionPermit {
    private AgentSqlPermitDecisionEnum decision;
    private AgentSqlProposal proposal;
    private AgentApproval approval;
    private AgentToolAttempt attempt;
    private String replayResult;
    private String message;
}
