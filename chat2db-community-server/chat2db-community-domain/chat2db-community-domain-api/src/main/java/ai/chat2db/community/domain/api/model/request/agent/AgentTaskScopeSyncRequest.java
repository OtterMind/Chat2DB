package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentTaskScopeSyncRequest {

    private Long expectedRevision;
}
