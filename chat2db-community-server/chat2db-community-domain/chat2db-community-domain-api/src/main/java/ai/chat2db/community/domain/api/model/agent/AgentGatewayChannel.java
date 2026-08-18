package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentGatewayPlatformEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentGatewayChannel {
    private String id;
    private String name;
    private AgentGatewayPlatformEnum platform;
    private String installationRef;
    private String defaultAgentId;
    private Long createdBy;
    private Boolean enabled;
    private Date archivedAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long revision;
}
