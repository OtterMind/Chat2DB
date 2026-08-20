package ai.chat2db.community.runtime.cli;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.domain.api.model.agent.AgentDefinition;
import ai.chat2db.community.domain.api.model.agent.AgentRuntimeProfile;
import ai.chat2db.community.domain.api.model.agent.AgentTask;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeStartRequest;
import ai.chat2db.community.runtime.provider.ManagedProviderProcess;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderExecutionResult;
import ai.chat2db.community.runtime.provider.ProviderMcpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliAdaptersTest {

    @TempDir
    Path workspace;

    @Test
    void claudeCodeUsesStreamJsonAndManagedMcpWhileReturningItsSession() {
        String stdout = """
                {"type":"system","session_id":"claude-session"}
                {"type":"assistant","message":{"model":"claude-sonnet","content":[{"type":"text","text":"Claude report"},{"type":"tool_use","id":"tool-1","name":"queryData","input":{"sql":"select 1"}}],"usage":{"input_tokens":5,"output_tokens":2}}}
                {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"ok"}]}}
                {"type":"result","session_id":"claude-session","result":"Claude report","is_error":false,"usage":{"input_tokens":5,"output_tokens":2}}
                """;
        CapturedProcess process = new CapturedProcess(stdout);
        AtomicReference<List<String>> command = new AtomicReference<>();
        List<AgentRuntimeEventTypeEnum> events = new ArrayList<>();
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(new ObjectMapper(),
                (argv, cwd, env) -> capture(process, command, argv, env));

        ProviderExecutionResult result = adapter.execute(request(AgentRuntimeProviderEnum.CLAUDE_CODE),
                event -> events.add(event.getType()), ignored -> { });

        assertEquals("claude-session", result.getSessionId());
        assertEquals("Claude report", result.getFinalResponse());
        assertTrue(command.get().containsAll(List.of("--output-format", "stream-json", "--mcp-config")));
        String stdin = process.stdin.toString(StandardCharsets.UTF_8);
        assertTrue(stdin.contains("Chat2DB Agent Task"));
        assertTrue(events.containsAll(List.of(AgentRuntimeEventTypeEnum.MESSAGE_DELTA,
                AgentRuntimeEventTypeEnum.TOOL_CALL, AgentRuntimeEventTypeEnum.TOOL_RESULT)));
        assertFalse(java.nio.file.Files.exists(workspace.resolve(".chat2db-claude-mcp.json")));
    }

    @Test
    void openCodeUsesJsonRunProtocolAndInjectsTaskMcpConfig() {
        String stdout = """
                {"type":"step_start","sessionID":"oc-session","part":{"id":"step-1"}}
                {"type":"text","sessionID":"oc-session","part":{"text":"OpenCode report"}}
                {"type":"step_finish","sessionID":"oc-session","part":{"reason":"stop","tokens":{"input":7,"output":3,"cache":{"read":2,"write":1}}}}
                """;
        CapturedProcess process = new CapturedProcess(stdout);
        AtomicReference<List<String>> command = new AtomicReference<>();
        AtomicReference<Map<String, String>> environment = new AtomicReference<>();
        OpenCodeAdapter adapter = new OpenCodeAdapter(new ObjectMapper(), (argv, cwd, env) -> {
            environment.set(Map.copyOf(env));
            return capture(process, command, argv, env);
        });

        ProviderExecutionResult result = adapter.execute(request(AgentRuntimeProviderEnum.OPENCODE),
                ignored -> { }, ignored -> { });

        assertEquals("oc-session", result.getSessionId());
        assertEquals("OpenCode report", result.getFinalResponse());
        assertEquals(List.of("/fake/opencode", "run", "--format", "json"), command.get().subList(0, 4));
        assertTrue(environment.get().get(OpenCodeAdapter.CONFIG_ENV).contains("{env:CHAT2DB_AGENT_TASK_TOKEN}"));
        assertEquals(7L, result.getUsage().get("inputTokens"));
    }

    @Test
    void piUsesJsonModeAndPersistsOpaqueSessionPathForResume() {
        String stdout = """
                {"type":"agent_start"}
                {"type":"turn_start"}
                {"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Pi report"}}
                {"type":"turn_end","message":{"model":"anthropic/claude","usage":{"input":4,"output":2,"cacheRead":1,"cacheWrite":0}}}
                """;
        CapturedProcess process = new CapturedProcess(stdout);
        AtomicReference<List<String>> command = new AtomicReference<>();
        Path sessions = workspace.resolve("sessions");
        ProviderExecutionRequest request = request(AgentRuntimeProviderEnum.PI);
        request.getRuntimeProfile().setModel("anthropic/claude-sonnet");
        PiAdapter adapter = new PiAdapter(new ObjectMapper(),
                (argv, cwd, env) -> capture(process, command, argv, env), sessions);

        ProviderExecutionResult result = adapter.execute(request, ignored -> { }, ignored -> { });

        assertEquals("Pi report", result.getFinalResponse());
        assertTrue(Path.of(result.getSessionId()).startsWith(sessions));
        assertTrue(java.nio.file.Files.exists(Path.of(result.getSessionId())));
        assertTrue(command.get().containsAll(List.of("-p", "--mode", "json", "--provider", "anthropic")));
    }

    private ProviderExecutionRequest request(AgentRuntimeProviderEnum provider) {
        AgentRuntimeProfile profile = new AgentRuntimeProfile();
        profile.setProvider(provider);
        profile.setExecutable("/fake/" + provider.defaultExecutable());
        profile.setTimeoutSeconds(30);
        profile.setCustomArguments(List.of());

        AgentDefinition agent = new AgentDefinition();
        agent.setName("Analyst");
        AgentTask task = new AgentTask();
        task.setTitle("Inspect data");
        AgentRuntimeStartRequest start = new AgentRuntimeStartRequest();
        start.setAgent(agent);
        start.setTask(task);
        start.setAssembledContext("Immutable context");

        ProviderMcpEndpoint endpoint = new ProviderMcpEndpoint();
        endpoint.setName("chat2db_task_tools");
        endpoint.setUrl(URI.create("http://127.0.0.1:10825/api/agent/runtime/mcp/runs/run-1"));
        endpoint.setBearerTokenEnvironmentVariable("CHAT2DB_AGENT_TASK_TOKEN");

        ProviderExecutionRequest request = new ProviderExecutionRequest();
        request.setRunId("run-1");
        request.setRuntimeProfile(profile);
        request.setStartRequest(start);
        request.setWorkingDirectory(workspace.toAbsolutePath());
        request.setEnvironment(Map.of("PATH", "/safe/bin", "CHAT2DB_AGENT_TASK_TOKEN", "secret"));
        request.setMcpEndpoints(List.of(endpoint));
        return request;
    }

    private CapturedProcess capture(CapturedProcess process, AtomicReference<List<String>> command,
                                    List<String> argv, Map<String, String> environment) {
        command.set(List.copyOf(argv));
        return process;
    }

    private static final class CapturedProcess implements ManagedProviderProcess {
        private final InputStream stdout;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();

        private CapturedProcess(String stdout) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        }

        @Override public InputStream stdout() { return stdout; }
        @Override public InputStream stderr() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream stdin() { return stdin; }
        @Override public boolean isAlive() { return false; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(Duration timeout) { return true; }
        @Override public void destroy() { }
        @Override public void destroyForcibly() { }
        @Override public long pid() { return 42L; }
    }
}
