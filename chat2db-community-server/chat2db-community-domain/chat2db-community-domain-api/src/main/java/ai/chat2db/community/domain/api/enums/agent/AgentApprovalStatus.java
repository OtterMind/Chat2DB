package ai.chat2db.community.domain.api.enums.agent;

public enum AgentApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
