package ai.chat2db.server.web.api.controller.ai.tongyi.client;

import ai.chat2db.server.tools.common.exception.ParamBusinessException;
import ai.chat2db.server.web.api.controller.ai.fastchat.interceptor.FastChatHeaderAuthorizationInterceptor;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatMessage;
import ai.chat2db.server.web.api.controller.ai.tongyi.model.TongyiChatCompletionsOptions;
import ai.chat2db.server.web.api.controller.ai.tongyi.model.TongyiChatMessage;
import cn.hutool.http.ContentType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * tongyi Chat Aligned Client
 *
 * @author moji
 */
@Slf4j
public class TongyiChatAIStreamClient {

    /**
     * apikey
     */
    @Getter
    @NotNull
    private String apiKey;

    /**
     * apiHost
     */
    @Getter
    @NotNull
    private String apiHost;

    /**
     * model
     */
    @Getter
    private String model;

    /**
     * embeddingModel
     */
    @Getter
    private String embeddingModel;

    /**
     * okHttpClient
     */
    @Getter
    private OkHttpClient okHttpClient;


    /**
     * @param builder
     */
    private TongyiChatAIStreamClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiHost = builder.apiHost;
        this.model = builder.model;
        this.embeddingModel = builder.embeddingModel;
        if (Objects.isNull(builder.okHttpClient)) {
            builder.okHttpClient = this.okHttpClient();
        }
        okHttpClient = builder.okHttpClient;
    }

    /**
     * okhttpclient
     */
    private OkHttpClient okHttpClient() {
        OkHttpClient okHttpClient = new OkHttpClient
            .Builder()
            .addInterceptor(new FastChatHeaderAuthorizationInterceptor(this.apiKey))
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(50, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build();
        return okHttpClient;
    }

    /**
     * structure
     *
     * @return
     */
    public static TongyiChatAIStreamClient.Builder builder() {
        return new TongyiChatAIStreamClient.Builder();
    }

    /**
     * builder
     */
    public static final class Builder {
        private String apiKey;

        private String apiHost;

        private String model;

        private String embeddingModel;

        /**
         * OkhttpClient
         */
        private OkHttpClient okHttpClient;

        public Builder() {
        }

        public TongyiChatAIStreamClient.Builder apiKey(String apiKeyValue) {
            this.apiKey = apiKeyValue;
            return this;
        }

        /**
         * @param apiHostValue
         * @return
         */
        public TongyiChatAIStreamClient.Builder apiHost(String apiHostValue) {
            this.apiHost = apiHostValue;
            return this;
        }

        /**
         * @param modelValue
         * @return
         */
        public TongyiChatAIStreamClient.Builder model(String modelValue) {
            this.model = modelValue;
            return this;
        }

        public TongyiChatAIStreamClient.Builder embeddingModel(String embeddingModelValue) {
            this.embeddingModel = embeddingModelValue;
            return this;
        }

        public TongyiChatAIStreamClient.Builder okHttpClient(OkHttpClient val) {
            this.okHttpClient = val;
            return this;
        }

        public TongyiChatAIStreamClient build() {
            return new TongyiChatAIStreamClient(this);
        }

    }

    /**
     * Q&A interface stream form
     *
     * @param chatMessages
     * @param eventSourceListener
     */
    public void streamCompletions(List<FastChatMessage> chatMessages, EventSourceListener eventSourceListener) {
        if (CollectionUtils.isEmpty(chatMessages)) {
            log.error("param error：Tongyi Chat Prompt cannot be empty");
            throw new ParamBusinessException("prompt");
        }
        if (Objects.isNull(eventSourceListener)) {
            log.error("param error：TongyiChatEventSourceListener cannot be empty");
            throw new ParamBusinessException();
        }
        log.info("Tongyi Chat AI, prompt:{}", chatMessages.get(chatMessages.size() - 1).getContent());
        try {

            TongyiChatCompletionsOptions chatCompletionsOptions = new TongyiChatCompletionsOptions();
            chatCompletionsOptions.setStream(true);
            chatCompletionsOptions.setModel(this.model);
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("result_format", "text");
            parameters.put("incremental_output", true);
            chatCompletionsOptions.setParameters(parameters);
            TongyiChatMessage tongyiChatMessage = new TongyiChatMessage();
            tongyiChatMessage.setMessages(chatMessages);
            chatCompletionsOptions.setMessages(chatMessages);

            EventSource.Factory factory = EventSources.createFactory(this.okHttpClient);
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            String requestBody = mapper.writeValueAsString(chatCompletionsOptions);
            Request request = new Request.Builder()
                .url(apiHost)
                .post(RequestBody.create(MediaType.parse(ContentType.JSON.getValue()), requestBody))
                .build();
            //Create event
            EventSource eventSource = factory.newEventSource(request, eventSourceListener);
            log.info("finish invoking tongyi chat ai");
        } catch (Exception e) {
            log.error("tongyi chat ai error", e);
            eventSourceListener.onFailure(null, e, null);
            throw new ParamBusinessException();
        }
    }

}
