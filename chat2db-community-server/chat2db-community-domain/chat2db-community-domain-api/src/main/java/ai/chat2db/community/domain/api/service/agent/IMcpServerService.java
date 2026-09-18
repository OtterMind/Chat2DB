package ai.chat2db.community.domain.api.service.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerState;
import java.util.List;

/**
 * Manages the external MCP servers a user configures from the conversation. Configuration lives in
 * one plain-text file the user may also edit by hand; its path is reported through {@link #configPath()}
 * so nothing in a skill or a prompt has to hardcode a product-specific location.
 */
public interface IMcpServerService {

    /** Absolute path of the configuration file. */
    String configPath();

    List<McpServerState> list();

    McpServerConfig require(String name);

    McpServerState add(McpServerRegistration registration);

    McpServerState update(String name, McpServerRegistration registration);

    void remove(String name);

    McpServerState setEnabled(String name, boolean enabled);

    McpServerState setPolicy(String name, McpToolPolicy policy, List<String> allowedTools);

    /** Connects with the current configuration and refreshes the cached tool list. */
    McpServerState refreshTools(String name);

    /** Servers whose tools participate in a session. */
    List<McpServerConfig> enabledServers();

    /** Remembers one tool of one server after the user allowed it for good. */
    void rememberTool(String name, String toolName);

    /** Remembers every tool of one server after the user allowed the server. */
    void rememberServer(String name);

    boolean isToolAllowed(McpServerConfig config, String toolName);

    /** Digest of everything that defines what a stdio server launches or where an HTTP server connects. */
    String commandHash(McpServerConfig config);

    /** Records the approved launch line of a stdio server. */
    void approveCommand(String name, String commandHash);

    boolean isCommandApproved(McpServerConfig config, String commandHash);

    /** Fields a user or the model supplies when a server is added or changed. */
    record McpServerRegistration(String name, String transport, String command, List<String> args,
            String url, List<String> headerNames, List<String> environmentKeys,
            java.util.Map<String, String> secrets, Boolean enabled, McpToolPolicy policy) {
    }
}
