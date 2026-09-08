package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;

@FunctionalInterface
public interface AgentRuntimeEventSink {

    void emit(AgentRuntimeEvent event);
}
