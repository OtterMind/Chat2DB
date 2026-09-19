package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentWorkspaceStorage;
import ai.chat2db.community.tools.util.SystemSettingsUtil;

public class AgentWorkspaceStorageImpl implements IAgentWorkspaceStorage {
    // Preserve the existing saved directory when migrating from Bash-only settings.
    private static final String WORKING_DIRECTORY = "agent.bash.workingDirectory";

    @Override
    public String getWorkingDirectory() {
        Object value = SystemSettingsUtil.getProperty(WORKING_DIRECTORY);
        return value instanceof String directory ? directory : "";
    }

    @Override
    public void setWorkingDirectory(String directory) {
        SystemSettingsUtil.setProperty(WORKING_DIRECTORY, directory);
    }
    @Override
    public boolean isToolEnabled(String toolName) {
        Object saved = SystemSettingsUtil.getProperty("agentToolEnabled." + toolName);
        if (saved instanceof Boolean enabled) return enabled;
        // Keep Bash enabled only when the user explicitly enabled it in the previous UI.
        return "bash".equals(toolName) && SystemSettingsUtil.getBooleanProperty("agentFeatureBetaEnabled.BASH", false);
    }

    @Override
    public void setToolEnabled(String toolName, boolean enabled) {
        SystemSettingsUtil.setProperty("agentToolEnabled." + toolName, enabled);
    }
}
