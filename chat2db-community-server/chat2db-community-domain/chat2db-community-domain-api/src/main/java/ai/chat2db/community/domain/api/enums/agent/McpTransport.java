package ai.chat2db.community.domain.api.enums.agent;

/** How Chat2DB reaches an external MCP server. */
public enum McpTransport {
    /** A local command that speaks MCP over stdio. */
    STDIO,
    /** A remote endpoint that speaks MCP over streamable HTTP. */
    HTTP
}
