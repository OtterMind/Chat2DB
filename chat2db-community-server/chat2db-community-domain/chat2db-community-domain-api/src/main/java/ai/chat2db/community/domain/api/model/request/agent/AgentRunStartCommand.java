package ai.chat2db.community.domain.api.model.request.agent;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;

import java.util.Objects;

public record AgentRunStartCommand(
        Long userId,
        String sessionId,
        AgentModelSnapshot model,
        AgentRuntimeInput input,
        String idempotencyKey) {

    public AgentRunStartCommand {
        Objects.requireNonNull(userId, "userId");
        requireText(sessionId, "sessionId");
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
