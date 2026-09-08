package ai.chat2db.community.domain.api.model.agent;

import java.util.Objects;

public record AgentRuntimeBinding(
        AgentRuntimeType runtimeType,
        String runtimeVersion,
        String protocolVersion,
        String externalSessionId,
        String resumeReference,
        long revision) {

    public AgentRuntimeBinding {
        Objects.requireNonNull(runtimeType, "runtimeType");
        requireText(runtimeVersion, "runtimeVersion");
        requireText(protocolVersion, "protocolVersion");
        requireText(externalSessionId, "externalSessionId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be greater than zero");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
