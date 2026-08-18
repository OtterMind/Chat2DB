package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

@Data
public class AgentRuntimeApprovalResult {
    private AgentRuntimeApproval approval;
    private AgentRuntimeLeaseStatus lease;
}
