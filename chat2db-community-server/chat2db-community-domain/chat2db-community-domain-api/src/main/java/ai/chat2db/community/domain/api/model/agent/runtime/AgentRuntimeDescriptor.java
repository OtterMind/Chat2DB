package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeId;

import java.util.Objects;

public record AgentRuntimeDescriptor(
        AgentRuntimeId id,
        String displayName,
        String version,
        String protocolVersion,
        AgentRuntimeCapabilities capabilities) {

    public AgentRuntimeDescriptor {
        Objects.requireNonNull(id, "id");
        requireText(displayName, "displayName");
        requireText(version, "version");
        requireText(protocolVersion, "protocolVersion");
        Objects.requireNonNull(capabilities, "capabilities");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
