package ai.chat2db.community.domain.api.enums.agent;

public enum AgentTaskScheduleReasonCodeEnum {
    MISSED_WINDOW,
    PREVIOUS_EXECUTION_ACTIVE,
    AGENT_UNAVAILABLE,
    RUNTIME_PROFILE_UNAVAILABLE,
    RUNTIME_OFFLINE,
    DATA_SCOPE_REVOKED,
    DISPATCH_FAILED,
    LEASE_EXPIRED
}
