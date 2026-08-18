package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentRuntimeApprovalAckRequest extends AgentRuntimeLeaseRenewRequest {
    private String approvalId;
    private Long expectedApprovalRevision;
}
