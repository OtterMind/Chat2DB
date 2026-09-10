package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.tools.enums.agent.AgentFeature;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;

public interface AgentFeatureFlagStorage {

    boolean isEnabled(AgentRuntimeType runtimeType);

    void setEnabled(AgentRuntimeType runtimeType, boolean enabled);

    boolean isEnabled(AgentFeature feature);

    void setEnabled(AgentFeature feature, boolean enabled);
}
