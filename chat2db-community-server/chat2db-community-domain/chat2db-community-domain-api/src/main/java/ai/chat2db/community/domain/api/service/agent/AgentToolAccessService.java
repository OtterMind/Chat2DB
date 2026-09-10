package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.tool.AgentToolState;
import ai.chat2db.community.tools.agent.runtime.IAgentToolAccessProvider;
import java.util.List;

public interface AgentToolAccessService extends IAgentToolAccessProvider {
    List<AgentToolState> listTools();
    List<String> activeTools(String ticket, String address);
    ai.chat2db.community.tools.agent.tool.IAgentToolResult<?> execute(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
    ai.chat2db.community.domain.api.model.agent.feature.AgentWorkspaceSettings prepareNative(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
}
