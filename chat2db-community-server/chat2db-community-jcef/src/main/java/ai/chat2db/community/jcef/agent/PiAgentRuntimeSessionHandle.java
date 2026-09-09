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
    private AgentRuntimeHealth health = AgentRuntimeHealth.READY;
    private String activeRunId;
    private String activeExternalRunId;
    private boolean cancelling;

    public PiAgentRuntimeSessionHandle(
            String sessionId,
            AgentRuntimeSessionRef session,
            PiProcessHandle process,
            PiRpcTransport rpc,
            PiEventMapper eventMapper,
            AgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper) {
        this.sessionId = sessionId;
        this.session = session;
        this.process = process;
        this.rpc = rpc;
        this.eventMapper = eventMapper;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
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
        activeExternalRunId = request.runId();
        health = AgentRuntimeHealth.BUSY;
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sessionId", request.sessionId());
        payload.put("runId", request.runId());
        payload.put("idempotencyKey", request.idempotencyKey());
        payload.set("model", objectMapper.valueToTree(request.model()));
        payload.set("input", objectMapper.valueToTree(request.input()));
        CompletableFuture<JsonNode> response = rpc.request("prompt", payload);
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
        payload.put("runId", request.runId());
        payload.put("externalRunId", request.externalRunId());
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
            throw new PiRpcException("Pi emitted a run event without an active run");
        }
        AgentRuntimeEvent event = eventMapper.map(sessionId, activeRunId, rawEvent);
        if (event == null) {
            return;
        }
        if (cancelling && event.type() == AgentEventType.RUN_COMPLETED) {
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
    }

    private synchronized AgentRuntimeRunRef acknowledgeRun(String runId, JsonNode result) {
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
