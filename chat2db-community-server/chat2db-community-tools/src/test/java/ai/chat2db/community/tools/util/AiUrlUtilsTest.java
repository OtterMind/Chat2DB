package ai.chat2db.community.tools.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiUrlUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "https://api.openai.com, /v1/chat/completions",
            "https://api.openai.com/v1, /chat/completions",
            "https://api.openai.com/v1/, /chat/completions",
            "https://example.com/compatible-mode/v1, /chat/completions",
            "https://open.bigmodel.cn/api/paas/v4, /chat/completions",
            "https://open.bigmodel.cn/api/coding/paas/v4, /chat/completions",
            "https://example.com/v1beta, /chat/completions",
            "https://example.com/ai, /v1/chat/completions"
    })
    void completionsPathFollowsTheBaseUrlVersionSegment(String baseUrl, String expected) {
        assertEquals(expected, AiUrlUtils.resolveCompletionsPath(baseUrl));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void completionsPathFallsBackToTheDefaultForBlankBaseUrls(String baseUrl) {
        assertEquals("/v1/chat/completions", AiUrlUtils.resolveCompletionsPath(baseUrl));
    }

    @ParameterizedTest
    @CsvSource({
            "https://open.bigmodel.cn/api/paas/v4, /embeddings",
            "https://api.openai.com/v1, /embeddings",
            "https://api.openai.com, /v1/embeddings"
    })
    void embeddingsPathFollowsTheBaseUrlVersionSegment(String baseUrl, String expected) {
        assertEquals(expected, AiUrlUtils.resolveEmbeddingsPath(baseUrl));
    }

    @ParameterizedTest
    @CsvSource({
            // The reported Zhipu GLM case: a /v4 base URL must not receive "/v1/chat/completions".
            "https://open.bigmodel.cn/api/paas/v4, https://open.bigmodel.cn/api/paas/v4/chat/completions",
            "https://open.bigmodel.cn/api/coding/paas/v4, https://open.bigmodel.cn/api/coding/paas/v4/chat/completions",
            "https://api.openai.com/v1, https://api.openai.com/v1/chat/completions",
            "https://api.openai.com, https://api.openai.com/v1/chat/completions",
            "https://api.minimax.io/v1/, https://api.minimax.io/v1/chat/completions",
            // A pasted full endpoint collapses instead of doubling the operation path.
            "https://api.openai.com/v1/chat/completions, https://api.openai.com/v1/chat/completions",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions, https://open.bigmodel.cn/api/paas/v4/chat/completions"
    })
    void chatCompletionsEndpointCarriesExactlyOneVersionSegment(String baseUrl, String expected) {
        assertEquals(expected, AiUrlUtils.buildChatCompletionsEndpoint(baseUrl));
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/v1/chat/completions/, https://example.com/v1",
            "https://example.com/chat/completions, https://example.com",
            "https://example.com, https://example.com"
    })
    void trimChatCompletionsPathRemovesAPastedOperationPath(String baseUrl, String expected) {
        assertEquals(expected, AiUrlUtils.trimChatCompletionsPath(baseUrl));
    }

    @Test
    void trimChatCompletionsPathKeepsBlankValues() {
        assertNull(AiUrlUtils.trimChatCompletionsPath(null));
        assertEquals("  ", AiUrlUtils.trimChatCompletionsPath("  "));
    }
}
