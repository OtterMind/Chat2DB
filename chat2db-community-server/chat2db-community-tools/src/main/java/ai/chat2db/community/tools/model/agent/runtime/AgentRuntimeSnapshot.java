package ai.chat2db.community.tools.model.agent.runtime;

import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import java.util.Objects;

public record AgentRuntimeSnapshot(
        AgentRuntimeSessionRef session,
        AgentRuntimeHealth health,
        String activeExternalRunId) {

    public AgentRuntimeSnapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(health, "health");
    }
}
