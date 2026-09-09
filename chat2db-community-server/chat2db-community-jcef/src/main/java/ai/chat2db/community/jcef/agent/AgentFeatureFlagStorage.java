package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;

public interface AgentFeatureFlagStorage {

    boolean isEnabled(AgentRuntimeType runtimeType);

    void setEnabled(AgentRuntimeType runtimeType, boolean enabled);
}
