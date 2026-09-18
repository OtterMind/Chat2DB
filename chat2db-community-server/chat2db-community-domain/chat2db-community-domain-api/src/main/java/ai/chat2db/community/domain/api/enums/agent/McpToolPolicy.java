package ai.chat2db.community.domain.api.enums.agent;

/** Whether an external MCP tool asks the user before every call. */
public enum McpToolPolicy {
    /** Tools that are not remembered ask the user for each call. */
    ASK,
    /** Every tool of this server runs without asking. */
    ALLOW
}
