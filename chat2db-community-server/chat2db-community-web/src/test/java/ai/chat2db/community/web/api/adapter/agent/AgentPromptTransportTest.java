package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.web.api.converter.agent.AgentPromptRequestConverter;
import ai.chat2db.community.web.api.model.request.agent.AgentRunStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentPromptTransportTest {
    @Test
    void transportsContextAndValidatesObjectIdentity() throws Exception {
        String body = """
                {"modelConfigId":"model","message":"分析这张表","idempotencyKey":"one",
                 "context":{"timeZone":"Asia/Shanghai","selection":{"dataSourceId":"123","database":"sales"},
                 "objects":[{"dataSourceId":"123","database":"sales","schema":"public","name":"orders","type":"TABLE","source":"MENTION"}]}}
                """;
        ObjectMapper json = new ObjectMapper();
        var request = json.readValue(body, AgentRunStartRequest.class);
        var command = AgentPromptRequestConverter.INSTANCE.request2command(1L, "session", request);
        assertEquals("分析这张表", command.input().text());
        assertEquals("Asia/Shanghai", command.context().timeZone());
        assertEquals("public", command.context().objects().get(0).schema());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            assertFalse(factory.getValidator().validate(json.readValue(body.replace("MENTION", "OTHER"), AgentRunStartRequest.class)).isEmpty());
        }
        assertNull(AgentPromptRequestConverter.INSTANCE.request2command(1L, "session",
                new AgentRunStartRequest("model", "old client", "legacy")).context());
    }
}
