package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;

/** Supplies and revokes the scoped tool access used by a runtime process. */
public interface IAgentToolAccessProvider {
    AgentToolAccess issue(String sessionId, IAgentRuntimeEventSink eventSink);

    void revoke(String ticket);
}
