package ai.chat2db.community.runtime.cli;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeEventTypeEnum;
import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import ai.chat2db.community.runtime.provider.ProviderEventSink;
import ai.chat2db.community.runtime.provider.ProviderExecutionRequest;
import ai.chat2db.community.runtime.provider.ProviderProcessLauncher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pi's non-interactive {@code -p --mode json} event protocol. */
public final class PiAdapter extends JsonLineCliAdapter {

    private static final Set<String> BLOCKED_ARGUMENTS = Set.of(
            "-p", "--print", "--mode", "--session", "--provider", "--model");
    private final Path sessionRoot;

    public PiAdapter() {
        this(new ObjectMapper(), new ai.chat2db.community.runtime.provider.DefaultProviderProcessLauncher(),
                Path.of(System.getProperty("java.io.tmpdir"), "chat2db-agent-runtime-pi-sessions"));
    }

    PiAdapter(ObjectMapper mapper, ProviderProcessLauncher launcher, Path sessionRoot) {
        super(mapper, launcher);
        this.sessionRoot = sessionRoot.toAbsolutePath().normalize();
    }

    @Override
    public AgentRuntimeProviderEnum provider() {
        return AgentRuntimeProviderEnum.PI;
    }

    @Override
    protected PreparedInvocation prepare(ProviderExecutionRequest request) {
        Path session = resolveSession(request.getResumeSessionId());
        List<String> command = new ArrayList<>(List.of(
                request.getRuntimeProfile().getExecutable(), "-p", "--mode", "json",
                "--session", session.toString()));
        addModel(command, request.getRuntimeProfile().getModel());
        command.addAll(customArguments(request, BLOCKED_ARGUMENTS));
        command.add(promptBuilder.build(request.getStartRequest()));
        return new PreparedInvocation(WindowsCliInvocation.pi(command), mutableEnvironment(request), null, false,
                session.toString(), null);
    }

    @Override
    protected void handleEvent(JsonNode event, ParseState state, ProviderEventSink sink, OutputStream stdin) {
        switch (event.path("type").asText()) {
            case "agent_start" -> {
                com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
                payload.put("sessionId", state.sessionId);
                emit(sink, AgentRuntimeEventTypeEnum.SESSION_UPDATED, "Pi session updated", payload);
                emit(sink, AgentRuntimeEventTypeEnum.STATUS, "Pi started", event);
            }
            case "turn_start" -> state.finalResponse.setLength(0);
            case "message_update" -> {
                JsonNode update = event.path("assistantMessageEvent");
                String delta = sanitize(update.path("delta").asText(""));
                if ("text_delta".equals(update.path("type").asText())) {
                    state.finalResponse.append(delta);
                    emit(sink, AgentRuntimeEventTypeEnum.MESSAGE_DELTA, delta, update);
                } else if ("thinking_delta".equals(update.path("type").asText())) {
                    emit(sink, AgentRuntimeEventTypeEnum.REASONING_DELTA, delta, update);
                }
            }
            case "tool_execution_start" -> emit(sink, AgentRuntimeEventTypeEnum.TOOL_CALL,
                    event.path("toolName").asText("tool"), event);
            case "tool_execution_end" -> emit(sink, AgentRuntimeEventTypeEnum.TOOL_RESULT,
                    stringify(event.path("result")), event);
            case "turn_end" -> {
                state.terminal = true;
                JsonNode message = event.path("message");
                JsonNode usage = message.path("usage");
                if (usage.isObject()) {
                    state.usage.put("inputTokens", usage.path("input").asLong());
                    state.usage.put("outputTokens", usage.path("output").asLong());
                    state.usage.put("cacheReadTokens", usage.path("cacheRead").asLong());
                    state.usage.put("cacheWriteTokens", usage.path("cacheWrite").asLong());
                    emit(sink, AgentRuntimeEventTypeEnum.USAGE, "Pi usage updated", usage);
                }
            }
            case "error" -> {
                state.terminal = true;
                state.failure = StringUtils.defaultIfBlank(stringify(event.path("message")), "Pi returned an error");
                emit(sink, AgentRuntimeEventTypeEnum.ERROR, state.failure, event);
            }
            case "auto_retry_end" -> {
                if (!event.path("success").asBoolean(true)) {
                    state.terminal = true;
                    state.failure = event.path("finalError").asText("Pi exhausted automatic retries");
                }
            }
            default -> {
            }
        }
    }

    private Path resolveSession(String resumeSessionId) {
        try {
            Files.createDirectories(sessionRoot);
            Path session;
            if (StringUtils.isBlank(resumeSessionId)) {
                session = sessionRoot.resolve(UUID.randomUUID() + ".jsonl");
            } else {
                session = Path.of(resumeSessionId).toAbsolutePath().normalize();
                if (!session.startsWith(sessionRoot)) {
                    throw new IllegalArgumentException("Pi resume session is outside the Chat2DB session directory");
                }
            }
            if (!Files.exists(session)) {
                Files.createFile(session);
            }
            return session;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to prepare Pi session", exception);
        }
    }

    private void addModel(List<String> command, String configuredModel) {
        if (StringUtils.isBlank(configuredModel)) {
            return;
        }
        String model = configuredModel.trim();
        int separator = model.indexOf('/');
        if (separator > 0 && separator < model.length() - 1) {
            command.add("--provider");
            command.add(model.substring(0, separator));
            model = model.substring(separator + 1);
        }
        command.add("--model");
        command.add(model);
    }

    private String stringify(JsonNode node) {
        return node.isTextual() ? node.asText() : node.isMissingNode() ? "" : node.toString();
    }

    private String sanitize(String text) {
        return text.replaceAll("<\\|[A-Za-z0-9_-]+>[A-Za-z0-9_-]*|<[A-Za-z0-9_-]+\\|>", "");
    }

    @Override
    protected String displayName() {
        return "Pi";
    }
}
