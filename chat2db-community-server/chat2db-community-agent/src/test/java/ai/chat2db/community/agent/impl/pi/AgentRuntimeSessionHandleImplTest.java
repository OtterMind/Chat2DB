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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void recordsDurationForSuccessfulAndFailedToolCalls() throws Exception {
        handle.startRun(runRequest());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        for (String id : List.of("read", "query")) {
            handle.accept(objectMapper.readTree(
                    "{\"type\":\"tool_execution_start\",\"toolCallId\":\"" + id + "\"}"));
        }
        handle.accept(objectMapper.readTree(
                "{\"type\":\"tool_execution_end\",\"toolCallId\":\"query\",\"isError\":true,\"result\":{}}"));
        handle.accept(objectMapper.readTree(
                "{\"type\":\"tool_execution_end\",\"toolCallId\":\"read\",\"result\":{}}"));
        assertEquals(AgentEventType.TOOL_CALL_FAILED, events.get(2).type());
        assertEquals(AgentEventType.TOOL_CALL_COMPLETED, events.get(3).type());
        for (var event : events.subList(2, 4)) {
            long duration = ((Number) event.payload().get("durationMs")).longValue();
            assertTrue(duration >= 0);
            assertEquals(duration, objectMapper.valueToTree(event.payload()).path("result").path("durationMs").asLong());
        }
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

    @Test
    void reloadsChangedSkillPathsAndVerifiesThemBeforeSendingTheNextPrompt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var entry = java.nio.file.Files.writeString(directory.resolve("first.md"), "first").toRealPath();
        var nextEntry = java.nio.file.Files.writeString(directory.resolve("second.md"), "second").toRealPath();
        var first = new ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill("example", entry.toString(), "one");
        var second = new ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill("example", nextEntry.toString(), "two");
        var config = new PiSkillConfiguration(directory, objectMapper, List.of(first));
        var runtime = new AgentRuntimeSessionHandleImpl("session", new AgentRuntimeSessionRef("external", "resume"),
                new PiProcessHandle("session", new FakeProcess()), transport, new PiEventConverter(), events::add,
                objectMapper, () -> {}, new IPiModelConfiguration() {
                    public AgentModelAccess prepare(AgentModelSnapshot model) {
                        return new AgentModelAccess("chat2db", model.modelId(), "openai-responses", "http://127.0.0.1/v1", "ticket");
                    }
                    public void close() { }
                }, () -> {}, config);
        var request = new AgentRuntimeRunRequest("session", "first-run", runRequest().model(), runRequest().input(), "first", List.of(first));
        var started = runtime.startRun(request);
        assertEquals("get_commands", transport.command);
        transport.complete(skillCommands(first));
        assertEquals("/chat2db-refresh-model", transport.payload.path("message").asText());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        started.toCompletableFuture().join();
        runtime.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        var changed = runtime.startRun(new AgentRuntimeRunRequest("session", "next-run", runRequest().model(),
                new AgentRuntimeInput("continue", List.of(), "example"), "next", List.of(second)));
        assertEquals("/chat2db-reload-skills", transport.payload.path("message").asText());
        assertEquals(nextEntry.toString(), objectMapper.readTree(directory.resolve("skills.json").toFile()).get(0).path("entryPath").asText());
        transport.complete(objectMapper.createObjectNode());
        assertEquals("get_commands", transport.command);
        transport.complete(skillCommands(second));
        transport.complete(objectMapper.createObjectNode());
        transport.complete(objectMapper.createObjectNode());
        assertEquals("/skill:example continue", transport.payload.path("message").asText());
        transport.complete(objectMapper.createObjectNode());
        changed.toCompletableFuture().join();
        runtime.accept(objectMapper.readTree("{\"type\":\"agent_settled\"}"));

        var failed = runtime.startRun(new AgentRuntimeRunRequest("session", "bad-run", runRequest().model(),
                runRequest().input(), "bad", List.of(first)));
        transport.complete(objectMapper.createObjectNode());
        transport.complete(skillCommands(second));
        assertThrows(CompletionException.class, () -> failed.toCompletableFuture().join());
        assertEquals("get_commands", transport.command);
        assertEquals(AgentRuntimeHealth.FAILED, runtime.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void cancellationWaitsForPendingResourceCommandsBeforeReusingTheProcess(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var entry = java.nio.file.Files.writeString(directory.resolve("skill.md"), "skill").toRealPath();
        var skill = new ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill("example", entry.toString(), "one");
        var config = new PiSkillConfiguration(directory, objectMapper, List.of());
        var runtime = new AgentRuntimeSessionHandleImpl("session", new AgentRuntimeSessionRef("external", "resume"),
                new PiProcessHandle("session", new FakeProcess()), transport, new PiEventConverter(), events::add,
                objectMapper, () -> {}, new IPiModelConfiguration() {
                    public AgentModelAccess prepare(AgentModelSnapshot model) {
                        return new AgentModelAccess("chat2db", model.modelId(), "openai-responses", "http://127.0.0.1/v1", "ticket");
                    }
                    public void close() { }
                }, () -> {}, config);
        var started = runtime.startRun(new AgentRuntimeRunRequest("session", "run", runRequest().model(),
                runRequest().input(), "first", List.of(skill)));
        var pendingReload = transport.response;
        var cancelled = runtime.cancel(new AgentRuntimeCancelRequest("session", "run", "run")).toCompletableFuture();
        transport.complete(objectMapper.createObjectNode());
        assertFalse(cancelled.isDone());
        assertEquals(AgentRuntimeHealth.BUSY, runtime.snapshot().toCompletableFuture().join().health());
        pendingReload.complete(objectMapper.createObjectNode());
        cancelled.join();
        assertTrue(started.toCompletableFuture().isCompletedExceptionally());
        assertEquals(List.of(AgentEventType.RUN_CANCELLED), events.stream().map(AgentRuntimeEvent::type).toList());
        assertEquals(AgentRuntimeHealth.READY, runtime.snapshot().toCompletableFuture().join().health());
        runtime.startRun(new AgentRuntimeRunRequest("session", "retry", runRequest().model(), runRequest().input(), "retry", List.of(skill)));
        assertEquals("/chat2db-reload-skills", transport.payload.path("message").asText());
    }

    private JsonNode skillCommands(ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSkill skill) {
        var response = objectMapper.createObjectNode();
        response.putArray("commands").addObject()
                .put("source", "skill").put("name", "skill:" + skill.name()).putObject("sourceInfo").put("path", skill.entryPath());
        return response;
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

    @Test
    void reportsUnexpectedRuntimeTerminationAndMarksHandleFailed() {
        transport.termination.completeExceptionally(new RuntimeException("Pi exited"));

        assertEquals(AgentRuntimeHealth.FAILED, handle.snapshot().toCompletableFuture().join().health());
        assertTrue(events.isEmpty());
    }

    @Test
    void emitsUnknownOutcomeWhenRuntimeTerminatesDuringRun() {
        handle.startRun(runRequest());
        transport.termination.completeExceptionally(new RuntimeException("Pi exited"));

        assertEquals(AgentEventType.RUN_OUTCOME_UNKNOWN, events.get(0).type());
        assertEquals(AgentRuntimeHealth.FAILED, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void settlesTheRunBeforeTerminationObserversCloseTheHandle() {
        handle.startRun(runRequest());
        handle.termination().whenComplete((ignored, error) -> {
            assertEquals(List.of(AgentEventType.RUN_OUTCOME_UNKNOWN),
                    events.stream().map(AgentRuntimeEvent::type).toList());
            handle.close();
        });

        transport.termination.completeExceptionally(new RuntimeException("Pi exited"));

        assertEquals(List.of(AgentEventType.RUN_OUTCOME_UNKNOWN),
                events.stream().map(AgentRuntimeEvent::type).toList());
        assertTrue(handle.termination().toCompletableFuture().isCompletedExceptionally());
        assertEquals(AgentRuntimeHealth.STOPPED, handle.snapshot().toCompletableFuture().join().health());
    }

    @Test
    void invokesEventsAndTerminationObserversWithoutHoldingTheHandleMonitor() throws Exception {
        var reference = new java.util.concurrent.atomic.AtomicReference<AgentRuntimeSessionHandleImpl>();
        List<AgentRuntimeEvent> delivered = new ArrayList<>();
        AgentRuntimeSessionHandleImpl observed = new AgentRuntimeSessionHandleImpl(
                "session", new AgentRuntimeSessionRef("external-session", null),
                new PiProcessHandle("session", new FakeProcess()), transport,
                new PiEventConverter(), event -> {
                    assertFalse(Thread.holdsLock(reference.get()));
                    delivered.add(event);
                }, objectMapper, () -> assertFalse(Thread.holdsLock(reference.get())),
                new IPiModelConfiguration() {
                    @Override public AgentModelAccess prepare(AgentModelSnapshot model) {
                        return new AgentModelAccess("chat2db", model.modelId(), "openai-responses", "http://127.0.0.1/v1", "ticket");
                    }
                    @Override public void close() { }
                });
        reference.set(observed);
        observed.termination().whenComplete((ignored, error) -> assertFalse(Thread.holdsLock(observed)));
        observed.startRun(runRequest());
        observed.accept(objectMapper.readTree("{\"type\":\"agent_start\"}"));
        observed.cancel(new AgentRuntimeCancelRequest("session", "run", "run"));
        transport.complete(objectMapper.createObjectNode());
        observed.startRun(runRequest());
        transport.termination.completeExceptionally(new RuntimeException("Pi exited"));
        observed.close();
        observed.close();

        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_CANCELLED,
                AgentEventType.RUN_OUTCOME_UNKNOWN), delivered.stream().map(AgentRuntimeEvent::type).toList());
    }

    @Test
    void snapshotAndRuntimeCallbacksDoNotDeadlockWithTheDomainCoordinator() throws Exception {
        for (boolean terminate : List.of(false, true)) {
            var domainLock = new java.util.concurrent.locks.ReentrantLock();
            var coordinatorEntered = new java.util.concurrent.CountDownLatch(1);
            var eventEntered = new java.util.concurrent.CountDownLatch(1);
            FakeTransport rpc = new FakeTransport();
            List<AgentRuntimeEvent> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
            AgentRuntimeSessionHandleImpl observed = new AgentRuntimeSessionHandleImpl(
                    "session", new AgentRuntimeSessionRef("external-session", null),
                    new PiProcessHandle("session", new FakeProcess()), rpc,
                    new PiEventConverter(), event -> {
                        eventEntered.countDown();
                        // Interruptible only so a regression can be cleaned up after a timeout.
                        // This has the same ownership as the domain coordinator's monitor.
                        try {
                            domainLock.lockInterruptibly();
                            try { delivered.add(event); } finally { domainLock.unlock(); }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted blocked domain callback", error);
                        }
                    }, objectMapper, () -> { }, new IPiModelConfiguration() {
                        @Override public AgentModelAccess prepare(AgentModelSnapshot model) {
                            return new AgentModelAccess("chat2db", model.modelId(), "openai-responses", "http://127.0.0.1/v1", "ticket");
                        }
                        @Override public void close() { }
                    });
            observed.startRun(runRequest());
            // This is the registry's cleanup callback, attached after the handle's callback.
            observed.termination().whenComplete((ignored, error) -> observed.close());
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<?> runtime = null;
            try {
                var snapshot = executor.submit(() -> {
                    domainLock.lock();
                    try {
                        coordinatorEntered.countDown();
                        assertTrue(eventEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                        return observed.snapshot().toCompletableFuture().join();
                    } finally {
                        domainLock.unlock();
                    }
                });
                assertTrue(coordinatorEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                JsonNode started = objectMapper.readTree("{\"type\":\"agent_start\"}");
                runtime = executor.submit(() -> {
                    if (terminate) rpc.termination.completeExceptionally(new RuntimeException("Pi exited"));
                    else observed.accept(started);
                });
                snapshot.get(2, java.util.concurrent.TimeUnit.SECONDS);
                runtime.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(terminate ? AgentEventType.RUN_OUTCOME_UNKNOWN : AgentEventType.RUN_STARTED,
                        delivered.get(0).type());
                if (terminate) assertEquals(AgentRuntimeHealth.STOPPED,
                        observed.snapshot().toCompletableFuture().join().health());
            } finally {
                if (runtime != null) runtime.cancel(true);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
                observed.close();
            }
        }
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
