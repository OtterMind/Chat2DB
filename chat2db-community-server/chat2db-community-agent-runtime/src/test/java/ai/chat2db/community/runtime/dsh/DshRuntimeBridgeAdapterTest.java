package ai.chat2db.community.runtime.dsh;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRun;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DshRuntimeBridgeAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void bundledBridgeDisablesAutomaticBrowserLaunch() throws Exception {
        try (InputStream stream = DshRuntimeBridgeAdapterTest.class.getResourceAsStream(
                "/agent-runtime/dsh-runtime-bridge.mjs")) {
            assertTrue(stream != null);
            String bridge = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(bridge.contains(
                    "args.push('--host', '127.0.0.1', '--port', '0', '--no-open');"));
        }

        ProviderExecutionRequest unsafe = request();
        unsafe.getRuntimeProfile().setCustomArguments(List.of("--no-open"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(new FakeProcess((reader, writer, fake) -> { })).execute(
                        unsafe, ignored -> { }, ignored -> { }));
    }

    @Test
    void executesBridgeProtocolAndMapsSessionStreamingToolsAndUsage() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        FakeProcess process = new FakeProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader);
            requests.add(initialize);
            respond(writer, initialize, object("protocolVersion", "chat2db-dsh-bridge-v1"));
            JsonNode turn = read(reader);
            requests.add(turn);
            notify(writer, "runtime/session-updated", object(
                    "sessionId", "dsh-session-1", "resumed", false));
            notify(writer, "runtime/turn-started", object(
                    "sessionId", "dsh-session-1", "turnId", "1"));
            notify(writer, "runtime/event", object(
                    "type", "MESSAGE_DELTA", "content", "Final report",
                    "payload", object("type", "assistant/chunk")));
            notify(writer, "runtime/event", object(
                    "type", "TOOL_CALL", "content", "query: call-1",
                    "payload", mapper.readTree("""
                            {"event":{"type":"tool/call","data":{"callId":"call-1","name":"query","arguments":"{\\"sql\\":\\"select 1\\"}"}}}
                            """)));
            notify(writer, "runtime/event", object(
                    "type", "TOOL_RESULT", "content", "",
                    "payload", mapper.readTree("""
                            {"event":{"type":"tool/result","data":{"message":{"source":{"kind":"tool","callId":"call-1"},"content":[{"type":"tool-result","toolCallId":"call-1","content":[{"type":"text","text":"one row"}],"isError":false}]}}},"view":{"for":"result","view":{"card":"terminal","output":"one row"}}}
                            """)));
            notify(writer, "runtime/event", object(
                    "type", "USAGE", "content", "DSH token usage updated",
                    "payload", object("inputTokens", 10, "outputTokens", 4)));
            respond(writer, turn, object(
                    "sessionId", "dsh-session-1", "turnId", "1",
                    "finalResponse", "Final report",
                    "usage", object("inputTokens", 10, "outputTokens", 4)));
            fake.awaitDestroy();
        });
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Map<String, String>> environment = new AtomicReference<>();
        DshRuntimeBridgeAdapter adapter = new DshRuntimeBridgeAdapter(mapper,
                (actualCommand, ignored, actualEnvironment) -> {
                    command.set(List.copyOf(actualCommand));
                    environment.set(Map.copyOf(actualEnvironment));
                    return process;
                }, Clock.systemUTC());
        List<AgentRuntimeEvent> events = new ArrayList<>();
        AtomicReference<String> turnId = new AtomicReference<>();

        ProviderExecutionResult result = adapter.execute(request(), events::add,
                new ai.chat2db.community.runtime.provider.ProviderLifecycleSink() {
                    @Override public void started(String runtimeExecutionId) { }
                    @Override public void turnStarted(String runtimeExecutionId, String providerTurnId) {
                        turnId.set(providerTurnId);
                    }
                });

        assertEquals("dsh-session-1", result.getSessionId());
        assertEquals("1", result.getTurnId());
        assertEquals("1", turnId.get());
        assertEquals("Final report", result.getFinalResponse());
        assertEquals(10, ((Number) result.getUsage().get("inputTokens")).intValue());
        assertEquals(List.of(AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                        AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                        AgentRuntimeEventTypeEnum.TOOL_CALL,
                        AgentRuntimeEventTypeEnum.TOOL_RESULT,
                        AgentRuntimeEventTypeEnum.USAGE),
                events.stream().map(AgentRuntimeEvent::getType).toList());
        AgentRuntimeEvent toolCall = events.stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.TOOL_CALL).findFirst().orElseThrow();
        AgentRuntimeEvent toolResult = events.stream()
                .filter(event -> event.getType() == AgentRuntimeEventTypeEnum.TOOL_RESULT).findFirst().orElseThrow();
        assertEquals("call-1", toolCall.getPayload().get("toolCallId"));
        assertEquals("query", toolCall.getPayload().get("name"));
        assertEquals("{\"sql\":\"select 1\"}", toolCall.getPayload().get("arguments"));
        assertEquals("call-1", toolResult.getPayload().get("toolCallId"));
        assertEquals("query", toolResult.getPayload().get("name"));
        assertEquals(Boolean.TRUE, toolResult.getPayload().get("success"));
        assertEquals("one row", toolResult.getContent());
        assertEquals("node", command.get().get(0));
        assertTrue(command.get().get(1).endsWith(".chat2db-dsh-runtime-bridge.mjs"));
        assertEquals("task-secret", environment.get().get("CHAT2DB_AGENT_TASK_TOKEN"));
        assertEquals("/fake/dsh", requests.get(0).path("params").path("executable").asText());
        assertTrue(requests.get(1).path("params").path("prompt").asText().contains("Immutable Context"));
        assertTrue(requests.get(1).path("params").path("prompt").asText()
                .contains("mcp__chat2db_task_tools__"));
        assertTrue(requests.get(1).path("params").path("prompt").asText()
                .contains("never use bash, curl"));
        String patch = Files.readString(workspace.resolve(".chat2db-dsh-mcp.patch.yml"));
        assertTrue(patch.startsWith("- insert:\n    - id: chat2db-mcp-0\n"));
        assertTrue(patch.contains("@deepseek-ai/dsh-mcp-client"));
        assertTrue(patch.contains("toolCallTimeoutMs: 2147000000"));
        assertTrue(patch.contains("process.env.CHAT2DB_AGENT_TASK_TOKEN"));
        assertFalse(patch.contains("task-secret"));
    }

    @Test
    void startsDshInTheCurrentWorkspaceInsteadOfResumingADeletedWorkspace() {
        FakeProcess process = new FakeProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader);
            respond(writer, initialize, object("protocolVersion", "chat2db-dsh-bridge-v1"));
            JsonNode turn = read(reader);
            assertFalse(turn.path("params").has("resumeSessionId"));
            respond(writer, turn, object("sessionId", "new-session", "turnId", "1",
                    "finalResponse", "ok", "usage", mapper.createObjectNode()));
            fake.awaitDestroy();
        });
        ProviderExecutionRequest request = request();
        request.setResumeSessionId("existing-session");
        DshRuntimeBridgeAdapter adapter = adapter(process);

        assertEquals("new-session",
                adapter.execute(request, ignored -> { }, ignored -> { }).getSessionId());

        try (InputStream stream = DshRuntimeBridgeAdapterTest.class.getResourceAsStream(
                "/agent-runtime/dsh-runtime-bridge.mjs")) {
            assertTrue(stream != null);
            String bridge = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(bridge.contains("call('session.history'"));
            assertTrue(bridge.contains("call('session.create', { cwd: params.cwd })"));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }

        ProviderExecutionRequest unsafe = request();
        unsafe.getRuntimeProfile().setCustomArguments(List.of("--host"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(new FakeProcess((reader, writer, fake) -> { })).execute(
                        unsafe, ignored -> { }, ignored -> { }));
    }

    @Test
    void failsImmediatelyWhenBridgeReportsAnError() {
        FakeProcess process = new FakeProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader);
            respond(writer, initialize, object("protocolVersion", "chat2db-dsh-bridge-v1"));
            read(reader);
            notify(writer, "bridge/error", object("message", "DSH web host exited"));
        });

        ProviderExecutionException exception = assertThrows(ProviderExecutionException.class,
                () -> adapter(process).execute(request(), ignored -> { }, ignored -> { }));

        assertEquals(ProviderFailureKind.PROTOCOL_ERROR, exception.getFailureKind());
        assertEquals("DSH web host exited", exception.getMessage());
    }

    private DshRuntimeBridgeAdapter adapter(FakeProcess process) {
        return new DshRuntimeBridgeAdapter(mapper, (command, directory, environment) -> process,
                Clock.systemUTC());
    }

    private ProviderExecutionRequest request() {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setProvider(AgentRuntimeProviderEnum.DSH);
        profile.setExecutable("/fake/dsh");
        profile.setTimeoutSeconds(30);

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

        ProviderMcpEndpoint endpoint = new ProviderMcpEndpoint();
        endpoint.setName("chat2db_task_tools");
        endpoint.setUrl(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"));
        endpoint.setBearerTokenEnvironmentVariable("CHAT2DB_AGENT_TASK_TOKEN");

        ProviderExecutionRequest request = new ProviderExecutionRequest();
        request.setRunId("run-1");
        request.setLeaseAttempt(1);
        request.setRuntimeProfile(profile);
        request.setStartRequest(start);
        request.setWorkingDirectory(workspace);
        request.setEnvironment(Map.of("PATH", "/safe/bin",
                "CHAT2DB_AGENT_TASK_TOKEN", "task-secret"));
        request.setMcpEndpoints(List.of(endpoint));
        request.setApprovalHandler(ignored -> {
            ProviderApprovalDecision decision = new ProviderApprovalDecision();
            decision.setApproved(false);
            return decision;
        });
        return request;
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

    private void notify(BufferedWriter writer, String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        write(writer, notification);
    }

    private void write(BufferedWriter writer, JsonNode node) throws IOException {
        writer.write(mapper.writeValueAsString(node));
        writer.newLine();
        writer.flush();
    }

    private ObjectNode object(Object... values) {
        ObjectNode node = mapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            String key = (String) values[index];
            Object value = values[index + 1];
            if (value instanceof JsonNode json) node.set(key, json);
            else if (value instanceof Boolean bool) node.put(key, bool);
            else if (value instanceof Integer number) node.put(key, number);
            else node.put(key, String.valueOf(value));
        }
        return node;
    }

    @FunctionalInterface
    private interface Script {
        void run(BufferedReader reader, BufferedWriter writer, FakeProcess process) throws Exception;
    }

    private static final class FakeProcess implements ManagedProviderProcess {
        private final PipedInputStream adapterStdout = new PipedInputStream();
        private final PipedOutputStream bridgeStdout;
        private final PipedInputStream bridgeStdin = new PipedInputStream();
        private final PipedOutputStream adapterStdin;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private FakeProcess(Script script) {
            try {
                bridgeStdout = new PipedOutputStream(adapterStdout);
                adapterStdin = new PipedOutputStream(bridgeStdin);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        bridgeStdin, StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                             bridgeStdout, StandardCharsets.UTF_8))) {
                    script.run(reader, writer, this);
                } catch (Exception ignored) {
                    // Test assertions surface through missing or malformed protocol responses.
                }
            }, "fake-dsh-bridge");
            thread.setDaemon(true);
            thread.start();
        }

        private void awaitDestroy() throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (alive.get() && System.nanoTime() < deadline) Thread.sleep(10L);
        }

        @Override public InputStream stdout() { return adapterStdout; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return adapterStdin; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(Duration timeout) { return !alive.get(); }
        @Override public void destroy() { alive.set(false); }
        @Override public void destroyForcibly() { alive.set(false); }
        @Override public long pid() { return 42L; }
        @Override public Instant startInstant() { return Instant.EPOCH; }
    }
}
