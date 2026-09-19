package ai.chat2db.community.tools.agent.tool;

/** Common result surface for V2 tools; each tool owns its structured data. */
public interface IAgentToolResult<T> {
    boolean ok();
    T data();
}
