package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentToolAccess;

public interface AgentToolAccessService {
    AgentToolAccess issue(String sessionId, AgentRuntimeEventSink eventSink);
    void revoke(String ticket);
}
