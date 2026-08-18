package ai.chat2db.community.domain.api.model.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentTaskScheduleClaim {
    private AgentTaskScheduleExecution execution;
    private boolean claimed;
}
