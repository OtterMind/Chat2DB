package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;

public interface IAgentModelAccessProvider {

    AgentModelAccess issue(String sessionId, AgentModelSnapshot model);

    void revoke(String ticket);
}
