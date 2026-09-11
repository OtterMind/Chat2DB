package ai.chat2db.community.web.api.model.response.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEvent;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import java.time.LocalDateTime;
import java.util.Map;

public record AgentEventResponse(
        String id,
        String sessionId,
        String runId,
        long sequence,
        AgentEventType type,
        Map<String, Object> payload,
        LocalDateTime occurredAt) {

    public static AgentEventResponse from(AgentEvent event) {
        return new AgentEventResponse(
                event.id(), event.sessionId(), event.runId(), event.sequence(),
                event.type(), event.payload(), event.occurredAt());
    }
}
