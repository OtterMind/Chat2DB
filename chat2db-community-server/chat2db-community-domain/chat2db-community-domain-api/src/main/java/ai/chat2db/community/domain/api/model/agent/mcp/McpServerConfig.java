package ai.chat2db.community.domain.api.model.agent.mcp;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import java.util.List;
import java.util.Map;

/**
 * One configured MCP server. Values are stored as plain text in the user configuration file; the
 * {@code secrets} map holds the values for {@code environmentKeys} and {@code headerNames}, and is
 * never echoed by a tool result, a session event or a log line.
 */
public record McpServerConfig(
        String name,
        McpTransport transport,
        String command,
        List<String> args,
        String url,
        List<String> headerNames,
        List<String> environmentKeys,
        Map<String, String> secrets,
        boolean enabled,
        McpToolPolicy policy,
        List<String> allowedTools,
        String approvedCommandHash,
        List<McpToolDescriptor> tools,
        String updatedAt) {

    public McpServerConfig {
        args = args == null ? List.of() : List.copyOf(args);
        headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
        environmentKeys = environmentKeys == null ? List.of() : List.copyOf(environmentKeys);
        secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        policy = policy == null ? McpToolPolicy.ASK : policy;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** The launch line a user must approve before a stdio server runs. */
    public String commandLine() {
        if (transport != McpTransport.STDIO || command == null) return null;
        return args.isEmpty() ? command : command + " " + String.join(" ", args);
    }

    public McpServerConfig withTools(List<McpToolDescriptor> discovered, String timestamp) {
        return new McpServerConfig(name, transport, command, args, url, headerNames, environmentKeys,
                secrets, enabled, policy, allowedTools, approvedCommandHash, discovered, timestamp);
    }

    public McpServerConfig withDecision(McpToolPolicy nextPolicy, List<String> nextAllowedTools, String timestamp) {
        return new McpServerConfig(name, transport, command, args, url, headerNames, environmentKeys,
                secrets, enabled, nextPolicy, nextAllowedTools, approvedCommandHash, tools, timestamp);
    }

    public McpServerConfig withEnabled(boolean nextEnabled, String timestamp) {
        return new McpServerConfig(name, transport, command, args, url, headerNames, environmentKeys,
                secrets, nextEnabled, policy, allowedTools, approvedCommandHash, tools, timestamp);
    }
}
