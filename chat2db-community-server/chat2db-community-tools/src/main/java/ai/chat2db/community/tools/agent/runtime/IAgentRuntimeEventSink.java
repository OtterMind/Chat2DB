package ai.chat2db.community.tools.agent.runtime;

import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;

@FunctionalInterface
public interface IAgentRuntimeEventSink {

    void emit(AgentRuntimeEvent event);
}
