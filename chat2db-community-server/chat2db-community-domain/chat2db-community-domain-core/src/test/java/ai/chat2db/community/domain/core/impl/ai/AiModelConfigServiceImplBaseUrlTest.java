package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.ModelConfigTestResponse;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.model.request.ai.AiModelConfigSaveRequest;
import ai.chat2db.community.domain.core.converter.AiModelConfigConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiModelConfigServiceImplBaseUrlTest {

    private static final long USER_ID = 42L;

    @TempDir
    Path tempDirectory;

    @ParameterizedTest
    @CsvSource({
            "https://example.com/v1, https://example.com",
            "https://example.com/v1/, https://example.com",
            "https://example.com, https://example.com",
            "https://example.com/, https://example.com",
            "https://example.com/compatible-mode/v1, https://example.com/compatible-mode",
            "https://example.com/v1beta, https://example.com/v1beta"
    })
    void openAiRuntimeBaseUrlToleratesTrailingV1(String configured, String expected) {
        assertEquals(expected, resolveBaseUrl("OPENAI", configured));
    }

    @Test
    void claudeRuntimeBaseUrlToleratesTrailingV1() {
        assertEquals("https://claude.example.com", resolveBaseUrl("CLAUDE", "https://claude.example.com/v1"));
    }

    @Test
    void minimaxRuntimeBaseUrlToleratesTrailingV1() {
        assertEquals("https://minimax.example.com", resolveBaseUrl("MINIMAX", "https://minimax.example.com/v1"));
    }

    @ParameterizedTest
    @CsvSource({
            "OPENAI, https://api.openai.com/v1/chat/completions",
            "MINIMAX, https://api.minimax.io/v1/chat/completions"
    })
    void connectionTestUsesProviderDefaultBaseUrl(String provider, String expectedEndpoint) {
        assertEquals(expectedEndpoint, testEndpoint(provider, null));
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.minimax.io/v1, https://api.minimax.io/v1/chat/completions",
            "https://api.minimaxi.com/v1, https://api.minimaxi.com/v1/chat/completions",
            "https://api.minimax.io/anthropic, https://api.minimax.io/anthropic/v1/messages",
            "https://api.minimaxi.com/anthropic, https://api.minimaxi.com/anthropic/v1/messages"
    })
    void minimaxPresetsUseMatchingProtocol(String baseUrl, String endpoint) {
        assertEquals(endpoint, testEndpoint("MINIMAX", baseUrl));
        for (String model : java.util.List.of("MiniMax-M3.1-Flash", "MiniMax-M3")) {
            AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
            request.setProvider("MINIMAX");
            request.setModel(model);
            request.setApiKey("test-key");
            request.setBaseUrl(baseUrl);
            assertEquals(model, service().resolveRuntimeModel(request).getModel());
        }
    }

    @Test
    void minimaxConnectionTestKeepsCustomBaseUrl() {
        assertEquals("https://api.minimax.chat/v1/chat/completions",
                testEndpoint("MINIMAX", "https://api.minimax.chat/v1"));
    }

    private String resolveBaseUrl(String provider, String baseUrl) {
        AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
        request.setProvider(provider);
        request.setModel("gpt-test");
        request.setApiKey("sk-test-1234567890");
        request.setBaseUrl(baseUrl);
        return service().resolveRuntimeModel(request).getBaseUrl();
    }

    private String testEndpoint(String provider, String baseUrl) {
        AiModelConfigSaveRequest request = new AiModelConfigSaveRequest();
        request.setProvider(provider);
        request.setModel("test-model");
        request.setBaseUrl(baseUrl);
        ModelConfigTestResponse response = service().testModelConfig(request);
        return response.getEndpoint();
    }

    private AiModelConfigServiceImpl service() {
        return new AiModelConfigServiceImpl(new ObjectMapper().findAndRegisterModules(), new AiModelConfigConverter(),
                () -> USER_ID, tempDirectory.resolve("ai-model-configs.json"), null);
    }
}
