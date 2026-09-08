package ai.chat2db.community.domain.api.model.agent.runtime;

public record AgentRuntimeSessionRef(String externalSessionId, String resumeReference) {

    public AgentRuntimeSessionRef {
        if (externalSessionId == null || externalSessionId.isBlank()) {
            throw new IllegalArgumentException("externalSessionId must not be blank");
        }
    }
}
