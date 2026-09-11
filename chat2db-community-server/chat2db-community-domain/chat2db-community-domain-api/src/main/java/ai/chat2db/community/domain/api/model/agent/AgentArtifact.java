package ai.chat2db.community.domain.api.model.agent;

import ai.chat2db.community.domain.api.enums.agent.AgentArtifactType;
import java.time.LocalDateTime;
import java.util.Objects;

public record AgentArtifact(
        String id,
        String sessionId,
        String runId,
        AgentArtifactType type,
        String mediaType,
        long size,
        String sha256,
        String storageReference,
        LocalDateTime gmtCreate) {

    public AgentArtifact {
        requireText(id, "id");
        requireText(sessionId, "sessionId");
        requireText(runId, "runId");
        Objects.requireNonNull(type, "type");
        requireText(mediaType, "mediaType");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 value");
        }
        requireText(storageReference, "storageReference");
        Objects.requireNonNull(gmtCreate, "gmtCreate");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
