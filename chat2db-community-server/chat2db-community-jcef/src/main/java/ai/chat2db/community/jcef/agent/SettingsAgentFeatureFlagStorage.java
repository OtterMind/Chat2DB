package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.domain.api.model.agent.AgentFeature;
import ai.chat2db.community.tools.util.SystemSettingsUtil;

public class SettingsAgentFeatureFlagStorage implements AgentFeatureFlagStorage {

    private static final String PREFIX = "agentRuntimeBetaEnabled.";
    private static final String FEATURE_PREFIX = "agentFeatureBetaEnabled.";

    @Override
    public boolean isEnabled(AgentRuntimeType runtimeType) {
        return SystemSettingsUtil.getBooleanProperty(PREFIX + runtimeType.name(), false);
    }

    @Override
    public void setEnabled(AgentRuntimeType runtimeType, boolean enabled) {
        SystemSettingsUtil.setProperty(PREFIX + runtimeType.name(), enabled);
    }

    @Override
    public boolean isEnabled(AgentFeature feature) {
        return SystemSettingsUtil.getBooleanProperty(FEATURE_PREFIX + feature.name(), false);
    }

    @Override
    public void setEnabled(AgentFeature feature, boolean enabled) {
        SystemSettingsUtil.setProperty(FEATURE_PREFIX + feature.name(), enabled);
    }
}
