package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentRunStatus;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import java.util.Objects;

public record AgentRun(
        String id,
        String sessionId,
        AgentRunStatus status,
        AgentModelSnapshot model,
        String requestMessageId,
        String idempotencyKey,
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
        requireText(idempotencyKey, "idempotencyKey");
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
