package ai.chat2db.community.domain.api.model.agent.runtime;

import java.util.Set;

public record AgentRuntimeCapabilities(
        Set<AgentRuntimeCapability> supported,
        int maxConcurrentRunsPerSession) {

    public AgentRuntimeCapabilities {
        supported = supported == null ? Set.of() : Set.copyOf(supported);
        if (maxConcurrentRunsPerSession < 1) {
            throw new IllegalArgumentException("maxConcurrentRunsPerSession must be greater than zero");
        }
    }

    public boolean supports(AgentRuntimeCapability capability) {
        return supported.contains(capability);
    }
}
