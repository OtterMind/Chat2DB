package ai.chat2db.community.runtime.hermes;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderExecutionException;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderFailureKind;
import ai.chat2db.community.runtime.provider.ProviderLifecycleSink;
import ai.chat2db.community.runtime.provider.ProviderMcpEndpoint;
import ai.chat2db.community.runtime.provider.ProviderApprovalDecision;
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

class HermesAcpAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void allowsSlowHermesSessionInitialization() {
        assertEquals(Duration.ofSeconds(60), HermesAcpAdapter.SESSION_START_TIMEOUT);
        assertTrue(HermesAcpAdapter.SESSION_START_TIMEOUT.compareTo(
                HermesAcpAdapter.HANDSHAKE_TIMEOUT) > 0);
    }

    @Test
    void executesAcpSessionAndConvertsEventsMcpUsageAndPermissionWithoutYolo() {
        List<JsonNode> requests = new ArrayList<>();
        FakeHermesProcess process = new FakeHermesProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); requests.add(initialize);
            respond(writer, initialize, object("agentInfo", object("name", "Hermes")));
            JsonNode session = read(reader); requests.add(session);
            respond(writer, session, object("sessionId", "hermes-session-1"));
            JsonNode prompt = read(reader); requests.add(prompt);
            update(writer, "agent_message_chunk", object("content", object("type", "text", "text", "Report")));
            update(writer, "agent_thought_chunk", object("content", object("type", "text", "text", "Thinking")));
            update(writer, "tool_call", object("toolCallId", "tool-1", "name", "queryData"));
            update(writer, "tool_call_update", object("toolCallId", "tool-1", "name", "queryData",
                    "status", "completed", "rawOutput", "ok"));
            update(writer, "usage_update", object("usage", object("inputTokens", 10, "outputTokens", 3)));
            ObjectNode permission = mapper.createObjectNode();
            permission.put("jsonrpc", "2.0"); permission.put("id", 91); permission.put("method", "session/request_permission");
            ObjectNode permissionParams = permission.putObject("params");
            permissionParams.put("sessionId", "hermes-session-1");
            permissionParams.putArray("options")
                    .add(object("optionId", "allow", "kind", "allow_once"))
                    .add(object("optionId", "deny", "kind", "reject_once"));
            write(writer, permission);
            JsonNode permissionResponse = read(reader); requests.add(permissionResponse);
            respond(writer, prompt, object("stopReason", "end_turn",
                    "usage", object("inputTokens", 10, "outputTokens", 3)));
            fake.awaitDestroy();
        });
        List<AgentRuntimeEvent> events = new ArrayList<>();

        ProviderExecutionRequest executionRequest = request();
        executionRequest.setApprovalHandler(approval -> {
            assertEquals("91", approval.getProviderRequestId());
            assertEquals("allow", approval.getAllowOptionId());
            assertEquals("deny", approval.getRejectOptionId());
            ProviderApprovalDecision decision = new ProviderApprovalDecision();
            decision.setApproved(true);
            decision.setSelectedOptionId(approval.getAllowOptionId());
            return decision;
        });
        ProviderExecutionResult result = adapter(process).execute(executionRequest, events::add, ignored -> { });

        assertEquals("hermes-session-1", result.getSessionId());
        assertEquals("Report", result.getFinalResponse());
        assertEquals(10, ((Number) result.getUsage().get("inputTokens")).intValue());
        assertEquals(List.of(AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                        AgentRuntimeEventTypeEnum.MESSAGE_DELTA, AgentRuntimeEventTypeEnum.REASONING_DELTA,
                        AgentRuntimeEventTypeEnum.TOOL_CALL, AgentRuntimeEventTypeEnum.TOOL_RESULT,
                        AgentRuntimeEventTypeEnum.USAGE, AgentRuntimeEventTypeEnum.APPROVAL_REQUIRED),
                events.stream().map(AgentRuntimeEvent::getType).toList());
        JsonNode mcp = requests.get(1).path("params").path("mcpServers").get(0);
        assertEquals("http", mcp.path("type").asText());
        assertEquals("Bearer task-secret", mcp.path("headers").get(0).path("value").asText());
        assertEquals("allow", requests.get(3).path("result").path("outcome").path("optionId").asText());
        assertEquals(List.of("/fake/hermes", "acp", "--profile", "tasker"), process.command.get());
        assertFalse(process.environment.get().containsKey("HERMES_YOLO_MODE"));
        assertFalse(process.environment.get().containsKey("HERMES_ACCEPT_HOOKS"));
    }

    @Test
    void resumesSessionAndCancellationUsesAcpCancelThenTerminatesProcess() throws Exception {
        CountDownLatch prompting = new CountDownLatch(1);
        AtomicReference<JsonNode> cancel = new AtomicReference<>();
        FakeHermesProcess process = new FakeHermesProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); respond(writer, initialize, mapper.createObjectNode());
            JsonNode resume = read(reader);
            assertEquals("session/resume", resume.path("method").asText());
            respond(writer, resume, object("sessionId", "session-existing"));
            read(reader); prompting.countDown();
            cancel.set(read(reader));
            fake.awaitDestroy();
        });
        HermesAcpAdapter adapter = adapter(process);
        ProviderExecutionRequest request = request();
        request.setResumeSessionId("session-existing");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread execution = new Thread(() -> {
            try {
                adapter.execute(request, ignored -> { }, new ProviderLifecycleSink() {
                    @Override public void started(String runtimeExecutionId) { }
                    @Override public void turnStarted(String runtimeExecutionId, String turnId) { }
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        execution.start();
        assertTrue(prompting.await(5, TimeUnit.SECONDS));

        adapter.cancel("run-1");
        execution.join(5_000L);

        assertFalse(execution.isAlive());
        assertEquals("session/cancel", cancel.get().path("method").asText());
        assertEquals(ProviderFailureKind.CANCELLED,
                ((ProviderExecutionException) failure.get()).getFailureKind());
        assertFalse(process.isAlive());
    }

    @Test
    void rejectsAutomaticApprovalArguments() {
        ProviderExecutionRequest request = request();
        request.getRuntimeProfile().setCustomArguments(List.of("--accept-hooks"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(new FakeHermesProcess((reader, writer, process) -> process.awaitDestroy()))
                        .execute(request, ignored -> { }, ignored -> { }));
    }

    @Test
    void classifiesPromptTimeout() {
        FakeHermesProcess process = new FakeHermesProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); respond(writer, initialize, mapper.createObjectNode());
            JsonNode session = read(reader); respond(writer, session, object("sessionId", "session-timeout"));
            read(reader);
            fake.awaitDestroy();
        });
        ProviderExecutionRequest request = request();
        request.getRuntimeProfile().setTimeoutSeconds(0);

        ProviderExecutionException failure = assertThrows(ProviderExecutionException.class,
                () -> adapter(process).execute(request, ignored -> { }, ignored -> { }));

        assertEquals(ProviderFailureKind.INACTIVITY_TIMEOUT, failure.getFailureKind());
        assertFalse(process.isAlive());
    }

    @Test
    void classifiesUnexpectedProcessExit() {
        FakeHermesProcess process = new FakeHermesProcess((reader, writer, fake) -> {
            JsonNode initialize = read(reader); respond(writer, initialize, mapper.createObjectNode());
            JsonNode session = read(reader); respond(writer, session, object("sessionId", "session-exit"));
            read(reader);
            fake.exit(17);
        });

        ProviderExecutionException failure = assertThrows(ProviderExecutionException.class,
                () -> adapter(process).execute(request(), ignored -> { }, ignored -> { }));

        assertEquals(ProviderFailureKind.PROCESS_EXIT, failure.getFailureKind());
    }

    private HermesAcpAdapter adapter(FakeHermesProcess process) {
        return new HermesAcpAdapter(mapper, (command, workingDirectory, environment) -> {
            process.command.set(List.copyOf(command));
            process.environment.set(Map.copyOf(environment));
            return process;
        }, Clock.systemUTC());
    }

    private ProviderExecutionRequest request() {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setProvider(AgentRuntimeProviderEnum.HERMES);
        profile.setExecutable("/fake/hermes");
        profile.setModel("nous:example/model");
        profile.setTimeoutSeconds(30);
        profile.setApprovalBridgeEnabled(true);
        profile.setCustomArguments(List.of("--profile", "tasker"));
        AgentDefinition agent = new AgentDefinition(); agent.setName("Analysis Agent");
        AgentTask task = new AgentTask(); task.setTitle("Analyze channels");
        AgentRuntimeStartRequest start = new AgentRuntimeStartRequest();
        start.setAgent(agent); start.setTask(task); start.setAssembledContext("Immutable Context");
        ProviderExecutionRequest request = new ProviderExecutionRequest();
        request.setRunId("run-1"); request.setLeaseAttempt(1); request.setRuntimeProfile(profile);
        request.setStartRequest(start); request.setWorkingDirectory(workspace);
        request.setEnvironment(Map.of("PATH", "/safe/bin", "CHAT2DB_AGENT_TASK_TOKEN", "task-secret",
                "HERMES_YOLO_MODE", "1", "HERMES_ACCEPT_HOOKS", "1"));
        request.setApprovalHandler(approval -> {
            ProviderApprovalDecision decision = new ProviderApprovalDecision();
            decision.setApproved(false);
            decision.setSelectedOptionId(approval.getRejectOptionId());
            return decision;
        });
        ProviderMcpEndpoint endpoint = new ProviderMcpEndpoint();
        endpoint.setName("chat2db_task_tools");
        endpoint.setUrl(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"));
        endpoint.setBearerTokenEnvironmentVariable("CHAT2DB_AGENT_TASK_TOKEN");
        request.setMcpEndpoints(List.of(endpoint));
        return request;
    }

    private JsonNode read(BufferedReader reader) throws IOException { return mapper.readTree(reader.readLine()); }

    private void respond(BufferedWriter writer, JsonNode request, JsonNode result) throws IOException {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0"); response.set("id", request.path("id")); response.set("result", result);
        write(writer, response);
    }

    private void update(BufferedWriter writer, String type, ObjectNode body) throws IOException {
        body.put("sessionUpdate", type);
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0"); notification.put("method", "session/update");
        notification.set("params", object("sessionId", "hermes-session-1", "update", body));
        write(writer, notification);
    }

    private void write(BufferedWriter writer, JsonNode message) throws IOException {
        writer.write(mapper.writeValueAsString(message)); writer.newLine(); writer.flush();
    }

    private ObjectNode object(Object... values) {
        ObjectNode node = mapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            String name = String.valueOf(values[index]); Object value = values[index + 1];
            if (value instanceof JsonNode json) node.set(name, json);
            else if (value instanceof String string) node.put(name, string);
            else if (value instanceof Integer number) node.put(name, number);
            else throw new IllegalArgumentException("unsupported test JSON value");
        }
        return node;
    }

    @FunctionalInterface
    private interface ServerScript {
        void run(BufferedReader reader, BufferedWriter writer, FakeHermesProcess process) throws Exception;
    }

    private static final class FakeHermesProcess implements ManagedProviderProcess {
        private final PipedInputStream adapterStdout = new PipedInputStream();
        private final PipedOutputStream serverStdout;
        private final PipedInputStream serverStdin = new PipedInputStream();
        private final PipedOutputStream adapterStdin;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger exitCode = new AtomicInteger();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<List<String>> command = new AtomicReference<>();
        private final AtomicReference<Map<String, String>> environment = new AtomicReference<>();

        private FakeHermesProcess(ServerScript script) {
            try {
                serverStdout = new PipedOutputStream(adapterStdout);
                adapterStdin = new PipedOutputStream(serverStdin);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            Thread server = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverStdin, StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(serverStdout, StandardCharsets.UTF_8))) {
                    script.run(reader, writer, this);
                } catch (Exception exception) {
                    if (alive.get()) exit(99);
                }
            }, "fake-hermes-acp");
            server.setDaemon(true); server.start();
        }

        private void awaitDestroy() throws InterruptedException { exited.await(10, TimeUnit.SECONDS); }
        private void exit(int code) {
            if (alive.compareAndSet(true, false)) {
                exitCode.set(code); try { serverStdout.close(); } catch (IOException ignored) { } exited.countDown();
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
