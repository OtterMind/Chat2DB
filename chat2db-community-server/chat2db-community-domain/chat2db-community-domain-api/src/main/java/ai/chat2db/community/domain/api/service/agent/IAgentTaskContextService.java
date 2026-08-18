package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentTaskContext;
import ai.chat2db.community.domain.api.model.request.agent.AgentTaskContextCreateRequest;

import java.util.List;

public interface IAgentTaskContextService {

    AgentTaskContext append(AgentTaskContextCreateRequest request);

    List<AgentTaskContext> list(String taskId);
}
