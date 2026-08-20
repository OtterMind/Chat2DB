package ai.chat2db.community.runtime.cli;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.provider.ProviderEventSink;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderProcessLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OpenCode's one-shot {@code run --format json} protocol. */
public final class OpenCodeAdapter extends JsonLineCliAdapter {

    static final String CONFIG_ENV = "OPENCODE_CONFIG_CONTENT";
    private static final Set<String> BLOCKED_ARGUMENTS = Set.of(
            "run", "--format", "--dir", "--session", "--model", "--variant",
            "--dangerously-skip-permissions");

    public OpenCodeAdapter() {
        super();
    }

    OpenCodeAdapter(ObjectMapper mapper, ProviderProcessLauncher launcher) {
        super(mapper, launcher);
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.OPENCODE;
    }

    @Override
    protected PreparedInvocation prepare(ProviderExecutionRequest request) {
        List<String> command = new ArrayList<>(List.of(
                request.getRuntimeProfile().getExecutable(), "run", "--format", "json",
                "--dangerously-skip-permissions", "--dir", request.getWorkingDirectory().toString()));
        addOption(command, "--model", request.getRuntimeProfile().getModel());
        addOption(command, "--variant", request.getRuntimeProfile().getThinkingMode());
        addOption(command, "--session", request.getResumeSessionId());
        command.addAll(customArguments(request, BLOCKED_ARGUMENTS));
        command.add(promptBuilder.build(request.getStartRequest()));

        Map<String, String> environment = mutableEnvironment(request);
        if (!request.getMcpEndpoints().isEmpty()) {
            ObjectNode config = mapper.createObjectNode();
            ObjectNode mcp = config.putObject("mcp");
            request.getMcpEndpoints().forEach(endpoint -> {
                ObjectNode server = mcp.putObject(endpoint.getName());
                server.put("type", "remote");
                server.put("url", endpoint.getUrl().toString());
                server.put("enabled", true);
                server.put("oauth", false);
                server.putObject("headers").put("Authorization", "Bearer {env:" + TASK_TOKEN_ENV + "}");
            });
            environment.put(CONFIG_ENV, config.toString());
        }
        environment.put("PWD", request.getWorkingDirectory().toString());
        return new PreparedInvocation(WindowsCliInvocation.openCode(command), environment, null, false, null, null);
    }

    @Override
    protected void handleEvent(JsonNode event, ParseState state, ProviderEventSink sink, OutputStream stdin) {
        String session = event.path("sessionID").asText();
        boolean sessionChanged = StringUtils.isNotBlank(session) && !session.equals(state.sessionId);
        if (StringUtils.isNotBlank(session)) {
            state.sessionId = session;
        }
        if (sessionChanged) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("sessionId", session);
            emit(sink, AgentRuntimeEventTypeEnum.SESSION_UPDATED, "OpenCode session updated", payload);
        }
        JsonNode part = event.path("part");
        switch (event.path("type").asText()) {
            case "step_start" -> {
                state.stepOpen = true;
                state.continuationExpected = false;
                state.turnId = part.path("id").asText(state.turnId);
                emit(sink, AgentRuntimeEventTypeEnum.STATUS, "OpenCode step started", part);
            }
            case "text" -> {
                String text = part.path("text").asText("");
                state.finalResponse.append(text);
                emit(sink, AgentRuntimeEventTypeEnum.MESSAGE_DELTA, text, part);
            }
            case "tool_use" -> {
                emit(sink, AgentRuntimeEventTypeEnum.TOOL_CALL,
                        part.path("tool").asText("tool"), part);
                String status = part.path("state").path("status").asText();
                if ("completed".equals(status) || "error".equals(status)) {
                    emit(sink, AgentRuntimeEventTypeEnum.TOOL_RESULT,
                            stringify(part.path("state").path("output")), part);
                }
                if (!part.path("metadata").path("providerExecuted").asBoolean(false)) {
                    state.continuationExpected = true;
                }
            }
            case "step_finish" -> {
                state.stepOpen = false;
                String reason = part.path("reason").asText();
                boolean needsContinuation = "tool-calls".equals(reason)
                        || (!reason.isBlank() && state.continuationExpected);
                state.continuationExpected = needsContinuation;
                if (!needsContinuation) {
                    state.terminal = true;
                }
                JsonNode tokens = part.path("tokens");
                if (tokens.isObject()) {
                    accumulateUsage(state, tokens);
                    emit(sink, AgentRuntimeEventTypeEnum.USAGE, "OpenCode usage updated", tokens);
                }
            }
            case "error" -> {
                state.terminal = true;
                state.failure = StringUtils.defaultIfBlank(
                        event.path("error").path("data").path("message").asText(),
                        event.path("error").path("name").asText("OpenCode returned an error"));
                emit(sink, AgentRuntimeEventTypeEnum.ERROR, state.failure, event.path("error"));
            }
            default -> {
            }
        }
    }

    private void accumulateUsage(ParseState state, JsonNode tokens) {
        add(state.usage, "inputTokens", tokens.path("input").asLong());
        add(state.usage, "outputTokens", tokens.path("output").asLong());
        add(state.usage, "cacheReadTokens", tokens.path("cache").path("read").asLong());
        add(state.usage, "cacheWriteTokens", tokens.path("cache").path("write").asLong());
    }

    private void add(Map<String, Object> usage, String key, long value) {
        if (value > 0) {
            usage.put(key, ((Number) usage.getOrDefault(key, 0L)).longValue() + value);
        }
    }

    private String stringify(JsonNode node) {
        return node.isTextual() ? node.asText() : node.isMissingNode() ? "" : node.toString();
    }

    private void addOption(List<String> command, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            command.add(name);
            command.add(value.trim());
        }
    }

    @Override
    protected String displayName() {
        return "OpenCode";
    }
}
