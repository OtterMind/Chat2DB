package ai.chat2db.community.web.api.adapter.ai;


import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


public class WebClientParameterFilter implements ExchangeFilterFunction {


    private static final Logger log = LoggerFactory.getLogger(WebClientParameterFilter.class);
    private static final DefaultDataBufferFactory DEFAULT_DATA_BUFFER_FACTORY = new DefaultDataBufferFactory();
    private String model;
    private final Map<String, String> reasoningContentByToolCallId = new ConcurrentHashMap<>();
    private final AtomicReference<String> latestToolCallReasoningContent = new AtomicReference<>();

    public WebClientParameterFilter(String model) {
        this.model = model;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (request.method() != HttpMethod.POST && request.method() != HttpMethod.PUT) {
            return next.exchange(request);
        }
        ClientRequest.Builder builder = ClientRequest.from(request);
        BodyInserter<?, ? super ClientHttpRequest> bodyInserter = request.body();
        BodyInserter<?, ? super ClientHttpRequest> newBodyInserter = (outputMessage, context) -> {
            BodyInserterProxyMessage proxyMessage = new BodyInserterProxyMessage(outputMessage);
            return bodyInserter.insert(proxyMessage, context)
                    .then(Mono.defer(() -> {
                        byte[] capturedBody = proxyMessage.getBody();
                        if (capturedBody.length == 0) {
                            return Mono.empty();
                        }
                        String bodyString = new String(capturedBody, StandardCharsets.UTF_8);
                        String modifiedBody = modifyRequestBody(bodyString);
                        log.info("Original Request Url: {}", request.url().toString());
                        log.info("Original Request Header Summary: {}", JSON.toJSONString(summarizeHeaders(request.headers())));
                        log.info("Original Request Body Summary: {}", JSON.toJSONString(summarizeBody(bodyString)));
                        DataBufferFactory bufferFactory = outputMessage.bufferFactory();
                        DataBuffer buffer = bufferFactory.wrap(modifiedBody.getBytes(StandardCharsets.UTF_8));
                        HttpHeaders headers = outputMessage.getHeaders();
                        headers.setContentLength(buffer.readableByteCount());
                        return outputMessage.writeWith(Mono.just(buffer));
                    }));
        };
        ClientRequest modifiedRequest = builder.body(newBodyInserter).build();
        return next.exchange(modifiedRequest)
                .map(response -> {
                    log.info("Original Response Status: {}", response.statusCode());
                    log.info("Original Response Header Summary: {}",
                            JSON.toJSONString(summarizeHeaders(response.headers().asHttpHeaders())));
                    if (response.statusCode().isError()) {
                        return response;
                    }
                    AtomicReference<StringBuilder> responseBodyBuilder = new AtomicReference<>(new StringBuilder());
                    AtomicReference<StringBuilder> claudeSseBuffer = new AtomicReference<>(new StringBuilder());
                    Flux<DataBuffer> bodyFlux = response.bodyToFlux(DataBuffer.class)
                            .concatMap(buffer -> {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                DataBufferUtils.release(buffer);

                                String chunk = new String(bytes, StandardCharsets.UTF_8);
                                responseBodyBuilder.get().append(chunk);

                                if (!"claude".equalsIgnoreCase(model)) {
                                    return Flux.just(DEFAULT_DATA_BUFFER_FACTORY.wrap(bytes));
                                }

                                return Flux.fromIterable(rewriteClaudeStreamChunk(chunk, claudeSseBuffer.get()))
                                        .map(this::toDataBuffer);
                            })
                            .concatWith(Flux.defer(() -> {
                                if (!"claude".equalsIgnoreCase(model)) {
                                    return Flux.empty();
                                }
                                return Flux.fromIterable(flushClaudeStreamBuffer(claudeSseBuffer.get()))
                                        .map(this::toDataBuffer);
                            }))
                            .doOnError(error -> {
                                String partialResponseBody = responseBodyBuilder.get().toString();
                                log.error("Original Response Body,error:{},summary:{}",
                                        error.getMessage(), JSON.toJSONString(summarizeBody(partialResponseBody)), error);
                            })
                            .doFinally(signalType -> {
                                if (signalType == SignalType.ON_ERROR) {
                                    return;
                                }
                                String fullResponseBody = responseBodyBuilder.get().toString();
                                rememberOpenAiReasoningForToolCalls(fullResponseBody);
                                log.info("Original Response Body,signal:{},summary:{}", signalType,
                                        JSON.toJSONString(summarizeBody(fullResponseBody)));
                            })
                            .concatWith(Flux.defer(() -> {
                                String body = responseBodyBuilder.get().toString();
                                if (!body.isEmpty() && !body.contains("data:")) {
                                    try {
                                        JsonNode jsonNode = objectMapper.readTree(body);
                                        JsonNode successNode = jsonNode.get("success");
                                        if (successNode != null && !successNode.asBoolean(true)) {
                                            return Flux.error(new RuntimeException(body));
                                        }
                                    } catch (Exception ignored) {
                                    }
                                }
                                return Flux.empty();
                            }));
                    return ClientResponse.from(response)
                            .body(bodyFlux)
                            .build();
                });
    }

    String modifyRequestBody(String originalBody) {
        try {
            if (!"claude".equalsIgnoreCase(model)) {
                JsonNode jsonNode = objectMapper.readTree(originalBody);
                if (jsonNode instanceof ObjectNode) {
                    removeEmptyExtraBody(jsonNode);
                    injectReasoningContentForToolCallMessages(jsonNode);
                    if(originalBody.contains(TOO_MANY_CALLS_MESSAGE)){
                        if (jsonNode instanceof ObjectNode) {
                            removeTools(jsonNode);
                        }
                    }
                }
                return objectMapper.writeValueAsString(jsonNode);
            } else {
                JsonNode jsonNode = objectMapper.readTree(originalBody);
                if (jsonNode instanceof ObjectNode) {
                    removeEmptyExtraBody(jsonNode);
                    JsonNode message = jsonNode.get("system");
                    if(originalBody.contains(TOO_MANY_CALLS_MESSAGE)){
                       removeTools(jsonNode);
                    }
                    if (message != null && message.isArray()) {
                        for (JsonNode msgNode : message) {
                            if (msgNode.has("text")) {
                                if (msgNode instanceof ObjectNode) {
                                    ObjectNode msgObjectNode = (ObjectNode) msgNode;
                                    ObjectMapper objectMapper = new ObjectMapper();
                                    ObjectNode userNode = objectMapper.createObjectNode();
                                    userNode.put("type", "ephemeral");
                                    msgObjectNode.set("cache_control", userNode);
                                }
                            }
                        }
                    } else if (message != null && message.isTextual()) {
                        ObjectMapper objectMapper = new ObjectMapper();
                        ArrayNode systemArray = objectMapper.createArrayNode();

                        ObjectNode item1 = objectMapper.createObjectNode();
                        item1.put("type", "text");
                        item1.put("text", message.asText());
                        ObjectNode userNode = objectMapper.createObjectNode();
                        userNode.put("type", "ephemeral");
                        item1.put("cache_control", userNode);
                        systemArray.add(item1);
                        ((ObjectNode) jsonNode).set("system", systemArray);
                    }
                }
                return objectMapper.writeValueAsString(jsonNode);
            }
        } catch (Exception e) {
            return originalBody;
        }
    }

    void rememberOpenAiReasoningForToolCalls(String responseBody) {
        if ("claude".equalsIgnoreCase(model) || isBlank(responseBody)) {
            return;
        }

        StringBuilder reasoningContent = new StringBuilder();
        List<String> toolCallIds = new ArrayList<>();
        for (String payload : extractOpenAiResponsePayloads(responseBody)) {
            if (isBlank(payload) || "[DONE]".equals(payload.trim())) {
                continue;
            }
            collectReasoningAndToolCallIds(payload, reasoningContent, toolCallIds);
        }

        String reasoning = reasoningContent.toString();
        if (isBlank(reasoning)) {
            return;
        }

        latestToolCallReasoningContent.set(reasoning);
        for (String toolCallId : toolCallIds) {
            if (!isBlank(toolCallId)) {
                reasoningContentByToolCallId.put(toolCallId, reasoning);
            }
        }
    }

    private List<String> extractOpenAiResponsePayloads(String responseBody) {
        String normalized = responseBody.replace("\r\n", "\n");
        if (!normalized.contains("data:")) {
            return List.of(normalized);
        }

        List<String> payloads = new ArrayList<>();
        String[] events = normalized.split("\n\n");
        for (String event : events) {
            StringBuilder data = new StringBuilder();
            for (String line : event.split("\n")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
            if (data.length() > 0) {
                payloads.add(data.toString());
            }
        }
        return payloads;
    }

    private void collectReasoningAndToolCallIds(String payload, StringBuilder reasoningContent,
                                                List<String> toolCallIds) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray()) {
                return;
            }
            for (JsonNode choice : choices) {
                collectFromOpenAiMessageNode(choice.get("delta"), reasoningContent, toolCallIds);
                collectFromOpenAiMessageNode(choice.get("message"), reasoningContent, toolCallIds);
            }
        } catch (Exception e) {
            log.debug("collectReasoningAndToolCallIds ignored invalid payload", e);
        }
    }

    private void collectFromOpenAiMessageNode(JsonNode messageNode, StringBuilder reasoningContent,
                                              List<String> toolCallIds) {
        if (messageNode == null || !messageNode.isObject()) {
            return;
        }

        String reasoning = firstText(messageNode, "reasoning_content", "reasoningContent");
        if (!isBlank(reasoning)) {
            reasoningContent.append(reasoning);
        }

        JsonNode toolCalls = messageNode.get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        for (JsonNode toolCall : toolCalls) {
            String id = firstText(toolCall, "id");
            if (!isBlank(id) && !toolCallIds.contains(id)) {
                toolCallIds.add(id);
            }
        }
    }

    private void injectReasoningContentForToolCallMessages(JsonNode jsonNode) {
        if (!(jsonNode instanceof ObjectNode objectNode)) {
            return;
        }
        JsonNode messages = objectNode.get("messages");
        if (messages == null || !messages.isArray()) {
            return;
        }

        List<ObjectNode> missingReasoningMessages = new ArrayList<>();
        boolean injectedByToolCallId = false;
        for (JsonNode message : messages) {
            if (!(message instanceof ObjectNode messageObject) || !isAssistantToolCallMessage(messageObject)
                    || hasReasoningContent(messageObject)) {
                continue;
            }

            String reasoning = resolveReasoningContentForToolCallMessage(messageObject);
            if (!isBlank(reasoning)) {
                messageObject.put("reasoning_content", reasoning);
                injectedByToolCallId = true;
            } else {
                missingReasoningMessages.add(messageObject);
            }
        }

        String latestReasoning = latestToolCallReasoningContent.get();
        if (!injectedByToolCallId && missingReasoningMessages.size() == 1 && !isBlank(latestReasoning)) {
            missingReasoningMessages.get(0).put("reasoning_content", latestReasoning);
        }
    }

    private boolean isAssistantToolCallMessage(ObjectNode messageObject) {
        if (!"assistant".equalsIgnoreCase(firstText(messageObject, "role"))) {
            return false;
        }
        JsonNode toolCalls = messageObject.get("tool_calls");
        return toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();
    }

    private boolean hasReasoningContent(ObjectNode messageObject) {
        return !isBlank(firstText(messageObject, "reasoning_content", "reasoningContent"));
    }

    private String resolveReasoningContentForToolCallMessage(ObjectNode messageObject) {
        JsonNode toolCalls = messageObject.get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            return null;
        }
        for (JsonNode toolCall : toolCalls) {
            String id = firstText(toolCall, "id");
            String reasoning = isBlank(id) ? null : reasoningContentByToolCallId.get(id);
            if (!isBlank(reasoning)) {
                return reasoning;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    List<Map<String, Object>> summarizeHeaders(org.springframework.http.HttpHeaders headers) {
        List<Map<String, Object>> summary = new ArrayList<>();
        headers.forEach((key, values) -> {
            if (values == null) {
                return;
            }
            for (String value : values) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", key);
                item.put("length", value == null ? 0 : value.length());
                item.put("preview", truncateForLog(maskHeaderValueForLog(key, value)));
                summary.add(item);
            }
        });
        return summary;
    }

    Map<String, Object> summarizeBody(String body) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("length", body == null ? 0 : body.length());
        if (isBlank(body)) {
            return summary;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.isObject()) {
                summary.put("format", "json");
                putTextMetadata(summary, root, "model");
                putBooleanMetadata(summary, root, "stream");
                putArraySize(summary, root, "messages", "messageCount");
                putArraySize(summary, root, "tools", "toolCount");
                return summary;
            }
        } catch (Exception ignored) {
            // SSE and partial responses are summarized by shape only.
        }
        summary.put("format", body.contains("data:") ? "sse" : "text");
        return summary;
    }

    private void putTextMetadata(Map<String, Object> summary, JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value != null && value.isTextual()) {
            summary.put(fieldName, truncateForLog(value.asText()));
        }
    }

    private void putBooleanMetadata(Map<String, Object> summary, JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value != null && value.isBoolean()) {
            summary.put(fieldName, value.asBoolean());
        }
    }

    private void putArraySize(Map<String, Object> summary, JsonNode root, String fieldName, String summaryName) {
        JsonNode value = root.get(fieldName);
        if (value != null && value.isArray()) {
            summary.put(summaryName, value.size());
        }
    }


    private String maskHeaderValueForLog(String key, String value) {
        if (isBlank(value) || !isSensitiveHeader(key)) {
            return value;
        }
        if (HttpHeaders.COOKIE.equalsIgnoreCase(key)) {
            return maskCookieHeader(value);
        }
        return maskSecret(value);
    }

    private boolean isSensitiveHeader(String key) {
        if (key == null) {
            return false;
        }
        String lowerKey = key.toLowerCase();
        return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(key)
                || HttpHeaders.COOKIE.equalsIgnoreCase(key)
                || lowerKey.contains("token")
                || lowerKey.contains("key")
                || lowerKey.contains("secret");
    }

    private String maskCookieHeader(String cookieHeader) {
        String[] cookieItems = cookieHeader.split("; ");
        StringBuilder masked = new StringBuilder();
        for (String cookieItem : cookieItems) {
            int index = cookieItem.indexOf('=');
            String name = index >= 0 ? cookieItem.substring(0, index) : cookieItem;
            String value = index >= 0 ? cookieItem.substring(index + 1) : "";
            if (masked.length() > 0) {
                masked.append("; ");
            }
            masked.append(name).append("=").append(maskSecret(value));
        }
        return masked.toString();
    }

    private String maskSecret(String value) {
        if (isBlank(value)) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String truncateForLog(String value) {
        if (value == null) {
            return null;
        }
        int maxLength = 512;
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, total=" + value.length() + ")";
    }

    public static final String TOO_MANY_CALLS_MESSAGE = "TOO_MANY_CALLS_MESSAGE_EXCEEDED_LIMIT";
    private void removeTools(JsonNode jsonNode) {
        JsonNode toolsNode = jsonNode.get("tools");
        if (toolsNode != null && toolsNode.isArray() && jsonNode.isObject()) {
            ((ObjectNode) jsonNode).putArray("tools");
        }
    }

    private void removeEmptyExtraBody(JsonNode jsonNode) {
        if (!(jsonNode instanceof ObjectNode objectNode)) {
            return;
        }
        JsonNode extraBody = objectNode.get("extra_body");
        if (extraBody != null && extraBody.isObject() && extraBody.isEmpty()) {
            objectNode.remove("extra_body");
        }
    }

    List<String> rewriteClaudeStreamChunk(String chunk, StringBuilder pendingSse) {
        pendingSse.append(chunk);
        List<String> events = new ArrayList<>();
        int boundaryIndex;
        while ((boundaryIndex = findSseBoundary(pendingSse)) >= 0) {
            int delimiterLength = pendingSse.indexOf("\r\n\r\n") == boundaryIndex ? 4 : 2;
            String event = pendingSse.substring(0, boundaryIndex + delimiterLength);
            pendingSse.delete(0, boundaryIndex + delimiterLength);
            events.add(normalizeClaudeMalformedMessageStartEvent(event));
        }
        return events;
    }

    List<String> flushClaudeStreamBuffer(StringBuilder pendingSse) {
        if (pendingSse.length() == 0) {
            return List.of();
        }
        String event = pendingSse.toString();
        pendingSse.setLength(0);
        return List.of(normalizeClaudeMalformedMessageStartEvent(event));
    }

    private int findSseBoundary(StringBuilder pendingSse) {
        String value = pendingSse.toString();
        int unixBoundary = value.indexOf("\n\n");
        int windowsBoundary = value.indexOf("\r\n\r\n");
        if (unixBoundary < 0) {
            return windowsBoundary;
        }
        if (windowsBoundary < 0) {
            return unixBoundary;
        }
        return Math.min(unixBoundary, windowsBoundary);
    }

    private String normalizeClaudeMalformedMessageStartEvent(String event) {
        if (!event.contains("\"type\":\"message_start\"") || !event.contains("\"content\":[[]]")) {
            return event;
        }

        int dataIndex = event.indexOf("data:");
        if (dataIndex < 0) {
            return event;
        }

        int jsonStart = dataIndex + 5;
        while (jsonStart < event.length()) {
            char current = event.charAt(jsonStart);
            if (current != ' ' && current != '\t') {
                break;
            }
            jsonStart++;
        }

        int lineEnd = event.indexOf('\n', jsonStart);
        if (lineEnd < 0) {
            lineEnd = event.length();
        }
        if (lineEnd > jsonStart && event.charAt(lineEnd - 1) == '\r') {
            lineEnd--;
        }

        String originalJson = event.substring(jsonStart, lineEnd);
        String normalizedJson = normalizeClaudeMalformedMessageStartJson(originalJson);
        if (originalJson.equals(normalizedJson)) {
            return event;
        }

        return event.substring(0, jsonStart) + normalizedJson + event.substring(lineEnd);
    }

    private String normalizeClaudeMalformedMessageStartJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ObjectNode rootObject)) {
                return json;
            }
            if (!"message_start".equals(rootObject.path("type").asText())) {
                return json;
            }

            JsonNode messageNode = rootObject.get("message");
            if (!(messageNode instanceof ObjectNode messageObject)) {
                return json;
            }

            JsonNode contentNode = messageObject.get("content");
            if (contentNode == null || !contentNode.isArray() || contentNode.size() != 1) {
                return json;
            }

            JsonNode firstItem = contentNode.get(0);
            if (!firstItem.isArray() || !firstItem.isEmpty()) {
                return json;
            }

            messageObject.set("content", objectMapper.createArrayNode());
            log.warn("Normalized malformed claude message_start event content from [[]] to []");
            return objectMapper.writeValueAsString(rootObject);
        } catch (Exception e) {
            log.debug("normalizeClaudeMalformedMessageStartJson ignored invalid payload", e);
            return json;
        }
    }

    private DataBuffer toDataBuffer(String value) {
        return DEFAULT_DATA_BUFFER_FACTORY.wrap(value.getBytes(StandardCharsets.UTF_8));
    }
    private static class BodyInserterProxyMessage implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        public BodyInserterProxyMessage(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }

        public byte[] getBody() {
            return baos.toByteArray();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return delegate.bufferFactory();
        }

        @Override
        public void beforeCommit(Supplier<? extends Mono<Void>> action) {

        }

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return Flux.from(body)
                    .doOnNext(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        try {
                            baos.write(bytes);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .then();
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMap(p -> p));
        }

        @Override
        public Mono<Void> setComplete() {
            return delegate.setComplete();
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public <T> T getNativeRequest() {
            return delegate.getNativeRequest();
        }
    }
}
