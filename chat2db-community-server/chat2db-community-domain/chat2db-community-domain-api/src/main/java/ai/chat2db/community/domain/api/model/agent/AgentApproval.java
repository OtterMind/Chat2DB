package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentApprovalDecisionEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentApprovalStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentApproval {
    private String id;
    private String proposalId;
    private String runId;
    private Integer proposalVersion;
    private String proposalHash;
    private AgentApprovalStatusEnum status;
    private String requestedBy;
    private Date requestedAt;
    private Long decidedBy;
    private Date decidedAt;
    private AgentApprovalDecisionEnum decision;
    private String reason;
    private Long revision;
}
