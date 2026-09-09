package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.AgentShellSettings;

public interface AgentShellSettingsService {
    AgentShellSettings get();
    AgentShellSettings update(String workingDirectory);
    String resolveWorkingDirectory(String sessionId);
}
