package ai.chat2db.community.domain.api.model.agent.mcp;

import java.util.Map;

/**
 * One tool advertised by an external MCP server, cached so a session can expose it without starting
 * the server. The hints are the server's own claims; they classify a tool but never grant access.
 */
public record McpToolDescriptor(
        String name,
        String description,
        Map<String, Object> parameters,
        boolean readOnlyHint,
        boolean destructiveHint) {

    public McpToolDescriptor {
        parameters = parameters == null ? Map.of("type", "object") : Map.copyOf(parameters);
    }
}
