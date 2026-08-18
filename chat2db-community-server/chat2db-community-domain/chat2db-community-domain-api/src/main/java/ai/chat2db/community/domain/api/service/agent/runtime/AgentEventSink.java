package ai.chat2db.community.domain.api.service.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;

@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentRuntimeEvent event);
}
