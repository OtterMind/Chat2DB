package ai.chat2db.community.domain.api.model.agent.runtime;

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
