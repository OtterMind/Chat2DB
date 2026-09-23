package ai.chat2db.community.tools.model.agent.tool;

import java.util.Map;

/** Suggested tool invocation after a result or a recoverable tool failure. */
public record AgentToolNextAction(String tool, Map<String, Object> arguments) { }
