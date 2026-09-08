package ai.chat2db.community.domain.api.model.agent;

public enum AgentSessionStatus {
    CREATED,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    SUSPENDED,
    FAILED,
    UNKNOWN,
    CLOSED;

    public boolean isClosed() {
        return this == CLOSED;
    }
}
