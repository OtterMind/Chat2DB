package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.model.ai.AiRuntimeModel;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.service.ai.IAiModelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModelGatewayServiceTest {

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
        AgentModelGatewayService service = service(runtimeModel());

        var access = service.issue("session", model());
        try (var response = service.forward(
                access.ticket(), "127.0.0.1", "{\"model\":\"gpt-test\",\"input\":\"hello\"}"
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
                access.ticket(), "127.0.0.1", "{\"model\":\"gpt-test\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsRemoteAndModelMismatchedRequests() {
        AgentModelGatewayService service = service(runtimeModel());
        var access = service.issue("session", model());
        byte[] body = "{\"model\":\"other\"}".getBytes(StandardCharsets.UTF_8);

        assertThrows(SecurityException.class, () -> service.forward(access.ticket(), "192.0.2.1", body));
        assertThrows(SecurityException.class, () -> service.forward(access.ticket(), "127.0.0.1", body));
    }

    private AgentModelGatewayService service(AiRuntimeModel runtimeModel) {
        IAiModelConfigService modelService = (IAiModelConfigService) Proxy.newProxyInstance(
                IAiModelConfigService.class.getClassLoader(),
                new Class<?>[] {IAiModelConfigService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("resolveRuntimeModel")) {
                        return runtimeModel;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return new AgentModelGatewayService(
                modelService,
                11837,
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
