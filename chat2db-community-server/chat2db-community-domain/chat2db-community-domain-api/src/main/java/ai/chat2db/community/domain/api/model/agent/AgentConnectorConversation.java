package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorConversationStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorConversation {
    private String id;
    private String connectorSessionId;
    private String externalSessionId;
    private String taskId;
    private AgentConnectorConversationStatusEnum status;
    private Date createdAt;
    private Date lastUsedAt;
    private Date closedAt;
    private Long revision;
    /** Runtime-derived count; not persisted in the Connector conversation table. */
    private Integer pendingApprovalCount;
}
