package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeLeaseStatus;
import ai.chat2db.community.domain.api.model.request.agent.AgentRuntimeRunStartedRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeControlPlaneClientTest {

    @Test
    void sendsDaemonAndLeaseCredentialsOverLoopbackAndUnwrapsResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> leaseHeader = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agent/runtime/daemon/runs/run-1/started", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            leaseHeader.set(exchange.getRequestHeaders().getFirst("X-Chat2DB-Agent-Run-Lease"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"success\":true,\"data\":{\"runId\":\"run-1\",\"leaseRevision\":2}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgentRuntimeControlPlaneClient client = new AgentRuntimeControlPlaneClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                    "daemon-secret");
            AgentRuntimeRunStartedRequest request = new AgentRuntimeRunStartedRequest();
            request.setDaemonId("daemon-1");
            request.setLeaseAttempt(1);
            request.setExpectedLeaseRevision(1L);
            request.setRuntimeExecutionId("thread-1");

            AgentRuntimeLeaseStatus result = client.started("run-1", "lease-secret", request);

            assertEquals(2L, result.getLeaseRevision());
            assertEquals("Bearer daemon-secret", authorization.get());
            assertEquals("lease-secret", leaseHeader.get());
            org.junit.jupiter.api.Assertions.assertTrue(body.get().contains("\"runtimeExecutionId\":\"thread-1\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonLoopbackControlPlaneAndUnsafePathIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeControlPlaneClient(
                URI.create("http://192.0.2.1:10825/"), "token"));
        AgentRuntimeControlPlaneClient client = new AgentRuntimeControlPlaneClient(
                URI.create("http://127.0.0.1:10825/"), "token");
        assertThrows(IllegalArgumentException.class,
                () -> client.started("../other-run", "lease", new AgentRuntimeRunStartedRequest()));
        assertEquals(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"),
                client.resolveTaskMcpEndpoint("/api/agent/runtime/mcp/runs/run-1"));
        assertThrows(IllegalArgumentException.class,
                () -> client.resolveTaskMcpEndpoint("//example.com/api/agent/runtime/mcp/runs/run-1"));
        assertThrows(IllegalArgumentException.class,
                () -> client.resolveTaskMcpEndpoint("/api/agent/runtime/daemon/runs/run-1"));
    }
}
