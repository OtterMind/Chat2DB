package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorSessionStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorSession {
    private String id;
    private String clientName;
    private String agentId;
    private String agentName;
    private Long ownerId;
    private String taskId;
    private String runId;
    private AgentConnectorSessionStatusEnum status;
    private String accessTokenHash;
    private String refreshTokenHash;
    private Date accessTokenExpiresAt;
    private Date refreshTokenExpiresAt;
    private Date createdAt;
    private Date lastUsedAt;
    private Date revokedAt;
    private Long revision;
    /** Runtime-derived count; not persisted in the Connector session table. */
    private Integer pendingApprovalCount;
    /** Runtime-derived count; not persisted in the Connector session table. */
    private Integer conversationCount;
}
