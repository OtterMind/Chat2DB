package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentTaskLifecycleRequest {

    private Long expectedRevision;
}
