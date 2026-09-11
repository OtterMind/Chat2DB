package ai.chat2db.community.tools.model.agent.runtime;

import java.util.List;
import java.util.Objects;

public record AgentRuntimeSessionOpenRequest(
        String sessionId,
        String externalSessionId,
        String systemPrompt,
        AgentModelSnapshot model,
        List<AgentRuntimeSkill> skills) {

    public AgentRuntimeSessionOpenRequest(String sessionId, String externalSessionId,
            String systemPrompt, AgentModelSnapshot model) {
        this(sessionId, externalSessionId, systemPrompt, model, List.of());
    }

    public AgentRuntimeSessionOpenRequest {
        skills = skills == null ? List.of() : List.copyOf(skills);
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
