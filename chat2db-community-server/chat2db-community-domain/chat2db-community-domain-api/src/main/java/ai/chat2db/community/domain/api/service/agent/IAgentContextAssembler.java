package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;

import java.util.List;

public interface IAgentContextAssembler {

    String assemble(AgentDefinition agent, AgentTask task, List<AgentRun> runHistory);
}
