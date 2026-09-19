package ai.chat2db.community.domain.api.enums.agent;

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
