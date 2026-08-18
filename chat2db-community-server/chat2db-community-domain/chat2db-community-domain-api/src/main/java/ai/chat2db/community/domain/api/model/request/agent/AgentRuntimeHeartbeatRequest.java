package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeInstanceStatusEnum;
import lombok.Data;

@Data
public class AgentRuntimeHeartbeatRequest {

    private String daemonId;
    private Integer activeRuns;
    private AgentRuntimeInstanceStatusEnum status;
    private Long expectedRevision;
}
