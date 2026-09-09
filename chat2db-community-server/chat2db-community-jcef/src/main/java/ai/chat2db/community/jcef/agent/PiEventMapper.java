package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class PiEventMapper {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> idGenerator;

    public PiEventMapper() {
        this(new ObjectMapper(), Clock.systemDefaultZone(), () -> UUID.randomUUID().toString());
    }

    PiEventMapper(ObjectMapper objectMapper, Clock clock, Supplier<String> idGenerator) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    public AgentRuntimeEvent map(String sessionId, String runId, JsonNode event) {
        AgentEventType type = mapType(event);
        if (type == null) {
            return null;
        }
        String externalEventId = event.hasNonNull("id") ? event.get("id").asText() : idGenerator.get();
        Map<String, Object> payload = objectMapper.convertValue(event, new TypeReference<>() {
        });
        return new AgentRuntimeEvent(
                externalEventId, sessionId, runId, type, payload, LocalDateTime.now(clock));
    }

    private AgentEventType mapType(JsonNode event) {
        String type = requiredText(event, "type");
        return switch (type) {
            case "agent_start" -> AgentEventType.RUN_STARTED;
            case "message_start" -> "assistant".equals(text(event, "role"))
                    ? AgentEventType.ASSISTANT_MESSAGE_STARTED : null;
            case "message_update" -> mapMessageUpdate(event);
            case "tool_execution_start" -> AgentEventType.TOOL_CALL_RUNNING;
            case "tool_execution_end" -> event.path("success").asBoolean(false)
                    ? AgentEventType.TOOL_CALL_COMPLETED : AgentEventType.TOOL_CALL_FAILED;
            case "extension_ui_request" -> AgentEventType.APPROVAL_REQUESTED;
            case "agent_settled" -> event.hasNonNull("error")
                    ? AgentEventType.RUN_FAILED : AgentEventType.RUN_COMPLETED;
            case "session_compact" -> AgentEventType.CHECKPOINT_COMMITTED;
            default -> null;
        };
    }

    private AgentEventType mapMessageUpdate(JsonNode event) {
        String updateType = text(event, "updateType");
        if ("text_delta".equals(updateType)) {
            return AgentEventType.ASSISTANT_TEXT_DELTA;
        }
        if ("reasoning_delta".equals(updateType)) {
            return AgentEventType.ASSISTANT_REASONING_DELTA;
        }
        if ("usage".equals(updateType)) {
            return AgentEventType.USAGE_UPDATED;
        }
        return null;
    }

    private String requiredText(JsonNode node, String name) {
        String value = text(node, name);
        if (value == null || value.isBlank()) {
            throw new PiRpcException("Pi event " + name + " is missing");
        }
        return value;
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
