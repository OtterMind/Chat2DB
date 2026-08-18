package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentRuntimeLeaseStatus {

    private String runId;
    private Integer leaseAttempt;
    private Long leaseRevision;
    private Date leaseExpiresAt;
    private Boolean cancelRequested;
    private AgentRunStatusEnum runStatus;
    private Boolean approvalDecisionPending;
    private Boolean sqlContinuationAvailable;
    private AgentRuntimeLeaseStateEnum state;
    private Date releasedAt;
}
