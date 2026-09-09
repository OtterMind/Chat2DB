package ai.chat2db.community.domain.api.service.agent;

public interface AgentWorkspaceStorage {
    String getWorkingDirectory();
    void setWorkingDirectory(String directory);
    boolean isToolEnabled(String toolName);
    void setToolEnabled(String toolName, boolean enabled);
}
