package ai.chat2db.community.domain.api.service.agent;

import java.util.Map;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;

/** V2 file permissions: managed results and skills are read-only, user files are opt-in. */
public interface IAiAgentFileAccessService {
    IAgentToolResult<?> execute(AgentToolExecutionContext context, String toolName, Map<String, Object> arguments);
    void authorizeNative(String sessionId, String toolName, String workingDirectory, Map<String, Object> arguments);

    default String authorizedDirectory(String sessionId, String toolName, String workingDirectory, Map<String, Object> arguments) {
        authorizeNative(sessionId, toolName, workingDirectory, arguments);
        return workingDirectory;
    }

    default String userSkillDirectory() { return null; }
}
