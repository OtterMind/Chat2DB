package ai.chat2db.community.runtime.cli;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.provider.ProviderEventSink;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderProcessLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Claude Code's non-interactive stream-json protocol. */
public final class ClaudeCodeAdapter extends JsonLineCliAdapter {

    private static final Set<String> BLOCKED_ARGUMENTS = Set.of(
            "-p", "--print", "--output-format", "--input-format", "--permission-mode",
            "--mcp-config", "--strict-mcp-config", "--resume", "--model", "--effort");

    public ClaudeCodeAdapter() {
        super();
    }

    ClaudeCodeAdapter(ObjectMapper mapper, ProviderProcessLauncher launcher) {
        super(mapper, launcher);
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.CLAUDE_CODE;
    }

    @Override
    protected PreparedInvocation prepare(ProviderExecutionRequest request) {
        List<String> command = new ArrayList<>(List.of(
                request.getRuntimeProfile().getExecutable(), "-p",
                "--output-format", "stream-json", "--input-format", "stream-json", "--verbose",
                "--permission-mode", "bypassPermissions", "--disallowedTools", "AskUserQuestion"));
        addOption(command, "--model", request.getRuntimeProfile().getModel());
        addOption(command, "--effort", request.getRuntimeProfile().getThinkingMode());
        addOption(command, "--resume", request.getResumeSessionId());
        Path mcpConfig = writeMcpConfig(request);
        if (mcpConfig != null) {
            command.addAll(List.of("--mcp-config", mcpConfig.toString(), "--strict-mcp-config"));
        }
        command.addAll(customArguments(request, BLOCKED_ARGUMENTS));

        ObjectNode input = mapper.createObjectNode();
        input.put("type", "user");
        ObjectNode message = input.putObject("message");
        message.put("role", "user");
        message.putArray("content").addObject().put("type", "text")
                .put("text", promptBuilder.build(request.getStartRequest()));
        byte[] bytes;
        try {
            bytes = (mapper.writeValueAsString(input) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to encode Claude Code input", exception);
        }
        return new PreparedInvocation(WindowsCliInvocation.claude(command), mutableEnvironment(request), bytes, true, null,
                mcpConfig == null ? null : () -> deleteQuietly(mcpConfig));
    }

    @Override
    protected void handleEvent(JsonNode event, ParseState state, ProviderEventSink sink, OutputStream stdin) {
        String type = event.path("type").asText();
        if (StringUtils.isNotBlank(event.path("session_id").asText())) {
            state.sessionId = event.path("session_id").asText();
        }
        switch (type) {
            case "system" -> {
                ObjectNode payload = mapper.createObjectNode();
                payload.put("sessionId", state.sessionId);
                emit(sink, AgentRuntimeEventTypeEnum.SESSION_UPDATED,
                        "Claude Code session updated", payload);
            }
            case "assistant" -> handleAssistant(event.path("message"), state, sink);
            case "user" -> handleToolResults(event.path("message"), sink);
            case "result" -> {
                state.terminal = true;
                String result = event.path("result").asText("");
                if (!result.isEmpty()) {
                    state.finalResponse.setLength(0);
                    state.finalResponse.append(result);
                }
                if (event.path("is_error").asBoolean(false)) {
                    state.failure = StringUtils.defaultIfBlank(result, "Claude Code returned an error result");
                }
                if (!event.path("usage").isMissingNode()) {
                    state.usage.putAll(mapper.convertValue(event.path("usage"),
                            new TypeReference<Map<String, Object>>() { }));
                    emit(sink, AgentRuntimeEventTypeEnum.USAGE, "Claude Code usage updated", event.path("usage"));
                }
            }
            case "control_request" -> approveControlRequest(event, stdin);
            default -> {
            }
        }
    }

    private void handleAssistant(JsonNode message, ParseState state, ProviderEventSink sink) {
        if (!message.path("usage").isMissingNode()) {
            state.usage.putAll(mapper.convertValue(message.path("usage"),
                    new TypeReference<Map<String, Object>>() { }));
        }
        for (JsonNode block : message.path("content")) {
            switch (block.path("type").asText()) {
                case "text" -> {
                    String text = block.path("text").asText("");
                    state.finalResponse.append(text);
                    emit(sink, AgentRuntimeEventTypeEnum.MESSAGE_DELTA, text, block);
                }
                case "thinking" -> emit(sink, AgentRuntimeEventTypeEnum.REASONING_DELTA,
                        block.path("thinking").asText(block.path("text").asText("")), block);
                case "tool_use" -> emit(sink, AgentRuntimeEventTypeEnum.TOOL_CALL,
                        block.path("name").asText("tool"), block);
                default -> {
                }
            }
        }
    }

    private void handleToolResults(JsonNode message, ProviderEventSink sink) {
        for (JsonNode block : message.path("content")) {
            if ("tool_result".equals(block.path("type").asText())) {
                emit(sink, AgentRuntimeEventTypeEnum.TOOL_RESULT,
                        block.path("content").isTextual() ? block.path("content").asText() : block.toString(), block);
            }
        }
    }

    private void approveControlRequest(JsonNode event, OutputStream stdin) {
        ObjectNode response = mapper.createObjectNode();
        response.put("type", "control_response");
        ObjectNode body = response.putObject("response");
        body.put("subtype", "success");
        body.put("request_id", event.path("request_id").asText());
        ObjectNode decision = body.putObject("response");
        decision.put("behavior", "allow");
        JsonNode input = event.path("request").path("input");
        decision.set("updatedInput", input.isObject() ? input : mapper.createObjectNode());
        writeJsonLine(stdin, response);
    }

    private Path writeMcpConfig(ProviderExecutionRequest request) {
        if (request.getMcpEndpoints().isEmpty()) {
            return null;
        }
        ObjectNode root = mapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        request.getMcpEndpoints().forEach(endpoint -> {
            ObjectNode server = servers.putObject(endpoint.getName());
            server.put("type", "http");
            server.put("url", endpoint.getUrl().toString());
            server.putObject("headers").put("Authorization", "Bearer ${" + TASK_TOKEN_ENV + "}");
        });
        Path path = request.getWorkingDirectory().resolve(".chat2db-claude-mcp.json");
        try {
            mapper.writeValue(path.toFile(), root);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create Claude Code MCP configuration", exception);
        }
    }

    private void addOption(List<String> command, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            command.add(name);
            command.add(value.trim());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    @Override
    protected String displayName() {
        return "Claude Code";
    }
}
