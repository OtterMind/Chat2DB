package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.web.api.converter.ai.ChatConverter;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigSaveRequest;
import ai.chat2db.community.web.api.model.request.ai.ModelConfigTestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentModelConfigTransportTest {
    @Test
    void keepsProtocolFromHttpPayloadThroughBothSaveAndTestConverters() throws Exception {
        String payload = """
                {"provider":"OPENAI","model":"custom-model","name":"Custom","agentApi":"openai-completions"}
                """;
        ObjectMapper json = new ObjectMapper();
        ChatConverter converter = Mappers.getMapper(ChatConverter.class);
        assertEquals("openai-completions", converter.toModelConfigParam(
                json.readValue(payload, ModelConfigSaveRequest.class)).getAgentApi());
        assertEquals("openai-completions", converter.toModelConfigParam(
                json.readValue(payload.replace("\"name\":\"Custom\",", ""), ModelConfigTestRequest.class)).getAgentApi());
    }
}
