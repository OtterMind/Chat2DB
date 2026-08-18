package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.AgentTaskCreation;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskTransitionRequest;
import ai.chat2db.community.domain.api.enums.agent.AgentRunTriggerTypeEnum;

import java.util.List;

public interface IAgentTaskService {

    AgentTaskCreation create(AgentTaskCreateRequest request);

    AgentTask get(String id);

    List<AgentTask> list();

    List<AgentTask> listArchived();

    List<AgentRun> listRuns(String taskId);

    AgentTask transition(AgentTaskTransitionRequest request);

    AgentTask syncAssignedAgentScopes(String taskId, long expectedRevision);

    AgentTask archive(String taskId, long expectedRevision);

    void deleteArchived(String taskId, long expectedRevision);

    AgentTaskCreation createRun(String taskId, AgentRunTriggerTypeEnum triggerType);

    AgentTaskCreation createRun(String taskId, AgentRunTriggerTypeEnum triggerType, String agentId);
}
