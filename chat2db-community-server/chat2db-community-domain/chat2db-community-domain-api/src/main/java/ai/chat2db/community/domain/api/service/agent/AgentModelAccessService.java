package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentModelAccess;

public interface AgentModelAccessService {

    AgentModelAccess issue(String sessionId, AgentModelSnapshot model);

    void revoke(String ticket);
}
