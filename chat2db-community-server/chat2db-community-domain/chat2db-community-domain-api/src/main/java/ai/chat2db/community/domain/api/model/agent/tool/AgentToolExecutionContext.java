package ai.chat2db.community.domain.api.model.agent.tool;

import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Host-supplied invocation identity; never deserialized from model tool arguments. */
public record AgentToolExecutionContext(String sessionId, String runId, String toolCallId, Long userId,
        IAgentRuntimeEventSink eventSink, BooleanSupplier active) {
    public AgentToolExecutionContext {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(toolCallId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(eventSink);
        Objects.requireNonNull(active);
    }
}
