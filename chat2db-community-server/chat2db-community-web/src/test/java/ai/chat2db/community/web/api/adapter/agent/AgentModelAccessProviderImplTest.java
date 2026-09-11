package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelAccessProviderImplTest {

    private HttpServer upstream;

    @AfterEach
    void tearDown() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void issuesLoopbackAccessAndStreamsWithServerSideCredentials() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = "data: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
        AgentModelAccessProviderImpl service = service(runtimeModel());

        var access = service.issue("session", model());
        try (var response = service.forward(
                access.ticket(), "127.0.0.1", "/v1/responses", Map.of(), "{\"model\":\"gpt-test\",\"input\":\"hello\"}"
                        .getBytes(StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertEquals("text/event-stream", response.contentType());
            assertTrue(new String(response.body().readAllBytes(), StandardCharsets.UTF_8).contains("response.completed"));
        }

        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("chat2db", access.provider());
        assertFalse(access.baseUrl().contains("test-secret"));
        assertFalse(access.baseUrl().contains(access.ticket()));
        service.revoke(access.ticket());
        assertThrows(SecurityException.class, () -> service.forward(
                access.ticket(), "127.0.0.1", "/v1/responses", Map.of(), "{\"model\":\"gpt-test\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void forwardsNativeProtocolPathsBodiesAndCredentialsWithoutConvertingPayloads() throws Exception {
        List<String> received = new ArrayList<>();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/proxy", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String anthropic = exchange.getRequestHeaders().getFirst("x-api-key");
            String google = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            received.add(exchange.getRequestURI() + "|" + auth + "|" + anthropic + "|" + google + "|"
                    + exchange.getRequestHeaders().getFirst("X-Chat2DB-Model-Ticket") + "|"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "data: native-response\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
        for (String api : List.of("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")) {
            AiRuntimeModel model = runtimeModel();
            model.setAgentApi(api);
            model.setBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/proxy");
            var service = service(model);
            var access = service.issue("session", model());
            assertEquals(api, access.api());
            String path = switch (api) {
                case "openai-completions" -> "/v1/chat/completions";
                case "openai-responses" -> "/v1/responses";
                case "anthropic-messages" -> "/v1/messages?beta=true";
                default -> "/v1beta/models/gpt-test:streamGenerateContent?alt=sse";
            };
            String body = api.equals("google-generative-ai") ? "{\"contents\":[{\"parts\":[{\"text\":\"hello\"}]}]}"
                    : "{\"model\":\"gpt-test\",\"native\":{\"keep\":[1,2,3]}}";
            try (var result = service.forward(access.ticket(), "127.0.0.1", path,
                    Map.of("Authorization", "Bearer temporary-ticket", "X-Chat2DB-Model-Ticket", access.ticket()),
                    body.getBytes(StandardCharsets.UTF_8))) {
                assertEquals("data: native-response\n\n", new String(result.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            String auth = api.startsWith("openai-") ? "Bearer test-secret|null|null"
                    : api.equals("anthropic-messages") ? "null|test-secret|null" : "null|null|test-secret";
            assertEquals("/proxy" + path + "|" + auth + "|null|" + body, received.get(received.size() - 1));
            assertThrows(SecurityException.class, () -> service.forward(access.ticket(), "127.0.0.1",
                    "/v1/models", Map.of(), body.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void resolvesNativeProviderDefaultsAndKeepsExistingOpenAiOnResponses() {
        for (var entry : Map.of("OPENAI", "openai-responses", "CLAUDE", "anthropic-messages",
                "GEMINI", "google-generative-ai", "MINIMAX", "openai-completions").entrySet()) {
            AiRuntimeModel configured = runtimeModel();
            configured.setProvider(entry.getKey());
            assertEquals(entry.getValue(), service(configured).issue("session", model()).api());
        }
    }

    @Test
    void rejectsRemoteAndModelMismatchedRequests() {
        AgentModelAccessProviderImpl service = service(runtimeModel());
        var access = service.issue("session", model());
        byte[] body = "{\"model\":\"other\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(SecurityException.class, () -> service.forward(access.ticket(), "192.0.2.1", "/v1/responses", Map.of(), body));
        assertThrows(SecurityException.class, () -> service.forward(access.ticket(), "127.0.0.1", "/v1/responses", Map.of(), body));
    }

    private AgentModelAccessProviderImpl service(AiRuntimeModel runtimeModel) {
        IAiModelConfigService modelService = (IAiModelConfigService) Proxy.newProxyInstance(
                IAiModelConfigService.class.getClassLoader(),
                new Class<?>[] {IAiModelConfigService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("resolveRuntimeModel")) {
                        return runtimeModel;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        AgentGatewayAddress address = new AgentGatewayAddress();
        address.publish(11837);
        return new AgentModelAccessProviderImpl(
                modelService,
                address,
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC),
                new SecureRandom());
    }

    private AiRuntimeModel runtimeModel() {
        AiRuntimeModel model = new AiRuntimeModel();
        model.setProvider("OPENAI");
        model.setModel("gpt-test");
        model.setApiKey("test-secret");
        int port = upstream == null ? 1 : upstream.getAddress().getPort();
        model.setBaseUrl("http://127.0.0.1:" + port + "/v1");
        return model;
    }

    private AgentModelSnapshot model() {
        return new AgentModelSnapshot("model", 1, "OPENAI", "gpt-test", 1000, 100);
    }

}
