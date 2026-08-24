package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorTokenGrant {
    private String sessionId;
    private String agentId;
    private String agentName;
    private String mcpEndpoint;
    private String accessToken;
    private Date accessTokenExpiresAt;
    private String refreshToken;
    private Date refreshTokenExpiresAt;
}
