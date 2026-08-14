package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentChatTaskCreation;
import ai.chat2db.community.domain.api.model.request.agent.AgentChatTaskCreateRequest;

public interface IAgentChatTaskService {

    AgentChatTaskCreation create(AgentChatTaskCreateRequest request);
}
