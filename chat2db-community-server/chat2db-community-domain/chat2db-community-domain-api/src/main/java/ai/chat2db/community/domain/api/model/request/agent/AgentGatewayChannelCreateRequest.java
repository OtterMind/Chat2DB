package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
import lombok.Data;

@Data
public class AgentGatewayChannelCreateRequest {
    private String name;
    private AgentGatewayPlatformEnum platform;
    private String installationRef;
    private String defaultAgentId;
    private Long createdBy;
}
