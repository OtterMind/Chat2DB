package ai.chat2db.community.runtime.provider;

import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;

@FunctionalInterface
public interface ProviderEventSink {
    void emit(AgentRuntimeEvent event);
}
