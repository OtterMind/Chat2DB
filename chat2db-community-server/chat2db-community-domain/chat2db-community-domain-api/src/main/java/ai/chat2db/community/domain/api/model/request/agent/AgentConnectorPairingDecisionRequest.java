package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentConnectorPairingDecisionRequest {
    private String agentId;
    private Boolean approved;
    private Long expectedRevision;
}
