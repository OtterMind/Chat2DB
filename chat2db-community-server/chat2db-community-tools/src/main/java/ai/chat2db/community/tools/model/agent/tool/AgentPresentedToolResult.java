package ai.chat2db.community.tools.model.agent.tool;

import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keeps each tool's original top-level contract when replacing only its oversized data. */
public final class AgentPresentedToolResult extends LinkedHashMap<String, Object> implements IAgentToolResult<Object> {
    public AgentPresentedToolResult(Map<String, Object> fields) { super(fields); }
    @Override public boolean ok() { return Boolean.TRUE.equals(get("ok")); }
    @Override public Object data() { return get("data"); }
}
