package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Names and describes the tools of external MCP servers inside one flat catalogue. Every name is
 * prefixed with its server so two servers may expose the same tool name without colliding.
 */
final class AgentMcpTools {

    static final String PREFIX = "mcp__";
    /** The runtime rejects tool names longer than this. */
    private static final int MAX_NAME = 100;

    private AgentMcpTools() {
    }

    static String group(McpServerConfig server) {
        return "mcp-server:" + server.name();
    }

    static String name(String server, String tool) {
        String sanitized = tool.replaceAll("[^A-Za-z0-9_-]", "_");
        String full = PREFIX + server + "__" + sanitized;
        if (full.length() <= MAX_NAME) return full;
        String digest = hash(tool).substring(0, 4);
        int keep = MAX_NAME - PREFIX.length() - server.length() - "__".length() - digest.length() - 1;
        return PREFIX + server + "__" + sanitized.substring(0, Math.max(1, keep)) + "-" + digest;
    }

    static List<AgentToolAccess.Tool> definitions(List<McpServerConfig> servers) {
        List<AgentToolAccess.Tool> tools = new ArrayList<>();
        for (McpServerConfig server : servers) {
            for (McpToolDescriptor tool : server.tools()) {
                tools.add(new AgentToolAccess.Tool(name(server.name(), tool.name()), describe(server, tool),
                        schema(tool.parameters()), null, List.of(), group(server), server.enabled()));
            }
        }
        return List.copyOf(tools);
    }

    static List<String> activeNames(List<McpServerConfig> servers) {
        List<String> names = new ArrayList<>();
        for (McpServerConfig server : servers) {
            if (!server.enabled()) continue;
            for (McpToolDescriptor tool : server.tools()) names.add(name(server.name(), tool.name()));
        }
        return List.copyOf(names);
    }

    /** Resolves a catalogue name back to the server and the tool, for calls that arrive from a model. */
    static Resolved resolve(List<McpServerConfig> servers, String toolName) {
        if (!toolName.startsWith(PREFIX)) return null;
        for (McpServerConfig server : servers) {
            for (McpToolDescriptor tool : server.tools()) {
                if (name(server.name(), tool.name()).equals(toolName)) {
                    return new Resolved(server, tool);
                }
            }
        }
        return null;
    }

    static String summarize(Resolved resolved, Map<String, Object> arguments) {
        return "Call " + resolved.tool().name() + " on MCP server '" + resolved.server().name() + "' with "
                + (arguments.isEmpty() ? "no arguments" : String.join(", ", arguments.keySet())) + ".";
    }

    private static String describe(McpServerConfig server, McpToolDescriptor tool) {
        String description = Objects.toString(tool.description(), "");
        String suffix = " External MCP tool from server '" + server.name() + "'.";
        if (tool.destructiveHint()) suffix += " The server marks it as destructive.";
        else if (tool.readOnlyHint()) suffix += " The server marks it as read-only.";
        return (description.isBlank() ? "Call " + tool.name() + "." : description) + suffix;
    }

    private static Map<String, Object> schema(Map<String, Object> parameters) {
        Map<String, Object> schema = new LinkedHashMap<>(parameters);
        schema.putIfAbsent("type", "object");
        return Map.copyOf(schema);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    record Resolved(McpServerConfig server, McpToolDescriptor tool) {
        String serverName() {
            return server.name();
        }

        String toolName() {
            return tool.name();
        }

        String key() {
            return (server.name() + "." + tool.name()).toLowerCase(Locale.ROOT);
        }
    }
}
