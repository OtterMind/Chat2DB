package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings;

public interface AgentWorkspaceService {
    AgentWorkspaceSettings get();
    AgentWorkspaceSettings update(String workingDirectory);
    String resolveWorkingDirectory(String sessionId);
    String selectDirectory();
    boolean isToolEnabled(String toolName);
    void setToolEnabled(String toolName, boolean enabled);
}
