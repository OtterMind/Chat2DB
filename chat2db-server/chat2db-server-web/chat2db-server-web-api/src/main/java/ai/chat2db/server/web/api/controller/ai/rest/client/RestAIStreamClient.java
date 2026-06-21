package ai.chat2db.server.web.api.controller.ai.rest.client;

import ai.chat2db.server.tools.common.exception.ParamBusinessException;
import ai.chat2db.server.web.api.controller.ai.fastchat.interceptor.FastChatHeaderAuthorizationInterceptor;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatCompletionsOptions;
import ai.chat2db.server.web.api.controller.ai.fastchat.model.FastChatMessage;
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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Custom AI interface client
 * @author moji
 */
@Slf4j
public class RestAIStreamClient {
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
     * okHttpClient
     */
    @Getter
    private OkHttpClient okHttpClient;

    /**
     * Construct instance object
     *
     * @param builder
     */
    public RestAIStreamClient(Builder builder) {
        this.apiKey = builder.apiKey;
        this.apiHost = builder.apiHost;
        this.model = builder.model;
        this.okHttpClient = new OkHttpClient
            .Builder()
            .addInterceptor(new FastChatHeaderAuthorizationInterceptor(this.apiKey))
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(50, TimeUnit.SECONDS)
            .readTimeout(50, TimeUnit.SECONDS)
            .build();
    }

    /**
     * structure
     *
     * @return
     */
    public static RestAIStreamClient.Builder builder() {
        return new RestAIStreamClient.Builder();
    }

    /**
     * builder
     */
    public static final class Builder {
        private String apiKey;

        private String apiHost;

        private String model;


        /**
         * OkhttpClient
         */
        private OkHttpClient okHttpClient;

        public Builder() {
        }

        public RestAIStreamClient.Builder apiKey(String apiKeyValue) {
            this.apiKey = apiKeyValue;
            return this;
        }

        /**
         * @param apiHostValue
         * @return
         */
        public RestAIStreamClient.Builder apiHost(String apiHostValue) {
            this.apiHost = apiHostValue;
            return this;
        }

        /**
         * @param modelValue
         * @return
         */
        public RestAIStreamClient.Builder model(String modelValue) {
            this.model = modelValue;
            return this;
        }


        public RestAIStreamClient.Builder okHttpClient(OkHttpClient val) {
            this.okHttpClient = val;
            return this;
        }

        public RestAIStreamClient build() {
            return new RestAIStreamClient(this);
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
            log.error("param error：Rest AI Prompt cannot be empty");
            throw new ParamBusinessException("prompt");
        }
        if (Objects.isNull(eventSourceListener)) {
            log.error("param error：RestAIEventSourceListener cannot be empty");
            throw new ParamBusinessException();
        }
        log.info("Rest AI, prompt:{}", chatMessages.get(chatMessages.size() - 1).getContent());
        try {

            FastChatCompletionsOptions chatCompletionsOptions = new FastChatCompletionsOptions(chatMessages);
            chatCompletionsOptions.setStream(true);
            chatCompletionsOptions.setModel(this.model);

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
            log.info("finish invoking rest ai");
        } catch (Exception e) {
            log.error("rest ai error", e);
            eventSourceListener.onFailure(null, e, null);
            throw new ParamBusinessException();
        }
    }


}
