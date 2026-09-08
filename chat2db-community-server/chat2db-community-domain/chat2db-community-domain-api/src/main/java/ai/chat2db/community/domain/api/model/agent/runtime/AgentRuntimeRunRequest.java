package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;

import java.util.Objects;

public record AgentRuntimeRunRequest(
        String sessionId,
        String runId,
        AgentModelSnapshot model,
        AgentRuntimeInput input,
        String idempotencyKey) {

    public AgentRuntimeRunRequest {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
        requireText(idempotencyKey, "idempotencyKey");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
