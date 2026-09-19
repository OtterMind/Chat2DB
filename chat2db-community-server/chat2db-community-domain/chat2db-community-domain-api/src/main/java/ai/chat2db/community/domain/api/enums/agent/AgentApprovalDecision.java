package ai.chat2db.community.domain.api.enums.agent;

/** What the user answered on an approval card. */
public enum AgentApprovalDecision {
    /** Run this call once. */
    ALLOW_ONCE,
    /** Run this call and never ask again for this tool. */
    ALLOW_TOOL,
    /** Run this call and never ask again for any tool of this server. */
    ALLOW_SERVER,
    /** Refuse this call. */
    DENY;

    public boolean allowed() {
        return this != DENY;
    }
}
