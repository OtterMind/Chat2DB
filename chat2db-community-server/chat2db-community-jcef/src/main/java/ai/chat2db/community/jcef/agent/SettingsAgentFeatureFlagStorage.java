package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeType;
import ai.chat2db.community.tools.util.SystemSettingsUtil;

public class SettingsAgentFeatureFlagStorage implements AgentFeatureFlagStorage {

    private static final String PREFIX = "agentRuntimeBetaEnabled.";

    @Override
    public boolean isEnabled(AgentRuntimeType runtimeType) {
        return SystemSettingsUtil.getBooleanProperty(PREFIX + runtimeType.name(), false);
    }

    @Override
    public void setEnabled(AgentRuntimeType runtimeType, boolean enabled) {
        SystemSettingsUtil.setProperty(PREFIX + runtimeType.name(), enabled);
    }
}
