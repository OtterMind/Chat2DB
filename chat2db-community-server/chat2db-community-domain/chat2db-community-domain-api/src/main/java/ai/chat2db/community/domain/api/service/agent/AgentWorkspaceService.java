package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentWorkspaceSettings;
import ai.chat2db.community.domain.api.model.agent.AgentDirectoryListing;

public interface AgentWorkspaceService {
    AgentWorkspaceSettings get();
    AgentWorkspaceSettings update(String workingDirectory);
    String resolveWorkingDirectory(String sessionId);
    AgentDirectoryListing listDirectories(String path);
}
