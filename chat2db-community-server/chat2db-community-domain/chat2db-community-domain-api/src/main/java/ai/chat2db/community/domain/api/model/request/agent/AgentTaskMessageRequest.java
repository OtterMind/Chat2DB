package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentTaskMessageRequest {

    private String taskId;

    private String content;

    private String agentId;

    private Long createdBy;
}
