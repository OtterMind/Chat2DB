package ai.chat2db.community.domain.api.model.agent;

import java.util.Objects;

public record AgentRun(
        String id,
        String sessionId,
        AgentRunStatus status,
        AgentModelSnapshot model,
        String requestMessageId,
        String externalRunId,
        long firstEventSequence,
        long lastEventSequence,
        AgentUsage usage,
        AgentFailure failure) {

    public AgentRun {
        requireText(id, "id");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(model, "model");
        requireText(requestMessageId, "requestMessageId");
        if (firstEventSequence < 0 || lastEventSequence < firstEventSequence) {
            throw new IllegalArgumentException("invalid agent event sequence range");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
