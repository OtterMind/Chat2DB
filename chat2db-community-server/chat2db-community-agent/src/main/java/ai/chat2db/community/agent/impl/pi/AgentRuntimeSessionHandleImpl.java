package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.agent.converter.pi.PiEventConverter;
import ai.chat2db.community.agent.exception.pi.PiRpcException;
import ai.chat2db.community.agent.pi.IPiModelConfiguration;
import ai.chat2db.community.agent.pi.IPiRpcTransport;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeEventSink;
import ai.chat2db.community.tools.agent.runtime.IAgentRuntimeSessionHandle;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.enums.agent.AgentRuntimeHealth;
import ai.chat2db.community.tools.model.agent.runtime.AgentModelAccess;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeSnapshot;
import ai.chat2db.community.tools.util.AgentTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class AgentRuntimeSessionHandleImpl implements IAgentRuntimeSessionHandle {

    private final String sessionId;
    private final AgentRuntimeSessionRef session;
    private final PiProcessHandle process;
    private final IPiRpcTransport rpc;
    private final PiEventConverter eventConverter;
    private final IAgentRuntimeEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final Runnable closeHook;
    private final IPiModelConfiguration modelConfiguration;
    private String modelConfigurationError;
    private AgentRuntimeHealth health = AgentRuntimeHealth.READY;
    private String activeRunId;
    private String activeExternalRunId;
    private boolean cancelling;
    private JsonNode lastAssistantMessage;

    public AgentRuntimeSessionHandleImpl(
            String sessionId,
            AgentRuntimeSessionRef session,
            PiProcessHandle process,
            IPiRpcTransport rpc,
            PiEventConverter eventConverter,
            IAgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper,
            Runnable closeHook,
            IPiModelConfiguration modelConfiguration) {
        this.sessionId = sessionId;
        this.session = session;
        this.process = process;
        this.rpc = rpc;
        this.eventConverter = eventConverter;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
        this.closeHook = closeHook;
        this.modelConfiguration = modelConfiguration;
        rpc.termination().whenComplete((ignored, error) -> runtimeTerminated(error));
    }

    @Override
    public AgentRuntimeSessionRef session() {
        return session;
    }

    @Override
    public synchronized CompletionStage<AgentRuntimeRunRef> startRun(AgentRuntimeRunRequest request) {
        if (!sessionId.equals(request.sessionId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Run belongs to another session"));
        }
        if (health != AgentRuntimeHealth.READY) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pi runtime session is not ready"));
        }
        AgentModelAccess modelAccess = modelConfiguration.prepare(request.model());
        modelConfigurationError = null;
        activeRunId = request.runId();
        lastAssistantMessage = null;
        activeExternalRunId = request.runId();
        health = AgentRuntimeHealth.BUSY;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", request.input().text());
        AgentTrace.record("pi.prompt.sending", sessionId, request.runId(),
                Map.of("inputCharacters", request.input().text().length()));
        CompletableFuture<JsonNode> response = rpc.request("prompt", objectMapper.createObjectNode()
                        .put("message", "/chat2db-refresh-model"))
                .thenCompose(ignored -> selectModel(request.runId(), modelAccess))
                .thenCompose(ignored -> sendPrompt(request.runId(), payload));
        response.whenComplete((ignored, error) -> {
            if (error != null) {
                failActiveRun(request.runId());
            }
        });
        return response.thenApply(result -> acknowledgeRun(request.runId(), result));
    }

    @Override
    public synchronized CompletionStage<Void> cancel(AgentRuntimeCancelRequest request) {
        if (!sessionId.equals(request.sessionId())
                || !request.runId().equals(activeRunId)
                || !request.externalRunId().equals(activeExternalRunId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown active Pi run"));
        }
        cancelling = true;
        ObjectNode payload = objectMapper.createObjectNode();
        CompletableFuture<JsonNode> response = rpc.request("abort", payload);
        response.whenComplete((ignored, error) -> {
            if (error != null) {
                resetCancellation();
            }
        });
        return response.thenAccept(ignored -> completeCancellation(request.runId()));
    }

    @Override
    public synchronized CompletionStage<AgentRuntimeSnapshot> snapshot() {
        return CompletableFuture.completedFuture(new AgentRuntimeSnapshot(session, health, activeExternalRunId));
    }

    public synchronized void accept(JsonNode rawEvent) {
        if (activeRunId == null) {
            AgentTrace.record("pi.event.ignored", sessionId, null,
                    Map.of("type", rawEvent.path("type").asText()));
            return;
        }
        if ("extension_error".equals(rawEvent.path("type").asText())
                && "command:chat2db-refresh-model".equals(rawEvent.path("extensionPath").asText())) {
            modelConfigurationError = rawEvent.path("error").asText("Pi model configuration refresh failed");
            return;
        }
        if ("message_end".equals(rawEvent.path("type").asText())
                && "assistant".equals(rawEvent.path("message").path("role").asText())) {
            lastAssistantMessage = rawEvent.get("message");
        }
        if ("agent_settled".equals(rawEvent.path("type").asText()) && lastAssistantMessage != null) {
            ObjectNode settled = rawEvent.deepCopy();
            String stopReason = lastAssistantMessage.path("stopReason").asText();
            if ("error".equals(stopReason)) {
                settled.put("error", lastAssistantMessage.path("errorMessage").asText("Pi model request failed"));
            } else if ("aborted".equals(stopReason)) {
                settled.put("cancelled", true);
            }
            rawEvent = settled;
        }
        AgentRuntimeEvent event = eventConverter.toRuntimeEvent(sessionId, activeRunId, rawEvent);
        if (event == null) {
            return;
        }
        if (cancelling && isTerminal(event.type())) {
            return;
        }
        eventSink.emit(event);
        if (isTerminal(event.type())) {
            finish(AgentRuntimeHealth.READY);
        }
    }

    @Override
    public synchronized void close() {
        health = AgentRuntimeHealth.STOPPED;
        activeRunId = null;
        activeExternalRunId = null;
        rpc.close();
        process.close();
        modelConfiguration.close();
        closeHook.run();
    }

    private synchronized CompletableFuture<JsonNode> selectModel(String runId, AgentModelAccess access) {
        requireActive(runId);
        if (modelConfigurationError != null) {
            throw new PiRpcException("Cannot refresh Pi model configuration: " + modelConfigurationError);
        }
        return rpc.request("set_model", objectMapper.createObjectNode()
                .put("provider", access.provider()).put("modelId", access.modelId()));
    }

    private void requireActive(String runId) {
        if (!runId.equals(activeRunId) || cancelling) {
            throw new CancellationException("Pi run was cancelled before its prompt was sent");
        }
    }

    private synchronized CompletableFuture<JsonNode> sendPrompt(String runId, ObjectNode payload) {
        requireActive(runId);
        return rpc.request("prompt", payload);
    }

    private synchronized AgentRuntimeRunRef acknowledgeRun(String runId, JsonNode result) {
        AgentTrace.record("pi.prompt.acknowledged", sessionId, runId, Map.of());
        String externalRunId = result.hasNonNull("externalRunId")
                ? result.get("externalRunId").asText() : runId;
        if (externalRunId.isBlank()) {
            throw new PiRpcException("Pi prompt response has a blank externalRunId");
        }
        if (runId.equals(activeRunId)) {
            activeExternalRunId = externalRunId;
        }
        return new AgentRuntimeRunRef(runId, externalRunId);
    }

    private synchronized void completeCancellation(String runId) {
        if (activeRunId == null) {
            return;
        }
        eventSink.emit(new AgentRuntimeEvent(
                "cancelled-" + runId, sessionId, runId, AgentEventType.RUN_CANCELLED,
                Map.of(), LocalDateTime.now()));
        finish(AgentRuntimeHealth.READY);
    }

    private synchronized void runtimeTerminated(Throwable error) {
        if (health == AgentRuntimeHealth.STOPPED) {
            return;
        }
        health = error == null ? AgentRuntimeHealth.STOPPED : AgentRuntimeHealth.FAILED;
        if (activeRunId != null) {
            eventSink.emit(new AgentRuntimeEvent(
                    "runtime-stopped-" + activeRunId, sessionId, activeRunId,
                    AgentEventType.RUN_OUTCOME_UNKNOWN,
                    Map.of("reason", error == null
                            ? "runtime stopped"
                            : Objects.toString(error.getMessage(), error.getClass().getSimpleName())),
                    LocalDateTime.now()));
            activeRunId = null;
            activeExternalRunId = null;
        }
    }

    private void finish(AgentRuntimeHealth targetHealth) {
        health = targetHealth;
        activeRunId = null;
        activeExternalRunId = null;
        cancelling = false;
    }

    private synchronized void failActiveRun(String runId) {
        if (runId.equals(activeRunId)) {
            finish(AgentRuntimeHealth.READY);
        }
    }

    private synchronized void resetCancellation() {
        cancelling = false;
    }

    private boolean isTerminal(AgentEventType type) {
        return type == AgentEventType.RUN_COMPLETED
                || type == AgentEventType.RUN_FAILED
                || type == AgentEventType.RUN_CANCELLED
                || type == AgentEventType.RUN_OUTCOME_UNKNOWN;
    }
}
