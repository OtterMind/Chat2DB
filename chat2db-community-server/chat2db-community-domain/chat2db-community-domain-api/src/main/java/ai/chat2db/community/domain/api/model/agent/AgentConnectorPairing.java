package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorPairingStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorPairing {
    private String id;
    private String clientName;
    private String pollTokenHash;
    private String userCode;
    private AgentConnectorPairingStatusEnum status;
    private String agentId;
    private String agentName;
    private Long ownerId;
    private String exchangeCode;
    private String sessionId;
    private Date expiresAt;
    private Date createdAt;
    private Date decidedAt;
    private Long revision;
}
