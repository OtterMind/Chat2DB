package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.util.AgentTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import ai.chat2db.community.web.api.model.response.agent.AgentModelGatewayResponse;
import org.springframework.stereotype.Service;

@Service
public class AgentModelAccessProviderImpl implements IAgentModelGateway {

    static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final Duration TICKET_TTL = Duration.ofHours(2);

    private final IAiModelConfigService modelConfigService;
    private final AgentGatewayAddress address;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    @Autowired
    public AgentModelAccessProviderImpl(
            IAiModelConfigService modelConfigService,
            AgentGatewayAddress address) {
        this(modelConfigService, address,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
                new ObjectMapper(), Clock.systemUTC(), new SecureRandom());
    }

    AgentModelAccessProviderImpl(
            IAiModelConfigService modelConfigService,
            AgentGatewayAddress address,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock,
            SecureRandom secureRandom) {
        this.modelConfigService = modelConfigService;
        this.address = address;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    @Override
    public AgentModelAccess issue(String sessionId, AgentModelSnapshot model) {
        Instant now = Instant.now(clock);
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        AiChatRuntimeResolveRequest request = new AiChatRuntimeResolveRequest();
        request.setModelConfigId(model.modelConfigId());
        request.setProvider(model.provider());
        request.setModel(model.modelId());
        AiRuntimeModel runtimeModel = modelConfigService.resolveRuntimeModel(request);
        if (runtimeModel == null || blank(runtimeModel.getApiKey()) || blank(runtimeModel.getBaseUrl())) {
            throw new IllegalStateException("Agent model credentials are unavailable");
        }
        if (!model.modelId().equals(runtimeModel.getModel())) {
            throw new IllegalStateException("Resolved Agent model does not match its snapshot");
        }
        if (!"OPENAI".equalsIgnoreCase(runtimeModel.getProvider())) {
            throw new IllegalStateException("Pi model gateway currently requires an OpenAI Responses provider");
        }
        String ticket = newTicket();
        tickets.put(ticket, new Ticket(
                sessionId, model.modelId(), runtimeModel.getBaseUrl(), runtimeModel.getApiKey(),
                now.plus(TICKET_TTL)));
        return new AgentModelAccess(
                "chat2db", model.modelId(), "openai-responses",
                address.baseUrl() + "/api/v3/ai/agent-model/v1",
                ticket);
    }

    @Override
    public void revoke(String ticket) {
        if (ticket != null) {
            tickets.remove(ticket);
        }
    }

    @Override
    public AgentModelGatewayResponse forward(String ticketValue, String remoteAddress, byte[] body) throws IOException {
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Agent model request is too large");
        }
        if (!isLoopback(remoteAddress)) {
            throw new SecurityException("Agent model gateway only accepts loopback requests");
        }
        Ticket ticket = tickets.get(ticketValue);
        if (ticket == null || !ticket.expiresAt().isAfter(Instant.now(clock))) {
            tickets.remove(ticketValue);
            throw new SecurityException("Agent model ticket is invalid or expired");
        }
        JsonNode requestBody = objectMapper.readTree(body);
        if (!requestBody.isObject() || !ticket.modelId().equals(requestBody.path("model").asText())) {
            throw new SecurityException("Agent model request does not match its ticket");
        }
        long started = System.nanoTime();
        AgentTrace.record("model.request", ticket.sessionId(), null,
                Map.of("model", ticket.modelId(), "requestBytes", body.length));
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    HttpRequest.newBuilder(responsesUri(ticket.baseUrl()))
                            .timeout(Duration.ofMinutes(10))
                            .header("Authorization", "Bearer " + ticket.apiKey())
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream, application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            java.util.List<String> contentTypes = response.headers().map().get("content-type");
            String contentType = contentTypes == null || contentTypes.isEmpty()
                    ? "application/octet-stream" : contentTypes.get(0);
            AgentTrace.record("model.response.headers", ticket.sessionId(), null,
                    Map.of("status", response.statusCode(), "durationMs", elapsedMillis(started)));
            InputStream monitored = new java.io.FilterInputStream(response.body()) {
                private long bytes;
                private boolean ended;
                private boolean closed;
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = in.read(buffer, offset, length);
                    if (count < 0) ended = true; else bytes += count;
                    return count;
                }
                @Override public int read() throws IOException {
                    int value = in.read();
                    if (value < 0) ended = true; else bytes++;
                    return value;
                }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { super.close(); } finally {
                        AgentTrace.record("model.response.closed", ticket.sessionId(), null,
                                Map.of("bytes", bytes, "complete", ended, "durationMs", elapsedMillis(started)));
                    }
                }
            };
            return new AgentModelGatewayResponse(
                    response.statusCode(),
                    contentType,
                    monitored);
        } catch (IOException error) {
            AgentTrace.record("model.failed", ticket.sessionId(), null,
                    Map.of("errorType", error.getClass().getSimpleName(), "durationMs", elapsedMillis(started)));
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Agent model request was interrupted", error);
        }
    }

    private URI responsesUri(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (normalized.endsWith("/responses")) {
            return URI.create(normalized);
        }
        return URI.create(normalized.endsWith("/v1")
                ? normalized + "/responses"
                : normalized + "/v1/responses");
    }

    private long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException error) {
            return false;
        }
    }

    private String newTicket() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Ticket(
            String sessionId,
            String modelId,
            String baseUrl,
            String apiKey,
            Instant expiresAt) {
    }

}
