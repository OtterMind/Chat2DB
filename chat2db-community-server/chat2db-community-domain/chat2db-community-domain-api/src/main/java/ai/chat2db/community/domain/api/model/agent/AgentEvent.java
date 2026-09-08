package ai.chat2db.community.domain.api.model.agent;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

public record AgentEvent(
        String id,
        String sessionId,
        String runId,
        long sequence,
        AgentEventType type,
        Map<String, Object> payload,
        LocalDateTime occurredAt) {

    public AgentEvent {
        requireText(id, "id");
        requireText(sessionId, "sessionId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be greater than zero");
        }
        Objects.requireNonNull(type, "type");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
