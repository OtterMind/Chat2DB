package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionCreateRequest;
import ai.chat2db.community.domain.api.model.request.agent.AgentDefinitionUpdateRequest;

import java.util.List;

public interface IAgentDefinitionService {

    AgentDefinition create(AgentDefinitionCreateRequest request);

    AgentDefinition update(AgentDefinitionUpdateRequest request);

    AgentDefinition archive(String id, long expectedRevision);

    AgentDefinition get(String id);

    List<AgentDefinition> list();
}
