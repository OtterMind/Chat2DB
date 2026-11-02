package ai.chat2db.community.tools.util;

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * OpenAI-compatible providers put the API version segment in different places:
 * OpenAI uses "https://api.openai.com/v1" while Zhipu GLM uses
 * "https://open.bigmodel.cn/api/paas/v4". Spring AI's OpenAiApi appends its
 * default "/v1/chat/completions" path to the configured base URL, so blindly
 * keeping that default turns a versioned base URL into
 * ".../v4/v1/chat/completions" and every request fails with 404.
 */
public final class AiUrlUtils {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1" + CHAT_COMPLETIONS_PATH;
    private static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";
    private static final int VERSION_PREFIX_LENGTH = "/v1".length();
    private static final Pattern TRAILING_VERSION_SEGMENT = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    private AiUrlUtils() {
    }

    /**
     * Resolves the completions path to pair with the given base URL: the bare
     * "/chat/completions" when the base URL already carries a version segment
     * ("/v1", "/v3", "/v4", ...), the default "/v1/chat/completions" otherwise.
     */
    public static String resolveCompletionsPath(String baseUrl) {
        return resolveVersionedPath(baseUrl, DEFAULT_COMPLETIONS_PATH);
    }

    /**
     * Resolves the embeddings path to pair with the given base URL, following the
     * same version-segment rule as {@link #resolveCompletionsPath(String)}.
     */
    public static String resolveEmbeddingsPath(String baseUrl) {
        return resolveVersionedPath(baseUrl, DEFAULT_EMBEDDINGS_PATH);
    }

    /**
     * Builds the chat completions endpoint for a connection test. A versioned base
     * URL gets the bare operation path, a plain base URL gets the default
     * "/v1"-prefixed one, and a pasted versioned ".../v1/chat/completions" endpoint
     * collapses back to its base URL instead of being doubled.
     */
    public static String buildChatCompletionsEndpoint(String baseUrl) {
        String base = trimChatCompletionsPath(baseUrl);
        return base + resolveCompletionsPath(base);
    }

    /**
     * Trims a pasted full ".../chat/completions" endpoint back to its base URL so
     * the operation path is never appended twice.
     */
    public static String trimChatCompletionsPath(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return baseUrl;
        }
        String normalized = StringUtils.removeEnd(baseUrl.trim(), "/");
        return StringUtils.removeEnd(normalized, CHAT_COMPLETIONS_PATH);
    }

    private static String resolveVersionedPath(String baseUrl, String versionedPath) {
        if (!endsWithVersionSegment(baseUrl)) {
            return versionedPath;
        }
        return versionedPath.substring(VERSION_PREFIX_LENGTH);
    }

    private static boolean endsWithVersionSegment(String baseUrl) {
        String base = trimChatCompletionsPath(baseUrl);
        if (StringUtils.isBlank(base)) {
            return false;
        }
        return TRAILING_VERSION_SEGMENT.matcher(base).find();
    }
}
