package ai.chat2db.community.domain.api.enums.agent;

public enum AgentRunStatusEnum {
    QUEUED,
    DISPATCHED,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN
}
