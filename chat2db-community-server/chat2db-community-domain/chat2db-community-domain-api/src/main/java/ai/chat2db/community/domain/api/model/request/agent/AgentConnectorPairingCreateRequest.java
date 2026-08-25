package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentConnectorPairingCreateRequest {
    private String clientName;
    private Integer protocolVersion;
}
