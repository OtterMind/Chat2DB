package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;

import java.util.Objects;

public record AgentRuntimeSessionResumeRequest(
        String sessionId,
        AgentRuntimeBinding binding,
        AgentModelSnapshot model) {

    public AgentRuntimeSessionResumeRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(model, "model");
    }
}
