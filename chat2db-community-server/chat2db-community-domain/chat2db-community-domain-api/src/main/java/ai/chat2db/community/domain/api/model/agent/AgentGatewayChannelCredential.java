package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

@Data
public class AgentGatewayChannelCredential {
    private AgentGatewayChannel channel;
    private String gatewayToken;
}
