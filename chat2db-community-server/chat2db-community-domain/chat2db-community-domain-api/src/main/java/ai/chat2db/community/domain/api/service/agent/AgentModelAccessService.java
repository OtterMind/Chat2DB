package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;

public interface AgentModelAccessService {

    AgentModelAccess issue(String sessionId, AgentModelSnapshot model);

    void revoke(String ticket);
}
