package ai.chat2db.community.domain.api.model.agent.runtime;

public enum AgentRuntimeHealth {
    STARTING,
    READY,
    BUSY,
    DEGRADED,
    STOPPED,
    FAILED
}
