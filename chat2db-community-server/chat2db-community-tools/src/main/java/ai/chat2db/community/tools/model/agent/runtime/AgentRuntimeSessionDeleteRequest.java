package ai.chat2db.community.tools.model.agent.runtime;

import java.util.Objects;

public record AgentRuntimeSessionDeleteRequest(String sessionId, AgentRuntimeBinding binding) {

    public AgentRuntimeSessionDeleteRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
    }
}
