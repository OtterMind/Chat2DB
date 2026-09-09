package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentWorkspaceStorage;
import ai.chat2db.community.tools.util.SystemSettingsUtil;

public class SettingsAgentWorkspaceStorage implements AgentWorkspaceStorage {
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
}
