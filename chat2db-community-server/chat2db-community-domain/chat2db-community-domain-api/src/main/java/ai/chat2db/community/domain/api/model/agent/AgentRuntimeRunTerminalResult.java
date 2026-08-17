package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeLeaseStateEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentRuntimeRunTerminalResult {

    private String runId;
    private AgentRunStatusEnum runStatus;
    private Integer leaseAttempt;
    private Long leaseRevision;
    private AgentRuntimeLeaseStateEnum leaseState;
    private Date releasedAt;
}
