package ai.chat2db.community.domain.api.enums.agent;

public enum AgentRunStatus {
    ACCEPTED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    SUSPENDED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == UNKNOWN;
    }
}
