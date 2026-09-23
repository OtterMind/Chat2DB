package ai.chat2db.community.tools.model.agent.runtime;

import ai.chat2db.community.tools.enums.agent.AgentRuntimeType;
import java.util.Objects;

public record AgentRuntimeDescriptor(
        AgentRuntimeType type,
        String displayName,
        String version,
        String protocolVersion,
        AgentRuntimeCapabilities capabilities) {

    public AgentRuntimeDescriptor {
        Objects.requireNonNull(type, "type");
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
