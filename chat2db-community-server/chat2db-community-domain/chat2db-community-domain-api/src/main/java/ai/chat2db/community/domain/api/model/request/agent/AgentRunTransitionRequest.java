package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatusEnum;
import lombok.Data;

@Data
public class AgentRunTransitionRequest {

    private String runId;

    private Long expectedRevision;

    private AgentRunStatusEnum targetStatus;

    private String failureReason;

    private String resultSummary;
}
