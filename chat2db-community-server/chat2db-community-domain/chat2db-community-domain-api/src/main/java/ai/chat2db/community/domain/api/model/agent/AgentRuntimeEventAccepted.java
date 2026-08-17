package ai.chat2db.community.domain.api.model.agent;

import lombok.Data;

@Data
public class AgentRuntimeEventAccepted {

    private AgentRunEvent event;
    private AgentRuntimeLeaseStatus lease;
}
