package ai.chat2db.community.tools.model.agent.runtime;

import java.util.Objects;
import java.util.List;

public record AgentRuntimeRunRequest(
        String sessionId,
        String runId,
        AgentModelSnapshot model,
        AgentRuntimeInput input,
        String idempotencyKey,
        List<AgentRuntimeSkill> skills) {

    public AgentRuntimeRunRequest(String sessionId, String runId, AgentModelSnapshot model,
            AgentRuntimeInput input, String idempotencyKey) {
        this(sessionId, runId, model, input, idempotencyKey, List.of());
    }

    public AgentRuntimeRunRequest {
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(input, "input");
        requireText(idempotencyKey, "idempotencyKey");
        skills = List.copyOf(skills);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
