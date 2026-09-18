package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Tool result of the MCP management tools: a small payload and a corrective error when it fails. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMcpResponse(boolean ok, Object data, Error error) implements IAgentToolResult<Object> {

    public static AgentMcpResponse success(Object data) {
        return new AgentMcpResponse(true, data, null);
    }

    public static AgentMcpResponse failure(String code, String field, String message) {
        return new AgentMcpResponse(false, null, new Error(code, field, message));
    }

    public record Error(String code, String field, String message) { }
}
