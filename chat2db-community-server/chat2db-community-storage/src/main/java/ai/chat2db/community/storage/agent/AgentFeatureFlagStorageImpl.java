package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentFeatureFlagStorage;
import ai.chat2db.community.tools.enums.agent.AgentFeature;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import ai.chat2db.community.tools.util.SystemSettingsUtil;

public class AgentFeatureFlagStorageImpl implements IAgentFeatureFlagStorage {

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
