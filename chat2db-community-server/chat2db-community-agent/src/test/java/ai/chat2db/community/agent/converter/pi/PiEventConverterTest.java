package ai.chat2db.community.agent.converter.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiEventConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiEventConverter converter = new PiEventConverter(
            objectMapper, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "event-id");

    @Test
    void mapsSupportedPiEvents() throws Exception {
        assertEquals(AgentEventType.RUN_STARTED, type("{\"type\":\"agent_start\"}"));
        assertEquals(AgentEventType.ASSISTANT_MESSAGE_STARTED,
                type("{\"type\":\"message_start\",\"message\":{\"role\":\"assistant\"}}"));
        assertEquals(AgentEventType.ASSISTANT_TEXT_DELTA,
                type("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\"}}"));
        assertEquals(AgentEventType.TOOL_CALL_FAILED,
                type("{\"type\":\"tool_execution_end\",\"isError\":true}"));
        assertEquals(AgentEventType.RUN_COMPLETED, type("{\"type\":\"agent_settled\"}"));
        assertEquals(AgentEventType.RUN_FAILED,
                type("{\"type\":\"agent_settled\",\"error\":\"failed\"}"));
    }

    @Test
    void dropsUnknownEventsAndRejectsMalformedOnes() throws Exception {
        assertNull(converter.toRuntimeEvent("session", "run", objectMapper.readTree("{\"type\":\"unknown\"}")));
        assertThrows(PiRpcException.class,
                () -> converter.toRuntimeEvent("session", "run", objectMapper.readTree("{}")));
    }

    @Test
    void carriesTheModelProvidedToolDescriptionOnRunningEvents() throws Exception {
        var event = converter.toRuntimeEvent("session", "run", objectMapper.readTree(
                "{\"type\":\"tool_execution_start\",\"toolName\":\"db_query\",\"toolCallId\":\"call\",\"args\":{\"description\":\"查询本月订单\"}}"));
        assertEquals("查询本月订单", event.payload().get("description"));
    }

    private AgentEventType type(String json) throws Exception {
        return converter.toRuntimeEvent("session", "run", objectMapper.readTree(json)).type();
    }
}
