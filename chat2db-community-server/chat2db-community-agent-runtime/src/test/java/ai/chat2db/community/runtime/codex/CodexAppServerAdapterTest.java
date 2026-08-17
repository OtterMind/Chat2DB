package ai.chat2db.community.runtime.codex;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderFailureKind;
import ai.chat2db.community.runtime.provider.ProviderMcpEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAppServerAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void executesCurrentAppServerProtocolAndConvertsMessagesToolsUsageAndSession() {
        List<JsonNode> requests = new ArrayList<>();
        FakeCodexProcess process = new FakeCodexProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); requests.add(initialize);
            respond(writer, initialize, mapper.createObjectNode().put("userAgent", "codex-cli/0.147.0"));
            requests.add(read(reader)); // initialized notification
            JsonNode threadStart = read(reader); requests.add(threadStart);
            respond(writer, threadStart, object("thread", object("id", "thread-1")));
            JsonNode turnStart = read(reader); requests.add(turnStart);
            respond(writer, turnStart, object("turn", object("id", "turn-1")));
            notify(writer, "item/agentMessage/delta", object("threadId", "thread-1",
                    "turnId", "turn-1", "itemId", "message-1", "delta", "Final report"));
            notify(writer, "item/started", object("threadId", "thread-1", "turnId", "turn-1",
                    "startedAtMs", 1L, "item", object("id", "tool-1", "type", "mcpToolCall")));
            notify(writer, "item/completed", object("threadId", "thread-1", "turnId", "turn-1",
                    "completedAtMs", 2L, "item", object("id", "tool-1", "type", "mcpToolCall")));
            ObjectNode usage = object("inputTokens", 10L, "cachedInputTokens", 2L,
                    "outputTokens", 4L, "reasoningOutputTokens", 1L, "totalTokens", 14L);
            ObjectNode tokenUsage = object("last", usage, "total", usage);
            tokenUsage.putNull("modelContextWindow");
            notify(writer, "thread/tokenUsage/updated", object("threadId", "thread-1",
                    "turnId", "turn-1", "tokenUsage", tokenUsage));
            notify(writer, "turn/completed", object("threadId", "thread-1",
                    "turn", object("id", "turn-1", "status", "completed", "items", mapper.createArrayNode())));
            fake.awaitDestroy();
        });
        List<AgentRuntimeEvent> events = new ArrayList<>();
        CodexAppServerAdapter adapter = adapter(process);

        AtomicReference<String> startedExecution = new AtomicReference<>();
        ProviderExecutionResult result = adapter.execute(request(30), events::add, startedExecution::set);

        assertEquals("thread-1", result.getSessionId());
        assertTrue(startedExecution.get().startsWith("codex-process-"));
        assertEquals("turn-1", result.getTurnId());
        assertEquals("Final report", result.getFinalResponse());
        assertEquals(14, ((Number) ((Map<?, ?>) result.getUsage().get("total")).get("totalTokens")).intValue());
        assertEquals(List.of(
                        AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                        AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                        AgentRuntimeEventTypeEnum.TOOL_CALL,
                        AgentRuntimeEventTypeEnum.TOOL_RESULT,
                        AgentRuntimeEventTypeEnum.USAGE),
                events.stream().map(AgentRuntimeEvent::getType).toList());
        assertTrue(requests.get(0).path("params").path("capabilities")
                .path("experimentalApi").asBoolean());
        JsonNode threadParams = requests.get(2).path("params");
        assertEquals("read-only", threadParams.path("sandbox").asText());
        assertEquals("never", threadParams.path("approvalPolicy").asText());
        JsonNode mcp = threadParams.path("config").path("mcp_servers").path("chat2db_task_tools");
        assertEquals("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1",
                mcp.path("url").asText());
        assertEquals("CHAT2DB_AGENT_TASK_TOKEN", mcp.path("bearer_token_env_var").asText());
        JsonNode turnParams = requests.get(3).path("params");
        assertTrue(turnParams.path("input").get(0).path("text").asText().contains("Immutable Context"));
        assertFalse(turnParams.path("input").get(0).path("text").asText().contains("password"));
        assertEquals(List.of("/fake/codex", "app-server", "--listen", "stdio://", "-c", "feature=true"),
                process.command.get());
        assertEquals(Map.of("PATH", "/safe/bin", "CHAT2DB_AGENT_TASK_TOKEN", "task-secret"),
                process.environment.get());
    }

    @Test
    void resumesExistingCodexThreadInsteadOfCreatingAnotherSession() {
        List<String> methods = new ArrayList<>();
        FakeCodexProcess process = successfulProcess(methods, true);
        ProviderExecutionRequest request = request(30);
        request.setResumeSessionId("thread-existing");

        ProviderExecutionResult result = adapter(process).execute(request, ignored -> { }, ignored -> { });

        assertEquals("thread-existing", result.getSessionId());
        assertTrue(methods.contains("thread/resume"));
        assertFalse(methods.contains("thread/start"));
    }

    @Test
    void cancellationInterruptsOnlyTheTrackedTurnAndTerminatesItsProcess() throws Exception {
        AtomicReference<JsonNode> interrupt = new AtomicReference<>();
        CountDownLatch turnStarted = new CountDownLatch(1);
        FakeCodexProcess process = new FakeCodexProcess((reader, writer, fake) -> {
            handshake(reader, writer, "thread-cancel", "turn-cancel", false);
            JsonNode request = read(reader);
            interrupt.set(request);
            respond(writer, request, mapper.createObjectNode());
            fake.awaitDestroy();
        });
        CodexAppServerAdapter adapter = adapter(process);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                adapter.execute(request(30), ignored -> { }, new ai.chat2db.community.runtime.provider.ProviderLifecycleSink() {
                    @Override public void started(String runtimeExecutionId) { }
                    @Override public void turnStarted(String runtimeExecutionId, String turnId) {
                        turnStarted.countDown();
                    }
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        execution.start();
        assertTrue(turnStarted.await(5, TimeUnit.SECONDS));

        adapter.cancel("run-1");
        execution.join(5_000L);

        assertFalse(execution.isAlive());
        assertEquals("turn/interrupt", interrupt.get().path("method").asText());
        assertEquals("thread-cancel", interrupt.get().path("params").path("threadId").asText());
        assertEquals("turn-cancel", interrupt.get().path("params").path("turnId").asText());
        assertEquals(ProviderFailureKind.CANCELLED,
                ((ProviderExecutionException) failure.get()).getFailureKind());
        assertFalse(process.isAlive());
    }

    @Test
    void classifiesUnexpectedProcessExitAndInactivityTimeout() {
        FakeCodexProcess exited = new FakeCodexProcess((reader, writer, fake) -> {
            handshake(reader, writer, "thread-exit", "turn-exit", false);
            fake.exit(17);
        });
        ProviderExecutionException exitFailure = assertThrows(ProviderExecutionException.class,
                () -> adapter(exited).execute(request(30), ignored -> { }, ignored -> { }));
        assertEquals(ProviderFailureKind.PROCESS_EXIT, exitFailure.getFailureKind());

        FakeCodexProcess inactive = new FakeCodexProcess((reader, writer, fake) -> {
            handshake(reader, writer, "thread-timeout", "turn-timeout", false);
            fake.awaitDestroy();
        });
        ProviderExecutionException timeoutFailure = assertThrows(ProviderExecutionException.class,
                () -> adapter(inactive).execute(request(0), ignored -> { }, ignored -> { }));
        assertEquals(ProviderFailureKind.INACTIVITY_TIMEOUT, timeoutFailure.getFailureKind());
        assertFalse(inactive.isAlive());
    }

    @Test
    void rejectsTransportOverrideAndNonAbsoluteExecutable() {
        ProviderExecutionRequest request = request(30);
        request.getRuntimeProfile().setExecutable("codex");
        assertThrows(IllegalArgumentException.class,
                () -> adapter(successfulProcess(new ArrayList<>(), false)).execute(
                        request, ignored -> { }, ignored -> { }));

        request.getRuntimeProfile().setExecutable("/fake/codex");
        request.getRuntimeProfile().setCustomArguments(List.of("--listen"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(successfulProcess(new ArrayList<>(), false)).execute(
                        request, ignored -> { }, ignored -> { }));
    }

    private CodexAppServerAdapter adapter(FakeCodexProcess process) {
        return new CodexAppServerAdapter(mapper, (command, workingDirectory, environment) -> {
            process.command.set(List.copyOf(command));
            process.environment.set(Map.copyOf(environment));
            return process;
        }, Clock.systemUTC());
    }

    private ProviderExecutionRequest request(int timeoutSeconds) {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setProvider(AgentRuntimeProviderEnum.CODEX);
        profile.setExecutable("/fake/codex");
        profile.setModel("gpt-5-codex");
        profile.setTimeoutSeconds(timeoutSeconds);
        profile.setCustomArguments(List.of("-c", "feature=true"));

        AgentDefinition agent = new AgentDefinition();
        agent.setId("agent-1");
        agent.setName("Analysis Agent");
        AgentTask task = new AgentTask();
        task.setId("task-1");
        task.setTitle("Analyze channels");
        AgentRun run = new AgentRun();
        run.setId("run-1");
        AgentRuntimeStartRequest start = new AgentRuntimeStartRequest();
        start.setAgent(agent);
        start.setTask(task);
        start.setRun(run);
        start.setAssembledContext("Immutable Context");

        ProviderExecutionRequest request = new ProviderExecutionRequest();
        request.setRunId("run-1");
        request.setLeaseAttempt(1);
        request.setRuntimeProfile(profile);
        request.setStartRequest(start);
        request.setWorkingDirectory(workspace);
        request.setEnvironment(Map.of("PATH", "/safe/bin",
                "CHAT2DB_AGENT_TASK_TOKEN", "task-secret"));
        ProviderMcpEndpoint endpoint = new ProviderMcpEndpoint();
        endpoint.setName("chat2db_task_tools");
        endpoint.setUrl(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"));
        endpoint.setBearerTokenEnvironmentVariable("CHAT2DB_AGENT_TASK_TOKEN");
        request.setMcpEndpoints(List.of(endpoint));
        return request;
    }

    private FakeCodexProcess successfulProcess(List<String> methods, boolean resume) {
        return new FakeCodexProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); methods.add(initialize.path("method").asText());
            respond(writer, initialize, mapper.createObjectNode().put("userAgent", "codex-test"));
            methods.add(read(reader).path("method").asText());
            JsonNode thread = read(reader); methods.add(thread.path("method").asText());
            String threadId = resume ? "thread-existing" : "thread-new";
            respond(writer, thread, object("thread", object("id", threadId)));
            JsonNode turn = read(reader); methods.add(turn.path("method").asText());
            respond(writer, turn, object("turn", object("id", "turn-ok")));
            notify(writer, "turn/completed", object("threadId", threadId,
                    "turn", object("id", "turn-ok", "status", "completed", "items", mapper.createArrayNode())));
            fake.awaitDestroy();
        });
    }

    private void handshake(BufferedReader reader, BufferedWriter writer,
                           String threadId, String turnId, boolean resume) throws Exception {
        JsonNode initialize = read(reader);
        respond(writer, initialize, mapper.createObjectNode().put("userAgent", "codex-test"));
        read(reader);
        JsonNode thread = read(reader);
        assertEquals(resume ? "thread/resume" : "thread/start", thread.path("method").asText());
        respond(writer, thread, object("thread", object("id", threadId)));
        JsonNode turn = read(reader);
        respond(writer, turn, object("turn", object("id", turnId)));
    }

    private JsonNode read(BufferedReader reader) throws IOException {
        return mapper.readTree(reader.readLine());
    }

    private void respond(BufferedWriter writer, JsonNode request, JsonNode result) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        response.set("result", result);
        write(writer, response);
    }

    private void notify(BufferedWriter writer, String method, ObjectNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        write(writer, notification);
    }

    private void write(BufferedWriter writer, JsonNode message) throws IOException {
        writer.write(mapper.writeValueAsString(message));
        writer.newLine();
        writer.flush();
    }

    private ObjectNode object(Object... values) {
        ObjectNode node = mapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            String name = String.valueOf(values[index]);
            Object value = values[index + 1];
            if (value instanceof JsonNode json) node.set(name, json);
            else if (value instanceof String string) node.put(name, string);
            else if (value instanceof Long number) node.put(name, number);
            else if (value instanceof Integer number) node.put(name, number);
            else throw new IllegalArgumentException("unsupported test JSON value");
        }
        return node;
    }

    @FunctionalInterface
    private interface ServerScript {
        void run(BufferedReader reader, BufferedWriter writer, FakeCodexProcess process) throws Exception;
    }

    private static final class FakeCodexProcess implements ManagedProviderProcess {
        private final PipedInputStream adapterStdout = new PipedInputStream();
        private final PipedOutputStream serverStdout;
        private final PipedInputStream serverStdin = new PipedInputStream();
        private final PipedOutputStream adapterStdin;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger exitCode = new AtomicInteger();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<List<String>> command = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> environment = new AtomicReference<>();

        private FakeCodexProcess(ServerScript script) {
            try {
                serverStdout = new PipedOutputStream(adapterStdout);
                adapterStdin = new PipedOutputStream(serverStdin);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            Thread server = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        serverStdin, StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             serverStdout, StandardCharsets.UTF_8))) {
                    script.run(reader, writer, this);
                } catch (Exception exception) {
                    if (alive.get()) {
                        exit(99);
                    }
                }
            }, "fake-codex-app-server");
            server.setDaemon(true);
            server.start();
        }

        private void awaitDestroy() throws InterruptedException {
            exited.await(10, TimeUnit.SECONDS);
        }

        private void exit(int code) {
            if (alive.compareAndSet(true, false)) {
                exitCode.set(code);
                try { serverStdout.close(); } catch (IOException ignored) { }
                exited.countDown();
            }
        }

        @Override public InputStream stdout() { return adapterStdout; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return adapterStdin; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int waitFor() throws InterruptedException { exited.await(); return exitCode.get(); }
        @Override public boolean waitFor(Duration timeout) throws InterruptedException {
            return exited.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        @Override public void destroy() { exit(0); }
        @Override public void destroyForcibly() { exit(137); }
    }
}
