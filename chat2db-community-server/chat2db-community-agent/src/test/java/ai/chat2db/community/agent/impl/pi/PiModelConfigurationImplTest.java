package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiModelConfigurationImplTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> revoked = new ArrayList<>();
    private final IAgentModelAccessProvider access = new IAgentModelAccessProvider() {
        @Override public AgentModelAccess issue(String sessionId, AgentModelSnapshot model) {
            return new AgentModelAccess("chat2db", model.modelId(), model.provider(),
                    "http://127.0.0.1/" + model.modelConfigId(), "ticket-" + model.modelConfigId());
        }
        @Override public void revoke(String ticket) { revoked.add(ticket); }
    };

    @Test
    void replacesProtocolAndTicketTogetherAndRevokesPreviousAccess() throws Exception {
        try (var configuration = new PiModelConfigurationImpl("session", directory, access, json)) {
            configuration.prepare(model("first", "openai-responses"));
            configuration.prepare(model("second", "anthropic-messages"));
            var provider = json.readTree(directory.resolve("models.json").toFile()).path("providers").path("chat2db");
            assertEquals("anthropic-messages", provider.path("api").asText());
            assertEquals("http://127.0.0.1/second", provider.path("baseUrl").asText());
            assertEquals("ticket-second", provider.path("apiKey").asText());
            assertEquals("ticket-second", provider.path("headers").path("X-Chat2DB-Model-Ticket").asText());
            assertEquals("second", provider.path("models").get(0).path("id").asText());
            assertEquals(List.of("ticket-first"), revoked);
        }
        assertEquals(List.of("ticket-first", "ticket-second"), revoked);
    }

    @Test
    void responsesPreservesOptionalToolFieldsWithoutChangingOtherProtocols() throws Exception {
        try (var configuration = new PiModelConfigurationImpl("session", directory, access, json)) {
            for (String api : List.of("openai-responses", "openai-completions", "anthropic-messages", "google-generative-ai")) {
                configuration.prepare(model(api, api));
                var provider = json.readTree(directory.resolve("models.json").toFile()).path("providers").path("chat2db");
                assertEquals(api.equals("openai-responses"), provider.path("compat").path("supportsStrictMode").asBoolean());
            }
        }
    }

    @Test
    void failedReplacementRevokesOnlyTheNewTicket() throws Exception {
        var configuration = new PiModelConfigurationImpl("session", directory, access, json);
        configuration.prepare(model("first", "openai-responses"));
        Files.delete(directory.resolve("models.json"));
        Files.delete(directory);
        assertThrows(PiRpcException.class, () -> configuration.prepare(model("second", "anthropic-messages")));
        assertEquals(List.of("ticket-second"), revoked);
        configuration.close();
        assertEquals(List.of("ticket-second", "ticket-first"), revoked);
    }

    private AgentModelSnapshot model(String id, String api) {
        return new AgentModelSnapshot(id, 1, api, id, 1000, 100);
    }
}
