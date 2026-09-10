package ai.chat2db.community.tools.model.agent.runtime;

import java.util.Objects;

public record AgentRuntimeSessionResumeRequest(
        String sessionId,
        AgentRuntimeBinding binding,
        String systemPrompt,
        AgentModelSnapshot model) {

    public AgentRuntimeSessionResumeRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(model, "model");
    }
}
