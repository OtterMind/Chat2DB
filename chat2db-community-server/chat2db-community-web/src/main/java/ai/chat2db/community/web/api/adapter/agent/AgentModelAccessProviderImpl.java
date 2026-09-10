package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.enums.ai.AiAgentModelApi;
import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.request.ai.AiChatRuntimeResolveRequest;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.agent.runtime.IAgentModelAccessProvider;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.util.AgentTrace;
import ai.chat2db.community.web.api.model.response.agent.AgentModelGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
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
        if (runtimeModel == null || blank(runtimeModel.getApiKey())) {
            throw new IllegalStateException("Agent model credentials are unavailable");
        }
        if (!model.modelId().equals(runtimeModel.getModel())) {
            throw new IllegalStateException("Resolved Agent model does not match its snapshot");
        }
        AiAgentModelApi api = AiAgentModelApi.resolve(runtimeModel.getAgentApi(), runtimeModel.getProvider(), runtimeModel.getBaseUrl());
        String ticket = newTicket();
        tickets.put(ticket, new Ticket(
                sessionId, model.modelId(), api, runtimeModel.getBaseUrl(), runtimeModel.getApiKey(),
                now.plus(TICKET_TTL)));
        return new AgentModelAccess(
                "chat2db", model.modelId(), api.getCode(),
                address.baseUrl() + "/api/v3/ai/agent-model"
                        + (api == AiAgentModelApi.GOOGLE_GENERATIVE_AI ? "/v1beta"
                        : api == AiAgentModelApi.OPENAI_COMPLETIONS || api == AiAgentModelApi.OPENAI_RESPONSES ? "/v1" : ""),
                ticket);
    }

    @Override
    public void revoke(String ticket) {
        if (ticket != null) {
            tickets.remove(ticket);
        }
    }

    @Override
    public AgentModelGatewayResponse forward(String ticketValue, String remoteAddress, String path, Map<String, String> headers, byte[] body) throws IOException {
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
        if (requestBody == null || !requestBody.isObject()
                || ticket.api() != AiAgentModelApi.GOOGLE_GENERATIVE_AI && !ticket.modelId().equals(requestBody.path("model").asText())) {
            throw new SecurityException("Agent model request does not match its ticket");
        }
        URI endpoint = endpoint(ticket, path);
        long started = System.nanoTime();
        AgentTrace.record("model.request", ticket.sessionId(), null,
                Map.of("model", ticket.modelId(), "requestBytes", body.length));
        try {
            HttpRequest.Builder upstream = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json");
            switch (ticket.api()) {
                case OPENAI_COMPLETIONS, OPENAI_RESPONSES -> upstream.header("Authorization", "Bearer " + ticket.apiKey());
                case ANTHROPIC_MESSAGES -> {
                    upstream.header("x-api-key", ticket.apiKey());
                    upstream.header("anthropic-version", headers.getOrDefault("anthropic-version", "2023-06-01"));
                    if (headers.containsKey("anthropic-beta")) upstream.header("anthropic-beta", headers.get("anthropic-beta"));
                }
                case GOOGLE_GENERATIVE_AI -> upstream.header("x-goog-api-key", ticket.apiKey());
            }
            if (headers.containsKey("OpenAI-Beta")) upstream.header("OpenAI-Beta", headers.get("OpenAI-Beta"));
            HttpResponse<InputStream> response = httpClient.send(
                    upstream.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            List<String> contentTypes = response.headers().map().get("content-type");
            String contentType = contentTypes == null || contentTypes.isEmpty()
                    ? "application/octet-stream" : contentTypes.get(0);
            AgentTrace.record("model.response.headers", ticket.sessionId(), null,
                    Map.of("status", response.statusCode(), "durationMs", elapsedMillis(started)));
            InputStream monitored = new FilterInputStream(response.body()) {
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

    private URI endpoint(Ticket ticket, String path) {
        URI local = URI.create(path);
        if (local.isAbsolute() || local.getRawAuthority() != null || local.getRawFragment() != null) {
            throw new SecurityException("Invalid model request path");
        }
        String requested = local.getPath();
        String suffix;
        String defaultBase;
        switch (ticket.api()) {
            case OPENAI_RESPONSES -> { suffix = "/responses"; defaultBase = "https://api.openai.com"; }
            case OPENAI_COMPLETIONS -> { suffix = "/chat/completions"; defaultBase = "https://api.openai.com"; }
            case ANTHROPIC_MESSAGES -> { suffix = "/messages"; defaultBase = "https://api.anthropic.com"; }
            case GOOGLE_GENERATIVE_AI -> {
                String model = ticket.modelId().startsWith("models/") ? ticket.modelId().substring(7) : ticket.modelId();
                String expected = "/v1beta/models/" + model;
                boolean streaming = (expected + ":streamGenerateContent").equals(requested);
                if (!streaming && !(expected + ":generateContent").equals(requested)) {
                    throw new SecurityException("Gemini model request does not match its ticket");
                }
                String encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
                String base = blank(ticket.baseUrl()) ? "https://generativelanguage.googleapis.com" : stripSlash(ticket.baseUrl());
                if (!base.matches(".*/v1(?:beta|alpha)?$")) base += "/v1beta";
                return URI.create(base + "/models/" + encodedModel + (streaming ? ":streamGenerateContent?alt=sse" : ":generateContent"));
            }
            default -> throw new IllegalStateException("Unsupported Agent protocol");
        }
        boolean beta = ticket.api() == AiAgentModelApi.ANTHROPIC_MESSAGES && "beta=true".equals(local.getRawQuery());
        if (!("/v1" + suffix).equals(requested) || local.getRawQuery() != null && !beta) {
            throw new SecurityException("Model API request does not match its ticket");
        }
        String base = blank(ticket.baseUrl()) ? defaultBase : stripSlash(ticket.baseUrl());
        String target = base.endsWith(suffix) ? base : base + (base.endsWith("/v1") ? "" : "/v1") + suffix;
        return URI.create(target + (beta ? "?beta=true" : ""));
    }

    private String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
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
            AiAgentModelApi api,
            String baseUrl,
            String apiKey,
            Instant expiresAt) {
    }

}
