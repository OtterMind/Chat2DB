package ai.chat2db.community.domain.api.model.agent;

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
