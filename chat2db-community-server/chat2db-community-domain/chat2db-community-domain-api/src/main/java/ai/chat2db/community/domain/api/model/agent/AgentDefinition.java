package ai.chat2db.community.domain.api.model.agent;

import java.util.Objects;

public record AgentDefinition(
        String id,
        String name,
        String description,
        String systemPrompt,
        AgentRuntimeId runtimeId,
        String modelConfigId,
        long revision) {

    public AgentDefinition {
        requireText(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(runtimeId, "runtimeId");
        requireText(modelConfigId, "modelConfigId");
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
