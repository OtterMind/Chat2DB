package ai.chat2db.community.tools.model.agent.runtime;

import java.util.List;
import java.util.Objects;

public record AgentRuntimeSessionResumeRequest(
        String sessionId,
        AgentRuntimeBinding binding,
        String systemPrompt,
        AgentModelSnapshot model,
        List<AgentRuntimeSkill> skills) {

    public AgentRuntimeSessionResumeRequest(String sessionId, AgentRuntimeBinding binding,
            String systemPrompt, AgentModelSnapshot model) {
        this(sessionId, binding, systemPrompt, model, List.of());
    }

    public AgentRuntimeSessionResumeRequest {
        skills = skills == null ? List.of() : List.copyOf(skills);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(model, "model");
    }
}
