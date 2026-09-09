package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiEventMapper mapper = new PiEventMapper(
            objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "event-id");

    @Test
    void mapsSupportedPiEvents() throws Exception {
        assertEquals(AgentEventType.RUN_STARTED, type("{\"type\":\"agent_start\"}"));
        assertEquals(AgentEventType.ASSISTANT_MESSAGE_STARTED,
                type("{\"type\":\"message_start\",\"role\":\"assistant\"}"));
        assertEquals(AgentEventType.ASSISTANT_TEXT_DELTA,
                type("{\"type\":\"message_update\",\"updateType\":\"text_delta\"}"));
        assertEquals(AgentEventType.USAGE_UPDATED,
                type("{\"type\":\"message_update\",\"updateType\":\"usage\"}"));
        assertEquals(AgentEventType.TOOL_CALL_FAILED,
                type("{\"type\":\"tool_execution_end\",\"success\":false}"));
        assertEquals(AgentEventType.RUN_COMPLETED, type("{\"type\":\"agent_settled\"}"));
        assertEquals(AgentEventType.RUN_FAILED,
                type("{\"type\":\"agent_settled\",\"error\":\"failed\"}"));
    }

    @Test
    void dropsUnknownEventsAndRejectsMalformedOnes() throws Exception {
        assertNull(mapper.map("session", "run", objectMapper.readTree("{\"type\":\"unknown\"}")));
        assertThrows(PiRpcException.class,
                () -> mapper.map("session", "run", objectMapper.readTree("{}")));
    }

    private AgentEventType type(String json) throws Exception {
        return mapper.map("session", "run", objectMapper.readTree(json)).type();
    }
}
