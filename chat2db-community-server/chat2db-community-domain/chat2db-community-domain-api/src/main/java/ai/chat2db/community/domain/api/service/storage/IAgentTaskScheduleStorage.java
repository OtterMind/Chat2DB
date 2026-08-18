package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.agent.AgentTaskSchedule;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleClaim;
import ai.chat2db.community.domain.api.model.agent.AgentTaskScheduleExecution;

import java.util.Date;
import java.util.List;

public interface IAgentTaskScheduleStorage {
    AgentTaskSchedule createSchedule(AgentTaskSchedule schedule);
    AgentTaskSchedule updateSchedule(AgentTaskSchedule schedule, long expectedRevision);
    AgentTaskSchedule getSchedule(String id);
    List<AgentTaskSchedule> listSchedules(Long createdBy);
    List<AgentTaskSchedule> listDueSchedules(Date dueAt, int limit);
    AgentTaskScheduleClaim claimExecution(AgentTaskScheduleExecution execution, Date now);
    AgentTaskScheduleExecution getExecution(String id);
    List<AgentTaskScheduleExecution> listExecutions(String scheduleId);
    List<AgentTaskScheduleExecution> listRecoverableExecutions(Date now, int limit);
    AgentTaskCreation createScheduledTask(AgentTaskSchedule schedule,
                                          AgentTaskScheduleExecution execution,
                                          AgentTask task, AgentRun run,
                                          Date nextRunAt, long expectedScheduleRevision,
                                          long expectedExecutionRevision, String leaseToken);
    AgentTaskScheduleExecution finishExecutionWithoutTask(AgentTaskSchedule schedule,
                                                          AgentTaskScheduleExecution execution,
                                                          Date nextRunAt,
                                                          long expectedScheduleRevision,
                                                          long expectedExecutionRevision,
                                                          String leaseToken);
    AgentTaskScheduleExecution updateExecution(AgentTaskScheduleExecution execution,
                                               long expectedRevision, String leaseToken);
    boolean hasActiveExecutionTask(String scheduleId);
}
