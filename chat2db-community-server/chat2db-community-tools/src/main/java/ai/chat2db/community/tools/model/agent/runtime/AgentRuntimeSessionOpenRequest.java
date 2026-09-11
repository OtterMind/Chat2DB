package ai.chat2db.community.tools.model.agent.runtime;

import java.util.Objects;

public record AgentRuntimeSessionOpenRequest(
        String sessionId,
        String externalSessionId,
        String systemPrompt,
        AgentModelSnapshot model) {

    public AgentRuntimeSessionOpenRequest {
        requireText(sessionId, "sessionId");
        requireText(externalSessionId, "externalSessionId");
        Objects.requireNonNull(model, "model");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
