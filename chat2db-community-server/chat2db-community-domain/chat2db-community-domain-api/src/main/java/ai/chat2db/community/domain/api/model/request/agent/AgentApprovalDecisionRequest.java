package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import lombok.Data;

@Data
public class AgentApprovalDecisionRequest {
    private String approvalId;
    private Long expectedRevision;
    private AgentApprovalDecisionEnum decision;
    private String reason;
    private Long decidedBy;
}
