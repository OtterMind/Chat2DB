package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskStatusEnum;
import lombok.Data;

@Data
public class AgentTaskTransitionRequest {

    private String taskId;

    private Long expectedRevision;

    private AgentTaskStatusEnum targetStatus;
}
