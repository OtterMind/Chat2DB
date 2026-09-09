package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.model.agent.AgentEventType;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeCancelRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeEvent;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeHealth;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeRunRequest;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSessionRef;
import ai.chat2db.community.domain.api.model.agent.runtime.AgentRuntimeSnapshot;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeEventSink;
import ai.chat2db.community.domain.api.service.agent.AgentRuntimeSessionHandle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class PiAgentRuntimeSessionHandle implements AgentRuntimeSessionHandle {

    private final String sessionId;
    private final AgentRuntimeSessionRef session;
    private final PiProcessHandle process;
    private final PiRpcTransport rpc;
    private final PiEventMapper eventMapper;
    private final AgentRuntimeEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final Runnable closeHook;
    private final String runtimeProvider;
    private final String runtimeModelId;
    private AgentRuntimeHealth health = AgentRuntimeHealth.READY;
    private String activeRunId;
    private String activeExternalRunId;
    private boolean cancelling;
    private JsonNode lastAssistantMessage;

    public PiAgentRuntimeSessionHandle(
            String sessionId,
            AgentRuntimeSessionRef session,
            PiProcessHandle process,
            PiRpcTransport rpc,
            PiEventMapper eventMapper,
            AgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper,
            Runnable closeHook,
            String runtimeProvider,
            String runtimeModelId) {
        this.sessionId = sessionId;
        this.session = session;
        this.process = process;
        this.rpc = rpc;
        this.eventMapper = eventMapper;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
        this.closeHook = closeHook;
        this.runtimeProvider = runtimeProvider;
        this.runtimeModelId = runtimeModelId;
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
        activeRunId = request.runId();
        lastAssistantMessage = null;
        activeExternalRunId = request.runId();
        health = AgentRuntimeHealth.BUSY;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", request.input().text());
        ai.chat2db.community.tools.util.AgentTrace.record("pi.prompt.sending", sessionId, request.runId(),
                java.util.Map.of("inputCharacters", request.input().text().length()));
        CompletableFuture<JsonNode> response = rpc.request("set_model", objectMapper.createObjectNode()
                        .put("provider", runtimeProvider)
                        .put("modelId", runtimeModelId))
                .thenCompose(ignored -> rpc.request("prompt", payload));
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
            ai.chat2db.community.tools.util.AgentTrace.record("pi.event.ignored", sessionId, null,
                    java.util.Map.of("type", rawEvent.path("type").asText()));
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
        AgentRuntimeEvent event = eventMapper.map(sessionId, activeRunId, rawEvent);
        if (event == null) {
            return;
        }
        if (cancelling && isTerminal(event.type())) {
            return;
        }
        eventSink.emit(event);
        if (isTerminal(event.type())) {
            finish(event.type() == AgentEventType.RUN_FAILED ? AgentRuntimeHealth.FAILED : AgentRuntimeHealth.READY);
        }
    }

    @Override
    public synchronized void close() {
        health = AgentRuntimeHealth.STOPPED;
        activeRunId = null;
        activeExternalRunId = null;
        rpc.close();
        process.close();
        closeHook.run();
    }

    private synchronized AgentRuntimeRunRef acknowledgeRun(String runId, JsonNode result) {
        ai.chat2db.community.tools.util.AgentTrace.record("pi.prompt.acknowledged", sessionId, runId, java.util.Map.of());
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
                java.util.Map.of(), java.time.LocalDateTime.now()));
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
                    java.util.Map.of("reason", error == null
                            ? "runtime stopped"
                            : java.util.Objects.toString(error.getMessage(), error.getClass().getSimpleName())),
                    java.time.LocalDateTime.now()));
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
            finish(AgentRuntimeHealth.FAILED);
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
