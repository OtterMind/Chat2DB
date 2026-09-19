package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import java.util.List;
import java.util.Map;

/**
 * Talks to an external MCP server. Implementations own the transport; the domain only decides which
 * servers exist, which of their tools a session sees and who approved what.
 */
public interface IMcpToolDiscovery {

    /** Connects with the stored configuration and lists the tools the server advertises. */
    List<McpToolDescriptor> discover(McpServerConfig config);

    /** Calls one tool of the server and returns its content as text. */
    McpToolCallResult call(McpServerConfig config, String toolName, Map<String, Object> arguments);

    /** Releases every connection and child process of one server. */
    void close(String serverName);

    record McpToolCallResult(boolean ok, String text, String errorCode, String errorMessage) {
        public static McpToolCallResult success(String text) {
            return new McpToolCallResult(true, text, null, null);
        }

        public static McpToolCallResult failure(String code, String message) {
            return new McpToolCallResult(false, null, code, message);
        }
    }
}
