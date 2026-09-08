package ai.chat2db.community.domain.api.model.agent;

import java.time.LocalDateTime;
import java.util.Objects;

public record AgentSession(
        int schemaVersion,
        String id,
        Long userId,
        AgentDefinition definition,
        AgentRuntimeBinding runtimeBinding,
        AgentSessionStatus status,
        String title,
        long lastEventSequence,
        LocalDateTime gmtCreate,
        LocalDateTime gmtModified) {

    public static final int SCHEMA_VERSION = 2;

    public AgentSession {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION);
        }
        requireText(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(runtimeBinding, "runtimeBinding");
        Objects.requireNonNull(status, "status");
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must not be negative");
        }
        Objects.requireNonNull(gmtCreate, "gmtCreate");
        Objects.requireNonNull(gmtModified, "gmtModified");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
