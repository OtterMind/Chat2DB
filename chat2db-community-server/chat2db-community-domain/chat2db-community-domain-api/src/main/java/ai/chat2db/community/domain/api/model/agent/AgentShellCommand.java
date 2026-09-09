package ai.chat2db.community.domain.api.model.agent;

public record AgentShellCommand(String sessionId, String workingDirectory, String command) { }
