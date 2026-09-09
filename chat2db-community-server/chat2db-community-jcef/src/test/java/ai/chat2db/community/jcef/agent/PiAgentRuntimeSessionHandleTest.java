package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.AgentModelSnapshot;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeHealth;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiAgentRuntimeSessionHandleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeTransport transport = new FakeTransport();
    private final List<AgentRuntimeEvent> events = new ArrayList<>();
    private final PiAgentRuntimeSessionHandle handle = new PiAgentRuntimeSessionHandle(
            "session", new AgentRuntimeSessionRef("external-session", "resume"),
            new PiProcessHandle("session", new FakeProcess()), transport,
            new PiEventMapper(), events::add, objectMapper);

    @Test
    void startsStreamsCompletesAndSnapshots() throws Exception {
        var start = handle.startRun(runRequest());
        assertEquals("prompt", transport.command);
        handle.accept(objectMapper.readTree("{\"type\":\"agent_start\"}"));
        transport.response.complete(objectMapper.readTree("{\"externalRunId\":\"pi-run\"}"));

        assertEquals("pi-run", start.toCompletableFuture().join().externalRunId());
        assertEquals(AgentRuntimeHealth.BUSY, handle.snapshot().toCompletableFuture().join().health());

        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));
        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_COMPLETED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void preservesTerminalEventBeforePromptAcknowledgement() throws Exception {
        var start = handle.startRun(runRequest());
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));
        transport.response.complete(objectMapper.readTree("{}"));

        assertEquals("run", start.toCompletableFuture().join().externalRunId());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
        assertEquals(null, handle.snapshot().toCompletableFuture().join().activeExternalRunId());
    }

    @Test
    void emitsCancellationAfterAbortIsAcknowledged() throws Exception {
        var start = handle.startRun(runRequest());
        transport.response.complete(objectMapper.readTree("{\"externalRunId\":\"pi-run\"}"));
        start.toCompletableFuture().join();
        transport.response = new CompletableFuture<>();

        var cancel = handle.cancel(new AgentRuntimeCancelRequest("session", "run", "pi-run"));
        assertEquals("abort", transport.command);
        transport.response.complete(objectMapper.createObjectNode());
        cancel.toCompletableFuture().join();

        assertEquals(AgentEventType.RUN_CANCELLED, events.get(0).type());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    private AgentRuntimeRunRequest runRequest() {
        return new AgentRuntimeRunRequest(
                "session", "run",
                new AgentModelSnapshot("model", 1, "openai", "gpt", 1000, 100),
                new AgentRuntimeInput("hello", List.of()), "request");
    }

    private static final class FakeTransport implements PiRpcTransport {
        private String command;
        private CompletableFuture<JsonNode> response = new CompletableFuture<>();
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        @Override public CompletableFuture<JsonNode> request(String command, JsonNode payload) {
            this.command = command;
            return response;
        }
        @Override public CompletableFuture<Void> termination() { return termination; }
        @Override public void close() { termination.complete(null); }
    }

    private static final class FakeProcess extends Process {
        @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public java.io.InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
