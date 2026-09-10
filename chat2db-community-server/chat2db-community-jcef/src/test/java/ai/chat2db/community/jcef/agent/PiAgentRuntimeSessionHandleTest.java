package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelSnapshot;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeInput;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiAgentRuntimeSessionHandleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeTransport transport = new FakeTransport();
    private final List<AgentRuntimeEvent> events = new ArrayList<>();
    private final PiAgentRuntimeSessionHandle handle = new PiAgentRuntimeSessionHandle(
            "session", new AgentRuntimeSessionRef("external-session", "resume"),
            new PiProcessHandle("session", new FakeProcess()), transport,
            new PiEventMapper(), events::add, objectMapper, () -> { }, "chat2db", "gpt");

    @Test
    void startsStreamsCompletesAndSnapshots() throws Exception {
        var start = handle.startRun(runRequest());
        assertEquals("set_model", transport.command);
        transport.complete(objectMapper.createObjectNode());
        assertEquals("prompt", transport.command);
        handle.accept(objectMapper.readTree("{\"type\":\"agent_start\"}"));
        transport.complete(objectMapper.createObjectNode());

        assertEquals("run", start.toCompletableFuture().join().externalRunId());
        assertEquals(AgentRuntimeHealth.BUSY, handle.snapshot().toCompletableFuture().join().health());

        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));
        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_COMPLETED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void preservesTerminalEventBeforePromptAcknowledgement() throws Exception {
        var start = handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));
        transport.complete(objectMapper.createObjectNode());

        assertEquals("run", start.toCompletableFuture().join().externalRunId());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
        assertEquals(null, handle.snapshot().toCompletableFuture().join().activeExternalRunId());
    }

    @Test
    void emitsCancellationAfterAbortIsAcknowledged() throws Exception {
        var start = handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        start.toCompletableFuture().join();

        var cancel = handle.cancel(new AgentRuntimeCancelRequest("session", "run", "run"));
        assertEquals("abort", transport.command);
        transport.complete(objectMapper.createObjectNode());
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

    @Test
    void settledAfterAnAssistantErrorFailsTheRunWithItsRealReason() throws Exception {
        handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        handle.accept(objectMapper.readTree("""
                {"type":"message_end","message":{"role":"assistant","stopReason":"error",
                "errorMessage":"model connection failed","usage":{"input":2,"output":0}}}
                """));
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        assertEquals(List.of(AgentEventType.USAGE_UPDATED, AgentEventType.RUN_FAILED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals("model connection failed", events.get(1).payload().get("error"));
        assertEquals(AgentRuntimeHealth.FAILED, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void aSuccessfulRetryIsNotMarkedFailedAndTrailingEventsDoNotBreakIdleState() throws Exception {
        handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        handle.accept(objectMapper.readTree("""
                {"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"retry"}}
                """));
        handle.accept(objectMapper.readTree("""
                {"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":2,"output":3}}}
                """));
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        assertEquals(List.of(AgentEventType.USAGE_UPDATED, AgentEventType.RUN_COMPLETED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    private static final class FakeTransport implements PiRpcTransport {
        private String command;
        private CompletableFuture<JsonNode> response;
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        @Override public CompletableFuture<JsonNode> request(String command, JsonNode payload) {
            this.command = command;
            this.response = new CompletableFuture<>();
            return response;
        }
        private void complete(JsonNode value) {
            response.complete(value);
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
