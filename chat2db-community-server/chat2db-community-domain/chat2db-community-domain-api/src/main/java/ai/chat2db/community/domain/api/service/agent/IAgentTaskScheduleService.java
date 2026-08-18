package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentTaskScheduleStatusEnum;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskScheduleUpdateRequest;

import java.util.Date;
import java.util.List;

public interface IAgentTaskScheduleService {
    AgentTaskSchedule create(AgentTaskScheduleCreateRequest request);
    AgentTaskSchedule update(AgentTaskScheduleUpdateRequest request);
    AgentTaskSchedule get(String id);
    List<AgentTaskSchedule> list(Long createdBy);
    List<AgentTaskScheduleExecution> listExecutions(String scheduleId);
    AgentTaskSchedule changeStatus(String scheduleId, long expectedRevision, AgentTaskScheduleStatusEnum status);
    AgentTaskScheduleExecution runNow(String scheduleId);
    List<Date> preview(String cronExpression, String timezone, int count);
}
