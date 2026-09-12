package ai.chat2db.community.agent.converter.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class PiEventConverter {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> idGenerator;

    public PiEventConverter() {
        this(new ObjectMapper(), Clock.systemDefaultZone(), () -> UUID.randomUUID().toString());
    }

    PiEventConverter(ObjectMapper objectMapper, Clock clock, Supplier<String> idGenerator) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    public AgentRuntimeEvent toRuntimeEvent(String sessionId, String runId, JsonNode event) {
        AgentEventType type = mapType(event);
        if (type == null) {
            return null;
        }
        String externalEventId = event.hasNonNull("id") ? event.get("id").asText() : idGenerator.get();
        Map<String, Object> payload = objectMapper.convertValue(event, new TypeReference<>() {
        });
        if (type == AgentEventType.TOOL_CALL_RUNNING) {
            JsonNode args = event.path("args");
            String description = args.path("description").isTextual() ? args.path("description").asText() : null;
            if (description != null && !description.isBlank()) {
                payload = new HashMap<>(payload);
                payload.put("description", description);
            }
        }
        return new AgentRuntimeEvent(
                externalEventId, sessionId, runId, type, payload, LocalDateTime.now(clock));
    }

    private AgentEventType mapType(JsonNode event) {
        String type = requiredText(event, "type");
        return switch (type) {
            case "agent_start" -> AgentEventType.RUN_STARTED;
            case "message_start" -> "assistant".equals(event.path("message").path("role").asText())
                    ? AgentEventType.ASSISTANT_MESSAGE_STARTED : null;
            case "message_update" -> mapMessageUpdate(event);
            case "message_end" -> "assistant".equals(event.path("message").path("role").asText())
                    && event.path("message").path("usage").isObject() ? AgentEventType.USAGE_UPDATED : null;
            case "tool_execution_start" -> AgentEventType.TOOL_CALL_RUNNING;
            case "tool_execution_end" -> event.path("isError").asBoolean(false)
                    ? AgentEventType.TOOL_CALL_FAILED : AgentEventType.TOOL_CALL_COMPLETED;
            case "extension_ui_request" -> AgentEventType.APPROVAL_REQUESTED;
            case "agent_settled" -> event.path("cancelled").asBoolean(false) ? AgentEventType.RUN_CANCELLED
                    : event.hasNonNull("error") ? AgentEventType.RUN_FAILED : AgentEventType.RUN_COMPLETED;
            case "compaction_end" -> event.hasNonNull("result") ? AgentEventType.CHECKPOINT_COMMITTED : null;
            default -> null;
        };
    }

    private AgentEventType mapMessageUpdate(JsonNode event) {
        String updateType = event.path("assistantMessageEvent").path("type").asText();
        if ("text_delta".equals(updateType)) {
            return AgentEventType.ASSISTANT_TEXT_DELTA;
        }
        if ("thinking_delta".equals(updateType) || "reasoning_delta".equals(updateType)) {
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
