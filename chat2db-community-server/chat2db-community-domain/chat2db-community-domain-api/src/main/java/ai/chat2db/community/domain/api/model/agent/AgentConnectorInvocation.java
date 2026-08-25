package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentConnectorInvocationStatusEnum;
import lombok.Data;

import java.util.Date;

@Data
public class AgentConnectorInvocation {
    private String id;
    private String conversationId;
    private String externalCallId;
    private String toolName;
    private String taskId;
    private String runId;
    private AgentConnectorInvocationStatusEnum status;
    private Date createdAt;
    private Date updatedAt;
    private Date completedAt;
    private String responseJson;
    private Long revision;
}
