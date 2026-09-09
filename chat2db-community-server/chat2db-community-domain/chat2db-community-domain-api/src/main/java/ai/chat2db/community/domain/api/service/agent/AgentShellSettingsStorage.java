package ai.chat2db.community.domain.api.service.agent;

public interface AgentShellSettingsStorage {
    String getWorkingDirectory();
    void setWorkingDirectory(String directory);
}
