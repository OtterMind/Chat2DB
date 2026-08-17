package ai.chat2db.community.domain.api.enums.agent;

public enum AgentRuntimeEventTypeEnum {
    MESSAGE_DELTA,
    REASONING_DELTA,
    TOOL_CALL,
    TOOL_RESULT,
    USAGE,
    SESSION_UPDATED,
    APPROVAL_REQUIRED,
    ARTIFACT_CREATED,
    STATUS,
    ERROR
}
