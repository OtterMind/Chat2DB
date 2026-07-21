package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
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

    private String resolveBaseUrl(String provider, String baseUrl) {
        AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
        request.setProvider(provider);
        request.setModel("gpt-test");
        request.setApiKey("sk-test-1234567890");
        request.setBaseUrl(baseUrl);
        return service().resolveRuntimeModel(request).getBaseUrl();
    }

    private AiModelConfigServiceImpl service() {
        return new AiModelConfigServiceImpl(new ObjectMapper().findAndRegisterModules(), new AiModelConfigConverter(),
                () -> USER_ID, tempDirectory.resolve("ai-model-configs.json"), null);
    }
}
