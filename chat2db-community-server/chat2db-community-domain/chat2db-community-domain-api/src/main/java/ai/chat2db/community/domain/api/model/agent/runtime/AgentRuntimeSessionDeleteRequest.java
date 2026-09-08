package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeBinding;

import java.util.Objects;

public record AgentRuntimeSessionDeleteRequest(String sessionId, AgentRuntimeBinding binding) {

    public AgentRuntimeSessionDeleteRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
    }
}
