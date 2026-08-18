package ai.chat2db.community.domain.api.model.request.agent;

import lombok.Data;

@Data
public class AgentTaskScheduleLifecycleRequest {
    private Long expectedRevision;
}
