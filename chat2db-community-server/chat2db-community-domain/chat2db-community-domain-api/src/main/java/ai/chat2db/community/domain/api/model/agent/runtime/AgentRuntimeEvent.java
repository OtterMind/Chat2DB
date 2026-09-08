package ai.chat2db.community.domain.api.model.agent.runtime;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

public record AgentRuntimeEvent(
        String externalEventId,
        String sessionId,
        String runId,
        AgentEventType type,
        Map<String, Object> payload,
        LocalDateTime occurredAt) {

    public AgentRuntimeEvent {
        requireText(externalEventId, "externalEventId");
        requireText(sessionId, "sessionId");
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
