package ai.chat2db.community.web.api.model.response.agent;

import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentTaskScheduleDetailResponse {
    private AgentTaskSchedule schedule;
    private List<AgentTaskScheduleExecution> executions = new ArrayList<>();
}
