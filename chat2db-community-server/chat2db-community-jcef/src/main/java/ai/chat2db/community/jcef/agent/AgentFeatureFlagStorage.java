package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentFeature;

public interface AgentFeatureFlagStorage {

    boolean isEnabled(AgentRuntimeType runtimeType);

    void setEnabled(AgentRuntimeType runtimeType, boolean enabled);

    boolean isEnabled(AgentFeature feature);

    void setEnabled(AgentFeature feature, boolean enabled);
}
