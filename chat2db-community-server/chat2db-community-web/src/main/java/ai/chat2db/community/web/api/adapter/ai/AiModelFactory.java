package ai.chat2db.community.web.api.adapter.ai;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.google.cloud.vertexai.VertexAI;
import io.micrometer.observation.ObservationRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.resolution.SpringBeanToolCallbackResolver;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Slf4j
public class AiModelFactory {

    private final ToolCallingManager toolCallingManager;

    public AiModelFactory(GenericApplicationContext applicationContext) {
        ToolCallingManager delegate = DefaultToolCallingManager.builder()
                .observationRegistry(ObservationRegistry.NOOP)
                .toolCallbackResolver(SpringBeanToolCallbackResolver.builder()
                        .applicationContext(applicationContext)
                        .build())
                .build();
        this.toolCallingManager = delegate;
    }

    public AiChatClient create(AiRuntimeModel runtimeModel, RequestMode requestMode) {
        AiProviderEnum provider = AiProviderEnum.from(runtimeModel.getProvider());
        if (runtimeModel.isSystemPreset()) {
            throw new IllegalArgumentException("Community edition requires a user configured AI model.");
        }
        RetryTemplate retryTemplate = createRetryTemplate(requestMode);
        if (provider == AiProviderEnum.OPENAI) {
            return openAiClient(runtimeModel, retryTemplate);
        }
        if (provider == AiProviderEnum.CLAUDE) {
            return claudeClient(runtimeModel, retryTemplate);
        }
        if (provider == AiProviderEnum.GEMINI) {
            return geminiClient(runtimeModel, retryTemplate);
        }
        throw new IllegalArgumentException("Unsupported provider: " + runtimeModel.getProvider());
    }

    static RetryTemplate createRetryTemplate(RequestMode requestMode) {
        return requestMode == RequestMode.STREAMING
                ? RetryTemplate.builder().maxAttempts(1).build()
                : RetryTemplate.defaultInstance();
    }

    private AiChatClient openAiClient(AiRuntimeModel runtimeModel, RetryTemplate retryTemplate) {
        logUpstreamTarget("openai",
                StringUtils.defaultIfBlank(runtimeModel.getBaseUrl(), OpenAiApiConstants.DEFAULT_BASE_URL) + "/v1/chat/completions",
                buildOpenAiHeaderView(runtimeModel, null));
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(runtimeModel.getApiKey());
        if (StringUtils.isNotBlank(runtimeModel.getBaseUrl())) {
            apiBuilder.baseUrl(runtimeModel.getBaseUrl());
            apiBuilder.restClientBuilder(RestClient.builder().requestInterceptor(new ZoerClientHttpRequestInterceptor()));
            apiBuilder.webClientBuilder(WebClient.builder().filter(new WebClientParameterFilter("openai")));

        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(runtimeModel.getModel())
                .internalToolExecutionEnabled(Boolean.TRUE);
        if (Objects.nonNull(runtimeModel.getTemperature())) {
            optionsBuilder.temperature(runtimeModel.getTemperature());
        }
        if (Objects.nonNull(runtimeModel.getMaxTokens())) {
            optionsBuilder.maxTokens(runtimeModel.getMaxTokens());
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(patchOpenAiApiChunkMerger(apiBuilder.build()))
                .defaultOptions(optionsBuilder.build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return new AiChatClient(ChatClient.create(chatModel), () -> {
        });
    }

    private AiChatClient claudeClient(AiRuntimeModel runtimeModel, RetryTemplate retryTemplate) {
        logUpstreamTarget("claude",
                StringUtils.defaultIfBlank(runtimeModel.getBaseUrl(), AnthropicApiConstants.DEFAULT_BASE_URL),
                buildClaudeHeaderView(runtimeModel, null));
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder().apiKey(runtimeModel.getApiKey())
                .anthropicBetaFeatures("")
                .webClientBuilder(WebClient.builder().filter(new WebClientParameterFilter("claude")));
        if (StringUtils.isNotBlank(runtimeModel.getBaseUrl())) {
            apiBuilder.baseUrl(runtimeModel.getBaseUrl());
        }

        AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                .model(runtimeModel.getModel())
                .internalToolExecutionEnabled(Boolean.TRUE);
        if (Objects.nonNull(runtimeModel.getTemperature())) {
            optionsBuilder.temperature(runtimeModel.getTemperature());
        }
        if (Objects.nonNull(runtimeModel.getMaxTokens())) {
            optionsBuilder.maxTokens(runtimeModel.getMaxTokens());
        }

        ChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(apiBuilder.build())
                .defaultOptions(optionsBuilder.build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return new AiChatClient(ChatClient.create(chatModel), () -> {
        });
    }

    private AiChatClient geminiClient(AiRuntimeModel runtimeModel, RetryTemplate retryTemplate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "gemini");
        payload.put("projectId", runtimeModel.getProjectId());
        payload.put("location", runtimeModel.getLocation());
        payload.put("model", runtimeModel.getModel());
        payload.put("temperature", runtimeModel.getTemperature());
        payload.put("maxTokens", runtimeModel.getMaxTokens());
        log.info("ai upstream target: {}", JSON.toJSONString(payload));
        VertexAI vertexAI = new VertexAI(runtimeModel.getProjectId(), runtimeModel.getLocation());

        VertexAiGeminiChatOptions.Builder optionsBuilder = VertexAiGeminiChatOptions.builder()
                .model(runtimeModel.getModel())
                .internalToolExecutionEnabled(true);
        if (Objects.nonNull(runtimeModel.getTemperature())) {
            optionsBuilder.temperature(runtimeModel.getTemperature());
        }
        if (Objects.nonNull(runtimeModel.getMaxTokens())) {
            optionsBuilder.maxOutputTokens(runtimeModel.getMaxTokens());
        }

        ChatModel chatModel = VertexAiGeminiChatModel.builder()
                .vertexAI(vertexAI)
                .defaultOptions(optionsBuilder.build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        return new AiChatClient(ChatClient.create(chatModel), vertexAI::close);
    }


    private OpenAiApi patchOpenAiApiChunkMerger(OpenAiApi openAiApi) {
        try {
            Field field = OpenAiApi.class.getDeclaredField("chunkMerger");
            field.setAccessible(true);
            field.set(openAiApi, new CompatibleOpenAiStreamFunctionCallingHelper());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("patchOpenAiApiChunkMerger failed, fallback to default helper", e);
        }
        return openAiApi;
    }

    private void logUpstreamTarget(String provider, String url, Map<String, Object> headers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", provider);
        payload.put("url", url);
        payload.put("headers", headers);
        log.info("ai upstream target: {}", JSON.toJSONString(payload));
    }

    private Map<String, Object> buildOpenAiHeaderView(AiRuntimeModel runtimeModel,
                                                      Object proxyHeaders) {
        Map<String, Object> headerView = new LinkedHashMap<>();
        headerView.put(HttpHeaders.AUTHORIZATION, maskSecret("Bearer " + runtimeModel.getApiKey()));
        headerView.put(HttpHeaders.CONTENT_TYPE, "application/json");
        return headerView;
    }

    private Map<String, Object> buildClaudeHeaderView(AiRuntimeModel runtimeModel,
                                                      Object proxyHeaders) {
        Map<String, Object> headerView = new LinkedHashMap<>();
        headerView.put("x-api-key", maskSecret(runtimeModel.getApiKey()));
        headerView.put("anthropic-version", "2023-06-01");
        headerView.put(HttpHeaders.CONTENT_TYPE, "application/json");
        return headerView;
    }

    private String maskSecret(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    @Getter
    public static class AiChatClient {
        private final ChatClient chatClient;

        private final Runnable cleanup;

        public AiChatClient(ChatClient chatClient, Runnable cleanup) {
            this.chatClient = chatClient;
            this.cleanup = cleanup;
        }

        public void close() {
            this.cleanup.run();
        }
    }

    public enum RequestMode {
        SYNCHRONOUS,
        STREAMING
    }

    private static final class OpenAiApiConstants {
        private static final String DEFAULT_BASE_URL = "https://api.openai.com";

        private OpenAiApiConstants() {
        }
    }

    private static final class AnthropicApiConstants {
        private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

        private AnthropicApiConstants() {
        }
    }
}
