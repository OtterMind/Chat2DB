package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.tool.AgentToolState;
import ai.chat2db.community.tools.agent.runtime.IAgentToolAccessProvider;
import java.util.List;

public interface AgentToolAccessService extends IAgentToolAccessProvider {
    List<AgentToolState> listTools();
    List<String> activeTools(String ticket, String address);

    /**
     * Every tool this session may use, with its schema. A session that adds an MCP server while it is
     * open re-reads this list, so the server's tools become callable without restarting the runtime.
     */
    List<ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess.Tool> definitions(
            String ticket, String address);
    ai.chat2db.community.tools.agent.tool.IAgentToolResult<?> execute(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
    ai.chat2db.community.domain.api.model.agent.tool.AgentNativePreparation prepareNative(
            String ticket, String address, String toolCallId, String toolName, java.util.Map<String, Object> arguments) throws Exception;
    Object output(String ticket, String address, String toolCallId, String toolName,
            java.util.Map<String, Object> arguments) throws Exception;
}
