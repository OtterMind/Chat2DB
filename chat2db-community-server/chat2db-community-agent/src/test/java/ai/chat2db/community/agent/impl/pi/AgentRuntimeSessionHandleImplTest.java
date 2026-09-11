package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.converter.pi.PiEventConverter;
import ai.chat2db.community.agent.pi.IPiModelConfiguration;
import ai.chat2db.community.agent.pi.IPiRpcTransport;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeSessionHandleImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeTransport transport = new FakeTransport();
    private final List<AgentRuntimeEvent> events = new ArrayList<>();
    private final AgentRuntimeSessionHandleImpl handle = new AgentRuntimeSessionHandleImpl(
            "session", new AgentRuntimeSessionRef("external-session", "resume"),
            new PiProcessHandle("session", new FakeProcess()), transport,
            new PiEventConverter(), events::add, objectMapper, () -> { }, new IPiModelConfiguration() {
                @Override public AgentModelAccess prepare(AgentModelSnapshot model) {
                    return new AgentModelAccess("chat2db", model.modelId(), "openai-responses", "http://127.0.0.1/v1", "ticket");
                }
                @Override public void close() { }
            });

    @Test
    void startsStreamsCompletesAndSnapshots() throws Exception {
        var start = handle.startRun(runRequest());
        assertEquals("prompt", transport.command);
        assertEquals("/chat2db-refresh-model", transport.payload.path("message").asText());
        transport.complete(objectMapper.createObjectNode());
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
    void putsSkillCommandBeforeTheCompleteRenderedPrompt() {
        String prompt = "<chat2db_context>context</chat2db_context>\n<user_request>画图</user_request>";
        var started = handle.startRun(new AgentRuntimeRunRequest("session", "run", runRequest().model(),
                new AgentRuntimeInput(prompt, List.of(), "chart"), "request"));
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        assertEquals("/skill:chart " + prompt, transport.payload.path("message").asText());
        transport.complete(objectMapper.createObjectNode());
        started.toCompletableFuture().join();
    }

    @Test
    void preservesTerminalEventBeforePromptAcknowledgement() throws Exception {
        var start = handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
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
        transport.complete(objectMapper.createObjectNode());
        handle.accept(objectMapper.readTree("""
                {"type":"message_end","message":{"role":"assistant","stopReason":"error",
                "errorMessage":"model connection failed","usage":{"input":2,"output":0}}}
                """));
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        assertEquals(List.of(AgentEventType.USAGE_UPDATED, AgentEventType.RUN_FAILED),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals("model connection failed", events.get(1).payload().get("error"));
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void aSuccessfulRetryIsNotMarkedFailedAndTrailingEventsDoNotBreakIdleState() throws Exception {
        handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
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

    @Test
    void changesModelOnTheSameHandleAndDoesNotSendPromptWhenRefreshFails() throws Exception {
        var first = handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        first.toCompletableFuture().join();
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        var second = handle.startRun(new AgentRuntimeRunRequest("session", "run-two",
                new AgentModelSnapshot("second", 1, "CLAUDE", "claude", 1000, 100),
                new AgentRuntimeInput("continue with the prior context", List.of()), "second-request"));
        transport.complete(objectMapper.createObjectNode());
        assertEquals("claude", transport.payload.path("modelId").asText());
        transport.complete(objectMapper.createObjectNode());
        assertEquals("continue with the prior context", transport.payload.path("message").asText());
        transport.complete(objectMapper.createObjectNode());
        second.toCompletableFuture().join();
        handle.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        var rejected = handle.startRun(runRequest());
        handle.accept(objectMapper.readTree("""
                {"type":"extension_error","extensionPath":"command:chat2db-refresh-model",
                 "event":"command","error":"invalid model configuration"}
                """));
        int count = transport.commands.size();
        transport.complete(objectMapper.createObjectNode());
        assertThrows(CompletionException.class,
                () -> rejected.toCompletableFuture().join());
        assertEquals(count, transport.commands.size());
        assertEquals(AgentRuntimeHealth.READY, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void cancellationDuringModelSelectionNeverSendsTheUserPrompt() {
        var start = handle.startRun(runRequest());
        var refreshing = transport.response;
        var cancellation = handle.cancel(new AgentRuntimeCancelRequest("session", "run", "run"));
        transport.complete(objectMapper.createObjectNode());
        cancellation.toCompletableFuture().join();
        int count = transport.commands.size();
        refreshing.complete(objectMapper.createObjectNode());
        assertThrows(CompletionException.class,
                () -> start.toCompletableFuture().join());
        assertEquals(count, transport.commands.size());
    }

    private static final class FakeTransport implements IPiRpcTransport {
        private String command;
        private JsonNode payload;
        private final List<String> commands = new ArrayList<>();
        private CompletableFuture<JsonNode> response;
        private final CompletableFuture<Void> termination = new CompletableFuture<>();
        @Override public CompletableFuture<JsonNode> request(String command, JsonNode payload) {
            this.command = command;
            this.payload = payload;
            this.commands.add(command);
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
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
