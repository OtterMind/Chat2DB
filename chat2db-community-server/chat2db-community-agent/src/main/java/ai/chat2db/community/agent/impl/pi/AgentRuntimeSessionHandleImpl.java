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
import java.util.HashMap;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class AgentRuntimeSessionHandleImpl implements IAgentRuntimeSessionHandle {

    private final String sessionId;
    private final AgentRuntimeSessionRef session;
    private final PiProcessHandle process;
    private final IPiRpcTransport rpc;
    private final PiEventConverter eventConverter;
    private final IAgentRuntimeEventSink eventSink;
    private final ObjectMapper objectMapper;
    private final Runnable closeHook;
    private final Runnable refreshToolAccess;
    private final IPiModelConfiguration modelConfiguration;
    private final PiSkillConfiguration skillConfiguration;
    /** The extension copy and its digest at launch; a changed file means the process is stale. */
    private final Path extension;
    private final String extensionDigest;
    private boolean skillRefreshFailed;
    private CompletableFuture<Void> resourceRefresh = CompletableFuture.completedFuture(null);
    private String modelConfigurationError;
    private AgentRuntimeHealth health = AgentRuntimeHealth.READY;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private boolean runtimeTerminated;
    private boolean closed;
    private String activeRunId;
    private String activeExternalRunId;
    private boolean cancelling;
    private JsonNode lastAssistantMessage;
    private final Map<String, Long> toolStartedAt = new HashMap<>();

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
        this(sessionId, session, process, rpc, eventConverter, eventSink, objectMapper,
                closeHook, modelConfiguration, () -> { });
    }

    public AgentRuntimeSessionHandleImpl(
            String sessionId,
            AgentRuntimeSessionRef session,
            PiProcessHandle process,
            IPiRpcTransport rpc,
            PiEventConverter eventConverter,
            IAgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper,
            Runnable closeHook,
            IPiModelConfiguration modelConfiguration,
            Runnable refreshToolAccess) {
        this(sessionId, session, process, rpc, eventConverter, eventSink, objectMapper,
                closeHook, modelConfiguration, refreshToolAccess, null, null);
    }

    AgentRuntimeSessionHandleImpl(String sessionId, AgentRuntimeSessionRef session, PiProcessHandle process,
            IPiRpcTransport rpc, PiEventConverter eventConverter, IAgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper, Runnable closeHook, IPiModelConfiguration modelConfiguration,
            Runnable refreshToolAccess, PiSkillConfiguration skillConfiguration) {
        this(sessionId, session, process, rpc, eventConverter, eventSink, objectMapper, closeHook,
                modelConfiguration, refreshToolAccess, skillConfiguration, null);
    }

    AgentRuntimeSessionHandleImpl(String sessionId, AgentRuntimeSessionRef session, PiProcessHandle process,
            IPiRpcTransport rpc, PiEventConverter eventConverter, IAgentRuntimeEventSink eventSink,
            ObjectMapper objectMapper, Runnable closeHook, IPiModelConfiguration modelConfiguration,
            Runnable refreshToolAccess, PiSkillConfiguration skillConfiguration, Path extension) {
        this.sessionId = sessionId;
        this.session = session;
        this.process = process;
        this.rpc = rpc;
        this.eventConverter = eventConverter;
        this.eventSink = eventSink;
        this.objectMapper = objectMapper;
        this.closeHook = closeHook;
        this.modelConfiguration = modelConfiguration;
        this.refreshToolAccess = refreshToolAccess;
        this.skillConfiguration = skillConfiguration;
        this.extension = extension;
        this.extensionDigest = extension == null ? null : PiSessionLauncherImpl.digest(extension);
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
        try {
            refreshToolAccess.run();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        AgentModelAccess modelAccess = modelConfiguration.prepare(request.model());
        modelConfigurationError = null;
        skillRefreshFailed = false;
        activeRunId = request.runId();
        lastAssistantMessage = null;
        toolStartedAt.clear();
        activeExternalRunId = request.runId();
        health = AgentRuntimeHealth.BUSY;
        ObjectNode payload = objectMapper.createObjectNode();
        String skillName = request.input().skillName();
        payload.put("message", skillName == null ? request.input().text()
                : "/skill:" + skillName + " " + request.input().text());
        AgentTrace.record("pi.prompt.sending", sessionId, request.runId(),
                Map.of("inputCharacters", request.input().text().length()));
        resourceRefresh = refreshSkills(request);
        CompletableFuture<JsonNode> response = resourceRefresh
                .thenCompose(ignored -> sendPrompt(request.runId(), objectMapper.createObjectNode()
                        .put("message", "/chat2db-refresh-model")))
                .thenCompose(ignored -> selectModel(request.runId(), modelAccess))
                .thenCompose(ignored -> sendPrompt(request.runId(), payload));
        response.whenComplete((ignored, error) -> {
            if (error != null) {
                failActiveRun(request.runId());
            }
        });
        return response.thenApply(result -> acknowledgeRun(request.runId(), result));
    }

    private CompletableFuture<Void> refreshSkills(AgentRuntimeRunRequest request) {
        if (skillConfiguration == null) return CompletableFuture.completedFuture(null);
        boolean changed = skillConfiguration.requiresReload(request.skills());
        if (!changed && !skillConfiguration.requiresVerification()) return CompletableFuture.completedFuture(null);
        CompletableFuture<JsonNode> reloaded;
        try {
            if (changed) {
                skillConfiguration.write(request.skills());
                reloaded = sendPrompt(request.runId(), objectMapper.createObjectNode().put("message", "/chat2db-reload-skills"));
            } else reloaded = CompletableFuture.completedFuture(null);
        } catch (java.io.IOException error) {
            reloaded = CompletableFuture.failedFuture(error);
        }
        return reloaded.thenCompose(ignored -> {
            synchronized (this) {
                requireActive(request.runId());
                return rpc.request("get_commands", objectMapper.createObjectNode());
            }
        }).thenAccept(commands -> {
            synchronized (this) {
                requireActive(request.runId());
                skillConfiguration.verify(commands, request.skills());
                AgentTrace.record("pi.skills.loaded", sessionId, request.runId(),
                        Map.of("skills", request.skills().stream().map(skill -> skill.name() + "@" + skill.digest()).toList()));
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) synchronized (this) { skillRefreshFailed = true; }
        });
    }

    @Override
    public CompletionStage<Void> cancel(AgentRuntimeCancelRequest request) {
        CompletableFuture<JsonNode> response;
        synchronized (this) {
            if (!sessionId.equals(request.sessionId())
                    || !request.runId().equals(activeRunId)
                    || !request.externalRunId().equals(activeExternalRunId)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown active Pi run"));
            }
            cancelling = true;
            ObjectNode payload = objectMapper.createObjectNode();
            CompletableFuture<Void> pendingRefresh = resourceRefresh;
            response = rpc.request("abort", payload)
                    .thenCompose(result -> pendingRefresh.handle((ignored, error) -> result));
        }
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

    @Override
    public CompletionStage<Void> termination() {
        return termination;
    }

    public void accept(JsonNode rawEvent) {
        AgentRuntimeEvent event = convertEvent(rawEvent);
        if (event != null) eventSink.emit(event);
    }

    private synchronized AgentRuntimeEvent convertEvent(JsonNode rawEvent) {
        if (activeRunId == null) {
            AgentTrace.record("pi.event.ignored", sessionId, null,
                    Map.of("type", rawEvent.path("type").asText()));
            return null;
        }
        if ("extension_error".equals(rawEvent.path("type").asText())
                && java.util.Set.of("command:chat2db-refresh-model", "command:chat2db-reload-skills")
                        .contains(rawEvent.path("extensionPath").asText())) {
            modelConfigurationError = rawEvent.path("error").asText("Pi model configuration refresh failed");
            return null;
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
        String type = rawEvent.path("type").asText();
        String toolCallId = rawEvent.path("toolCallId").asText();
        if ("tool_execution_start".equals(type)) {
            toolStartedAt.putIfAbsent(toolCallId, System.nanoTime());
        } else if ("tool_execution_end".equals(type)) {
            Long started = toolStartedAt.remove(toolCallId);
            if (started != null) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                ObjectNode timed = rawEvent.deepCopy();
                timed.put("durationMs", durationMs);
                if (timed.get("result") instanceof ObjectNode result) result.put("durationMs", durationMs);
                rawEvent = timed;
            }
        }
        AgentRuntimeEvent event = eventConverter.toRuntimeEvent(sessionId, activeRunId, rawEvent);
        if (event == null) {
            return null;
        }
        if (cancelling && isTerminal(event.type())) {
            return null;
        }
        if (isTerminal(event.type())) {
            finish(skillRefreshFailed ? AgentRuntimeHealth.FAILED : AgentRuntimeHealth.READY);
        }
        return event;
    }

    @Override
    public boolean needsRestart() {
        if (extensionDigest == null) return false;
        String packaged = PiSessionLauncherImpl.extensionDigest();
        String current = PiSessionLauncherImpl.digest(extension);
        if (packaged == null || current == null) return false;
        // Either the packaged extension was updated or this session's copy changed on disk.
        return !extensionDigest.equals(packaged) || !extensionDigest.equals(current);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            health = AgentRuntimeHealth.STOPPED;
        }
        try {
            try {
                process.close();
            } finally {
                try {
                    rpc.close();
                } finally {
                    try { modelConfiguration.close(); } finally { closeHook.run(); }
                }
            }
        } finally {
            // Publish termination only after the child is gone, so a caller that observes it can relaunch safely.
            runtimeTerminated(null);
        }
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

    private void completeCancellation(String runId) {
        AgentRuntimeEvent event;
        synchronized (this) {
            if (!runId.equals(activeRunId)) return;
            event = new AgentRuntimeEvent(
                    "cancelled-" + runId, sessionId, runId, AgentEventType.RUN_CANCELLED,
                    Map.of(), LocalDateTime.now());
            finish(AgentRuntimeHealth.READY);
        }
        eventSink.emit(event);
    }

    private void runtimeTerminated(Throwable error) {
        AgentRuntimeEvent event = null;
        synchronized (this) {
            if (runtimeTerminated) return;
            runtimeTerminated = true;
            if (activeRunId != null) {
                event = new AgentRuntimeEvent(
                        "runtime-stopped-" + activeRunId, sessionId, activeRunId,
                        AgentEventType.RUN_OUTCOME_UNKNOWN,
                        Map.of("reason", error == null
                                ? "runtime stopped"
                                : Objects.toString(error.getMessage(), error.getClass().getSimpleName())),
                        LocalDateTime.now());
            }
            finish(error == null ? AgentRuntimeHealth.STOPPED : AgentRuntimeHealth.FAILED);
        }
        try {
            if (event != null) eventSink.emit(event);
        } finally {
            // Registry cleanup must run only after the active run has reached its durable outcome.
            if (error == null) termination.complete(null);
            else termination.completeExceptionally(error);
        }
    }

    private void finish(AgentRuntimeHealth targetHealth) {
        toolStartedAt.clear();
        health = targetHealth;
        activeRunId = null;
        activeExternalRunId = null;
        cancelling = false;
    }

    private synchronized void failActiveRun(String runId) {
        if (runId.equals(activeRunId) && !cancelling) {
            finish(skillRefreshFailed ? AgentRuntimeHealth.FAILED : AgentRuntimeHealth.READY);
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
