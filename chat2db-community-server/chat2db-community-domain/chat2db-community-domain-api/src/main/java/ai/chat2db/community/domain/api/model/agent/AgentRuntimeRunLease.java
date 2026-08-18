package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentRuntimeRunLease {

    private String runId;
    private String runtimeInstanceId;
    private Integer leaseAttempt;
    private String leaseTokenHash;
    private String taskTokenHash;
    private Date claimedAt;
    private Date leaseExpiresAt;
    private Date lastRenewedAt;
    private Date startedAt;
    private String runtimeExecutionId;
    private Date cancelRequestedAt;
    private Long lastEventSequence;
    private AgentRuntimeLeaseStateEnum state;
    private Date releasedAt;
    private String terminalEventId;
    private Long revision;
}
