package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRunEvent;
import ai.chat2db.community.domain.api.model.request.agent.AgentRunTransitionRequest;

public interface IAgentRunService {

    AgentRun get(String id);

    AgentRun transition(AgentRunTransitionRequest request);

    AgentRunEvent appendEvent(AgentRunEvent event);
}
