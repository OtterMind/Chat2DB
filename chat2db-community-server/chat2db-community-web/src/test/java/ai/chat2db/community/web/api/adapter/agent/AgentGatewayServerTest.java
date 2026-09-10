package ai.chat2db.community.web.api.adapter.agent;

import ai.chat2db.community.domain.api.service.agent.AgentToolAccessService;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.web.api.model.response.agent.AgentModelGatewayResponse;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentGatewayServerTest {
    @Test
    void bindsDistinctLoopbackPortsWithoutAnApplicationWebServerAndReleasesThem() throws Exception {
        AgentGatewayAddress first = new AgentGatewayAddress();
        AgentGatewayAddress second = new AgentGatewayAddress();
        assertThrows(IllegalStateException.class, first::baseUrl);
        try (AgentGatewayServer a = server(first); AgentGatewayServer b = server(second)) {
            a.start(); b.start();
            assertNotEquals(first.baseUrl(), second.baseUrl());
            assertEquals("127.0.0.1", URI.create(first.baseUrl()).getHost());
            assertTrue(URI.create(first.baseUrl()).getPort() > 0);
            var client = HttpClient.newHttpClient();
            var catalog = client.send(HttpRequest.newBuilder(URI.create(first.baseUrl() + "/api/v3/ai/agent-tools/catalog"))
                    .header("Authorization", "Bearer valid").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, catalog.statusCode());
            assertEquals("[\"db_query\"]", catalog.body());
            var unauthorized = client.send(HttpRequest.newBuilder(URI.create(first.baseUrl() + "/api/v3/ai/agent-tools/catalog"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, unauthorized.statusCode());
            var otherApi = client.send(HttpRequest.newBuilder(URI.create(first.baseUrl() + "/api/connection/datasource/list"))
                    .header("Authorization", "Bearer valid").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, otherApi.statusCode());
        }
        assertThrows(IllegalStateException.class, first::baseUrl);
        assertThrows(IllegalStateException.class, second::baseUrl);
    }

    @Test
    void forwardsModelResponsesOnTheSameRandomListener() throws Exception {
        AgentGatewayAddress address = new AgentGatewayAddress();
        try (AgentGatewayServer server = server(address)) {
            server.start();
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create(address.baseUrl() + "/api/v3/ai/agent-model/v1/responses"))
                    .header("Authorization", "Bearer valid").POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("data: done\n\n", response.body());
        }
    }

    private AgentGatewayServer server(AgentGatewayAddress address) {
        AgentToolAccessService tools = (AgentToolAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AgentToolAccessService.class}, (proxy, method, args) -> {
                    assertEquals("activeTools", method.getName());
                    assertEquals("valid", args[0]);
                    assertEquals("127.0.0.1", args[1]);
                    return List.of("db_query");
                });
        IAgentModelGateway models = new IAgentModelGateway() {
            public AgentModelAccess issue(String session, AgentModelSnapshot model) { throw new AssertionError(); }
            public void revoke(String ticket) { throw new AssertionError(); }
            public AgentModelGatewayResponse forward(String ticket, String remote, byte[] body) {
                assertEquals("valid", ticket);
                assertEquals("127.0.0.1", remote);
                assertEquals("{}", new String(body, StandardCharsets.UTF_8));
                return new AgentModelGatewayResponse(200, "text/event-stream",
                        new ByteArrayInputStream("data: done\n\n".getBytes(StandardCharsets.UTF_8)));
            }
        };
        return new AgentGatewayServer(address, () -> tools, () -> models);
    }
}
