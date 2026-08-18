package ai.chat2db.community.domain.api.service.agent;

public interface IAgentTaskScheduleDispatcher {
    int dispatchDue(int limit);
    int recover(int limit);
}
