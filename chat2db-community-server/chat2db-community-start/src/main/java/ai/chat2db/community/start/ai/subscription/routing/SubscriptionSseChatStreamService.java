package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.AiChatMessage;
import ai.chat2db.community.domain.api.model.ai.AiChatSession;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.domain.api.service.ai.IAiChatStreamService;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.Chat2dbMcpToolPolicy;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.start.ai.subscription.appserver.internal.AppServerHomeLayout;
import ai.chat2db.community.start.ai.subscription.lifecycle.ChatGptSubscriptionLifecycleService;
import ai.chat2db.community.start.ai.subscription.routing.mcp.DedicatedMcpBridge;
import ai.chat2db.community.start.ai.subscription.routing.tool.ToolExecutionKernel;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionAiRuntime;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionAttemptFenceListener;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionDesktopRuntimeCondition;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.web.api.adapter.ai.AiChatStreamAdapter;
import ai.chat2db.community.web.api.adapter.ai.ConsoleSseEmitter;
import ai.chat2db.community.web.api.model.request.ai.ChatMessage;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Primary chat router that preserves the API-key path and streams app-server notifications. */
@Component
@Primary
@Conditional(SubscriptionDesktopRuntimeCondition.class)
public final class SubscriptionSseChatStreamService implements IAiChatStreamService<ChatRequest, SseEmitter> {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionSseChatStreamService.class);
    /** Hard ceiling for a single provider turn regardless of intermediate events. */
    static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofMinutes(15);
    /**
     * If the app-server emits no matching turn events for this long after the turn is live,
     * terminalize as outcome-unknown, keep any partial answer, and release the provider lease.
     * Tests may temporarily override via {@link #overrideIdleEventTimeout(Duration)}.
     */
    /**
     * Keep this short enough that a hung provider does not look like a frozen UI,
     * but long enough that normal reasoning pauses and short tool gaps do not false-fire.
     */
    static final Duration DEFAULT_IDLE_EVENT_TIMEOUT = Duration.ofSeconds(45);
    /**
     * After an allowed direct MCP item starts, Chat2DB's tool kernel must begin within this
     * window. Code-mode wrappers that never hit the loopback MCP otherwise look like freezes.
     */
    /** Keep short: code-mode/MCP stalls previously burned 12–45s of “frozen” UI. */
    static final Duration DEFAULT_MCP_START_TIMEOUT = Duration.ofSeconds(5);
    private static volatile Duration turnTimeout = DEFAULT_TURN_TIMEOUT;
    private static volatile Duration idleEventTimeout = DEFAULT_IDLE_EVENT_TIMEOUT;
    private static volatile Duration mcpStartTimeout = DEFAULT_MCP_START_TIMEOUT;
    private static final Set<String> SAFE_NON_TOOL_ITEM_TYPES = Set.of(
            "userMessage", "agentMessage", "reasoning", "plan", "compacted", "contextCompaction");

    private final AiChatStreamAdapter apiKeyDelegate;
    private final SubscriptionAiRuntime runtime;
    private final IAiChatHistoryService historyService;
    private final IIdentityService identityService;
    private final AiRouteResolver routeResolver = new AiRouteResolver();

    public SubscriptionSseChatStreamService(
            AiChatStreamAdapter apiKeyDelegate,
            SubscriptionAiRuntime runtime,
            IAiChatHistoryService historyService,
            IIdentityService identityService) {
        this.apiKeyDelegate = apiKeyDelegate;
        this.runtime = runtime;
        this.historyService = historyService;
        this.identityService = identityService;
    }

    @Override
    public SseEmitter stream(ChatRequest request) {
        AiRouteDecision decision = routeResolver.resolve(request);
        if (decision.isApiKey()) {
            return apiKeyDelegate.stream(request);
        }
        SseEmitter emitter = buildEmitter(request);
        if (decision.isRejected()) {
            sendTerminalError(emitter, decision.rejectCode());
            return emitter;
        }
        startSubscriptionTurn(request, decision.modelRef(), emitter);
        return emitter;
    }

    private void startSubscriptionTurn(ChatRequest request, AiModelRef modelRef, SseEmitter emitter) {
        if (!runtime.capability().enabled()) {
            sendTerminalError(emitter, "SUBSCRIPTION_ROUTE_DISABLED");
            return;
        }
        IAiSubscriptionStateRepository repository = runtime.repository();
        AiProviderConnection connection = repository.connection(AiProviderEnum.OPENAI);
        if (connection.state() != AiProviderConnectionState.CONNECTED) {
            sendTerminalError(emitter, "SUBSCRIPTION_NOT_CONNECTED");
            return;
        }
        Optional<AiModelSnapshot> currentSnapshot = findRecentlyConfirmed(repository, modelRef);
        if (currentSnapshot.isEmpty()) {
            try {
                runtime.refreshModels(modelRef.provider());
            } catch (RuntimeException ignored) {
                // The request remains fail-closed; provider details are not exposed in the stream.
            }
            currentSnapshot = findRecentlyConfirmed(repository, modelRef);
            if (currentSnapshot.isEmpty()) {
                sendTerminalError(emitter, "MODEL_NOT_RECENTLY_CONFIRMED");
                return;
            }
        }
        String reasoningEffort = resolveReasoningEffort(currentSnapshot.orElseThrow(), request.getReasoningEffort());
        if (request.getReasoningEffort() != null && !request.getReasoningEffort().isBlank()
                && reasoningEffort == null) {
            sendTerminalError(emitter, "REASONING_EFFORT_NOT_SUPPORTED");
            return;
        }

        String messageId = request.getMessageId() == null || request.getMessageId().isBlank()
                ? UUID.randomUUID().toString() : request.getMessageId();
        String attemptId = UUID.randomUUID().toString();
        if (!repository.tryCreateAttemptAndAcquireProviderLease(
                attemptId, messageId, modelRef.provider(), AiAttemptState.CREATED,
                connection.fenceGeneration())) {
            sendTerminalError(emitter, "PROVIDER_BUSY");
            return;
        }
        try {
            repository.saveMessageModelSnapshot(messageId, modelRef);
        } catch (RuntimeException exception) {
            repository.transitionAttempt(attemptId, AiAttemptState.CREATED,
                    AiAttemptState.INTERRUPTED, null, null);
            repository.releaseProviderLease(modelRef.provider(), attemptId);
            sendTerminalError(emitter, "MESSAGE_SNAPSHOT_FAILED");
            return;
        }
        repository.transitionAttempt(attemptId, AiAttemptState.CREATED, AiAttemptState.SUBMITTING, null, null);

        String sessionId = prepareSession(request);
        TurnContext context = new TurnContext(attemptId, messageId, modelRef, sessionId, emitter);
        // Client stop/abort only closes the SSE; without these hooks the Codex turn and
        // provider lease keep running and the next send hits PROVIDER_BUSY.
        context.attachClientDisconnectHandlers();
        try {
            runtime.addEventListener(context);
            runtime.mcpBridge().bindActiveAttempt(attemptId);
            CodexAppServerPort appServer = runtime.appServer();
            AppServerThreadView thread = appServer.startThread(modelRef.modelId());
            context.threadId = thread.threadId();
            AppServerTurnView turn;
            try {
                turn = appServer.startTurn(context.threadId, buildPrompt(request, sessionId), reasoningEffort);
            } catch (RuntimeException ambiguous) {
                repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING,
                        AiAttemptState.OUTCOME_UNKNOWN, context.threadId, null);
                context.finish(AiAttemptState.OUTCOME_UNKNOWN, "TURN_OUTCOME_UNKNOWN");
                return;
            }
            context.turnId = turn.turnId();
            repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE,
                    context.threadId, context.turnId);
            if ("completed".equalsIgnoreCase(turn.status())) {
                context.finish(AiAttemptState.COMPLETED, null);
                return;
            }
            context.armWatchdogs();
        } catch (RuntimeException exception) {
            repository.findAttempt(attemptId).ifPresent(attempt -> {
                if (attempt.state().canTransitionTo(AiAttemptState.FAILED)) {
                    repository.transitionAttempt(attemptId, attempt.state(), AiAttemptState.FAILED,
                            context.threadId, context.turnId);
                }
            });
            context.finish(AiAttemptState.FAILED, "SUBSCRIPTION_TURN_FAILED");
        }
    }

    private Optional<AiModelSnapshot> findRecentlyConfirmed(
            IAiSubscriptionStateRepository repository, AiModelRef modelRef) {
        Instant cutoff = Instant.now().minus(ChatGptSubscriptionLifecycleService.MODEL_FRESHNESS);
        return repository.listCurrentModels(modelRef.provider()).stream()
                .filter(snapshot -> snapshot.modelRef().equals(modelRef)
                        && snapshot.available()
                        && !snapshot.discoveredAt().isBefore(cutoff))
                .findFirst();
    }

    static String resolveReasoningEffort(AiModelSnapshot snapshot, String requested) {
        List<String> supported = snapshot.supportedReasoningEfforts();
        String normalizedRequested = normalizeReasoningEffort(requested);
        if (normalizedRequested != null) {
            return supported.contains(normalizedRequested) ? normalizedRequested : null;
        }
        if (supported.contains("high")) {
            return "high";
        }
        if (snapshot.defaultReasoningEffort() != null
                && supported.contains(snapshot.defaultReasoningEffort())) {
            return snapshot.defaultReasoningEffort();
        }
        return supported.isEmpty() ? null : supported.get(0);
    }

    static Map<String, Object> terminalDonePayload(
            String finalAnswer, String sessionId, String messageId, String attemptId) {
        return Map.of(
                "type", "done",
                "messageType", "done",
                "content", "[DONE]",
                "finalAnswer", finalAnswer == null ? "" : finalAnswer,
                "sessionId", sessionId == null ? "" : sessionId,
                "messageId", messageId,
                "attemptId", attemptId);
    }

    /** Package-visible test hook. Pass {@code null} to restore the production default. */
    static void overrideIdleEventTimeout(Duration timeout) {
        idleEventTimeout = timeout == null ? DEFAULT_IDLE_EVENT_TIMEOUT : timeout;
    }

    /** Package-visible test hook. Pass {@code null} to restore the production default. */
    static void overrideTurnTimeout(Duration timeout) {
        turnTimeout = timeout == null ? DEFAULT_TURN_TIMEOUT : timeout;
    }

    /** Package-visible test hook. Pass {@code null} to restore the production default. */
    static void overrideMcpStartTimeout(Duration timeout) {
        mcpStartTimeout = timeout == null ? DEFAULT_MCP_START_TIMEOUT : timeout;
    }

    static Duration idleEventTimeout() {
        return idleEventTimeout;
    }

    static Duration turnTimeout() {
        return turnTimeout;
    }

    static Duration mcpStartTimeout() {
        return mcpStartTimeout;
    }

    private static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z][a-z0-9_-]{0,31}") ? normalized : null;
    }

    /** Fail-closed projection of pinned app-server item types visible to subscription turns. */
    static boolean isAllowedAppServerItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        String type = item.path("type").asText("");
        if (SAFE_NON_TOOL_ITEM_TYPES.contains(type)) {
            return true;
        }
        // Allowlisted Chat2DB MCP (mcpToolCall or FunctionCall namespace mcp__chat2db_subscription).
        if (Chat2dbMcpToolPolicy.isAllowlistedChat2dbMcpInvocation(item)) {
            return true;
        }
        if (Chat2dbMcpToolPolicy.isDeniedNativeOrCodeModeItem(item)) {
            return false;
        }
        // Pinned side-effect-free resource helpers only (empty catalogs).
        if ("mcpToolCall".equals(type)) {
            String server = item.path("server").asText("");
            String tool = item.path("tool").asText("");
            return Chat2dbMcpToolPolicy.isPinnedResourceMetadataCall(server, tool);
        }
        // tool_search is a Codex discovery helper (not a DB tool); tolerate it when AlwaysDefer is on.
        if ("functionCall".equals(type) || "function_call".equals(type) || "customToolCall".equals(type)) {
            String name = item.path("name").asText("").trim();
            return "tool_search".equals(name) || "toolSearch".equals(name);
        }
        return false;
    }

    static String denyReasonForItem(JsonNode item) {
        if (item != null && item.isObject() && Chat2dbMcpToolPolicy.isDeniedNativeOrCodeModeItem(item)) {
            return "APP_SERVER_CODE_MODE_NOT_ALLOWED";
        }
        return "APP_SERVER_TOOL_NOT_ALLOWED";
    }

    static boolean isModelRejection(JsonNode turn) {
        JsonNode error = turn == null ? null : turn.path("error");
        if (error == null || error.isMissingNode() || error.isNull()) {
            return false;
        }
        String code = error.path("code").asText("").toLowerCase(java.util.Locale.ROOT);
        String message = error.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
        if (code.equals("model_not_found") || code.equals("unsupported_model")
                || code.equals("model_not_allowed") || code.equals("model_access_denied")) {
            return true;
        }
        return message.contains("model") && (message.contains("not found")
                || message.contains("unsupported") || message.contains("not allowed")
                || message.contains("access denied") || message.contains("unavailable"));
    }

    static boolean isAuthFailure(JsonNode turn) {
        JsonNode error = turn == null ? null : turn.path("error");
        if (error == null || error.isMissingNode() || error.isNull()) {
            return false;
        }
        String code = error.path("code").asText("").toLowerCase(java.util.Locale.ROOT);
        String message = error.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
        if (code.contains("auth") || code.contains("unauthorized") || code.contains("unauthenticated")
                || code.contains("login") || code.contains("token_expired") || code.contains("forbidden")) {
            return true;
        }
        return (message.contains("auth") || message.contains("login") || message.contains("credential")
                || message.contains("token"))
                && (message.contains("expired") || message.contains("invalid") || message.contains("revoked")
                || message.contains("unauthorized") || message.contains("required")
                || message.contains("refresh"));
    }

    static boolean isRateLimitFailure(JsonNode turn) {
        JsonNode error = turn == null ? null : turn.path("error");
        if (error == null || error.isMissingNode() || error.isNull()) {
            return false;
        }
        String code = error.path("code").asText("").toLowerCase(java.util.Locale.ROOT);
        String message = error.path("message").asText("").toLowerCase(java.util.Locale.ROOT);
        return code.contains("rate_limit") || code.contains("ratelimit") || code.contains("quota")
                || message.contains("rate limit") || message.contains("rate_limit")
                || message.contains("too many requests") || message.contains("quota");
    }

    void rejectModelAndRefresh(AiModelRef modelRef) {
        try {
            runtime.refreshModels(modelRef.provider());
        } catch (RuntimeException ignored) {
            // The rejected model is disabled below even if discovery is currently unavailable.
        }
        runtime.repository().markModelRejected(modelRef, "MODEL_REJECTED");
    }

    private String prepareSession(ChatRequest request) {
        Long userId = identityService.currentUserId();
        try {
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                AiChatSession session = historyService.createSession(userId, request.getInput());
                sessionId = session.getId();
            }
            historyService.addMessage(addMessage(sessionId, userId, "user", request.getInput(), null));
            return sessionId;
        } catch (RuntimeException exception) {
            return request.getSessionId();
        }
    }

    private String buildPrompt(ChatRequest request, String sessionId) {
        List<ChatMessage> history = new ArrayList<>();
        if (request.getHistory() != null && !request.getHistory().isEmpty()) {
            history.addAll(request.getHistory());
        } else if (sessionId != null && !sessionId.isBlank()) {
            try {
                List<AiChatMessage> stored = historyService.getHistoryForAI(sessionId, identityService.currentUserId());
                int end = stored.size();
                if (end > 0 && "user".equalsIgnoreCase(stored.get(end - 1).getRole())) {
                    end--;
                }
                int start = Math.max(0, end - 20);
                for (AiChatMessage message : stored.subList(start, end)) {
                    ChatMessage converted = new ChatMessage();
                    converted.setRole(message.getRole());
                    converted.setContent(message.getContent());
                    history.add(converted);
                }
            } catch (RuntimeException ignored) {
                // Current input still proceeds; no provider request is replayed.
            }
        }
        StringBuilder prompt = new StringBuilder();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            prompt.append("System instructions:\n").append(request.getSystemPrompt()).append("\n\n");
        }
        // Pin the renderer cascader selection so the model prioritizes the chosen datasource.
        String selection = Chat2dbMcpToolPolicy.formatUiSelectionContext(
                request.getDataSourceId(), request.getDatabaseName(), request.getSchemaName());
        if (!selection.isBlank()) {
            prompt.append(selection).append('\n');
        }
        if (!history.isEmpty()) {
            prompt.append("Conversation history:\n");
            for (ChatMessage message : history) {
                if (message != null && message.getRole() != null && message.getContent() != null) {
                    prompt.append(message.getRole()).append(": ").append(message.getContent()).append('\n');
                }
            }
            prompt.append('\n');
        }
        prompt.append("user: ").append(request.getInput());
        return prompt.toString();
    }

    private AiChatMessageAddRequest addMessage(
            String sessionId, Long userId, String role, String content, String reasoning) {
        AiChatMessageAddRequest add = new AiChatMessageAddRequest();
        add.setSessionId(sessionId);
        add.setUserId(userId);
        add.setRole(role);
        add.setContent(content);
        add.setReasoningContent(reasoning);
        return add;
    }

    private SseEmitter buildEmitter(ChatRequest request) {
        if (ConfigUtils.isDesktop() && request.getConsoleResult() != null) {
            return new ConsoleSseEmitter(request.getConsoleResult());
        }
        return new SseEmitter(0L);
    }

    private void sendTerminalError(SseEmitter emitter, String errorCode) {
        LOG.warn("subscription stream terminated code={}", safeErrorCode(errorCode));
        sendEvent(emitter, "error", Map.of(
                "type", "error", "messageType", "error", "errorCode", errorCode,
                "content", errorCode));
        sendEvent(emitter, "done", Map.of("type", "done", "messageType", "done", "content", "[DONE]"));
        emitter.complete();
    }

    private static String safeErrorCode(String errorCode) {
        return errorCode != null && errorCode.matches("[A-Z0-9_]{1,64}")
                ? errorCode : "SUBSCRIPTION_STREAM_ERROR";
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            if (emitter instanceof ConsoleSseEmitter console) {
                console.sendData(event, data);
            } else {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
        } catch (IOException exception) {
            // Broken pipe after Stop: completeWithError triggers onError → client-disconnect cleanup.
            try {
                emitter.completeWithError(exception);
            } catch (RuntimeException ignored) {
                // Emitter may already be completed by the disconnect handler.
            }
        }
    }

    private final class TurnContext implements SubscriptionAttemptFenceListener {

        private final String attemptId;
        private final String messageId;
        private final AiProviderEnum provider;
        private final AiModelRef modelRef;
        private final String sessionId;
        private final SseEmitter emitter;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicLong outputSequence = new AtomicLong();
        private final AtomicLong idleGeneration = new AtomicLong();
        private final AtomicLong mcpStartGeneration = new AtomicLong();
        private final Object eventGate = new Object();
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private volatile String threadId;
        private volatile String turnId;

        private TurnContext(String attemptId, String messageId, AiModelRef modelRef,
                            String sessionId, SseEmitter emitter) {
            this.attemptId = attemptId;
            this.messageId = messageId;
            this.provider = modelRef.provider();
            this.modelRef = modelRef;
            this.sessionId = sessionId;
            this.emitter = emitter;
        }

        /**
         * When the renderer aborts the SSE (Stop), interrupt the app-server turn and release
         * the single-provider lease so a new send can start immediately.
         * {@link #finish} is idempotent; normal completion also fires onCompletion harmlessly.
         */
        private void attachClientDisconnectHandlers() {
            Runnable clientGone = this::onClientDisconnected;
            emitter.onCompletion(clientGone);
            emitter.onTimeout(clientGone);
            emitter.onError(error -> clientGone.run());
        }

        private void onClientDisconnected() {
            synchronized (eventGate) {
                if (terminal.get()) {
                    return;
                }
                LOG.info("subscription stream client disconnected attemptId={} threadId={} turnId={}",
                        attemptId, threadId, turnId);
                interruptBestEffort();
                finish(AiAttemptState.INTERRUPTED, "CLIENT_DISCONNECTED");
            }
        }

        private void armWatchdogs() {
            scheduleAbsoluteTimeout();
            touchIdleWatchdog();
        }

        private void scheduleAbsoluteTimeout() {
            Duration timeout = turnTimeout();
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(this::absoluteTimeout);
        }

        private void touchIdleWatchdog() {
            long generation = idleGeneration.incrementAndGet();
            Duration timeout = idleEventTimeout();
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> idleTimeout(generation));
        }

        private void armMcpStartWatchdog() {
            long generation = mcpStartGeneration.incrementAndGet();
            Duration timeout = mcpStartTimeout();
            CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> onMcpStartTimeout(generation));
        }

        private void clearMcpStartWatchdog() {
            mcpStartGeneration.incrementAndGet();
        }

        @Override
        public void onNotification(String method, JsonNode params) {
            synchronized (eventGate) {
                if (terminal.get() || !matches(params)) {
                    return;
                }
                // Any matching provider event proves the turn is still alive; reset idle silence.
                touchIdleWatchdog();
                if ("item/started".equals(method)) {
                    JsonNode item = params.path("item");
                    if (!isAllowedAppServerItem(item)) {
                        rejectNativeToolItem(params);
                    } else if (Chat2dbMcpToolPolicy.isDirectChat2dbMcpToolCall(item)) {
                        // Direct MCP must reach Chat2DB's journal promptly; code-mode wrappers hang here.
                        armMcpStartWatchdog();
                        emitToolLifecycle("tool_call", item, null);
                    }
                } else if ("item/completed".equals(method) || "item/agentMessage/delta".equals(method)
                        || "item/reasoning/summaryTextDelta".equals(method)
                        || "turn/completed".equals(method)) {
                    clearMcpStartWatchdog();
                    if ("item/completed".equals(method)
                            && Chat2dbMcpToolPolicy.isDirectChat2dbMcpToolCall(params.path("item"))) {
                        emitToolLifecycle("tool_result", params.path("item"), "ok");
                    }
                    if ("item/agentMessage/delta".equals(method)) {
                        appendDelta(params.path("delta").asText(""), false);
                    } else if ("item/reasoning/summaryTextDelta".equals(method)) {
                        appendDelta(params.path("delta").asText(""), true);
                    } else if ("turn/completed".equals(method)) {
                        JsonNode turn = params.path("turn");
                        String status = turn.path("status").asText("failed");
                        if ("completed".equalsIgnoreCase(status)) {
                            finish(AiAttemptState.COMPLETED, null);
                        } else if ("interrupted".equalsIgnoreCase(status)) {
                            finish(AiAttemptState.INTERRUPTED, "INTERRUPTED");
                        } else if (isModelRejection(turn)) {
                            rejectModelAndRefresh();
                        } else if (isAuthFailure(turn)) {
                            try {
                                runtime.markOpenAiReauthRequired("TURN_AUTH_FAILURE");
                            } catch (RuntimeException ignored) {
                                // Still fail the attempt even if reauth flag write fails.
                            }
                            finish(AiAttemptState.FAILED, "APP_SERVER_AUTH_REQUIRED");
                        } else if (isRateLimitFailure(turn)) {
                            try {
                                runtime.repository().markModelRejected(modelRef, "RATE_LIMITED");
                            } catch (RuntimeException ignored) {
                                // Attempt still fails closed.
                            }
                            finish(AiAttemptState.FAILED, "APP_SERVER_RATE_LIMITED");
                        } else {
                            finish(AiAttemptState.FAILED, "APP_SERVER_TURN_FAILED");
                        }
                    }
                } else if ("error".equals(method)) {
                    clearMcpStartWatchdog();
                    sendEvent(emitter, "error", Map.of(
                            "type", "error", "messageType", "error",
                            "errorCode", "APP_SERVER_STREAM_ERROR", "content", "APP_SERVER_STREAM_ERROR"));
                }
            }
        }

        @Override
        public String attemptId() {
            return attemptId;
        }

        @Override
        public void onAttemptFenced(AiAttemptState state, String errorCode) {
            synchronized (eventGate) {
                if (terminal.get()) {
                    return;
                }
                try {
                    if (threadId != null && turnId != null) {
                        runtime.appServer().interruptTurn(threadId, turnId);
                    }
                } catch (RuntimeException ignored) {
                    // The local stream fence remains authoritative when interrupt acknowledgement is unavailable.
                }
                finish(state, errorCode == null ? "ATTEMPT_FENCED" : errorCode);
            }
        }

        private void rejectModelAndRefresh() {
            SubscriptionSseChatStreamService.this.rejectModelAndRefresh(modelRef);
            finish(AiAttemptState.FAILED, "MODEL_REJECTED");
        }

        private void rejectNativeToolItem(JsonNode params) {
            String eventThreadId = params.path("threadId").asText(threadId);
            String eventTurnId = params.path("turnId").asText(turnId);
            String errorCode = denyReasonForItem(params.path("item"));
            try {
                if (eventThreadId != null && eventTurnId != null) {
                    runtime.appServer().interruptTurn(eventThreadId, eventTurnId);
                }
            } catch (RuntimeException ignored) {
                // The durable attempt still fails closed even when interrupt acknowledgement is unavailable.
            }
            finish(AiAttemptState.FAILED, errorCode);
        }

        private void onMcpStartTimeout(long generation) {
            synchronized (eventGate) {
                if (terminal.get() || mcpStartGeneration.get() != generation) {
                    return;
                }
                AiAttemptState currentState = runtime.repository().findAttempt(attemptId)
                        .map(AiAttempt::state)
                        .orElse(AiAttemptState.FAILED);
                // Kernel has begun journaling the tool; idle/tool path owns the rest of the wait.
                if (currentState == AiAttemptState.TOOL_ACTIVE
                        || currentState == AiAttemptState.TOOL_OUTCOME_UNKNOWN
                        || currentState == AiAttemptState.COMPLETED
                        || currentState == AiAttemptState.FAILED
                        || currentState == AiAttemptState.INTERRUPTED
                        || currentState == AiAttemptState.OUTCOME_UNKNOWN) {
                    return;
                }
                LOG.warn("subscription mcp start stalled attemptId={} state={} timeoutMs={}",
                        attemptId, currentState, SubscriptionSseChatStreamService.mcpStartTimeout().toMillis());
                interruptBestEffort();
                finish(AiAttemptState.FAILED, "APP_SERVER_MCP_STALLED");
            }
        }

        private boolean matches(JsonNode params) {
            if (params == null || params.isNull()) {
                return false;
            }
            String eventThread = params.path("threadId").asText(null);
            String eventTurn = params.path("turnId").asText(null);
            JsonNode turn = params.path("turn");
            if (eventTurn == null && turn.isObject()) {
                eventTurn = turn.path("id").asText(null);
            }
            return (threadId == null || eventThread == null || threadId.equals(eventThread))
                    && (turnId == null || eventTurn == null || turnId.equals(eventTurn));
        }

        /**
         * Surfaces MCP tool start/finish in the renderer thought strip (name only; no SQL/args).
         */
        private void emitToolLifecycle(String messageType, JsonNode item, String statusHint) {
            if (terminal.get() || item == null || !item.isObject()) {
                return;
            }
            String toolName = extractMcpToolName(item);
            if (toolName == null || toolName.isBlank()) {
                toolName = "mcp_tool";
            }
            String content = "tool_result".equals(messageType)
                    ? (statusHint == null || statusHint.isBlank() ? "returned" : statusHint)
                    : "";
            sendEvent(emitter, messageType, Map.of(
                    "type", messageType,
                    "messageType", messageType,
                    "name", toolName,
                    "content", content,
                    "ts", Instant.now().toEpochMilli()));
        }

        private static String extractMcpToolName(JsonNode item) {
            String tool = textOrBlank(item.path("tool"));
            if (!tool.isBlank()) {
                return tool;
            }
            String name = textOrBlank(item.path("name"));
            if (name.startsWith("mcp__chat2db_subscription__")) {
                return name.substring("mcp__chat2db_subscription__".length());
            }
            if (name.startsWith("mcp__chat2db_subscription")) {
                String rest = name.substring("mcp__chat2db_subscription".length());
                return rest.startsWith("__") ? rest.substring(2) : rest;
            }
            return name;
        }

        private static String textOrBlank(JsonNode node) {
            if (node == null || node.isNull() || !node.isTextual()) {
                return "";
            }
            return node.asText("").trim();
        }

        private void appendDelta(String delta, boolean reasoningDelta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            IAiSubscriptionStateRepository repository = runtime.repository();
            AiAttemptState currentState = repository.findAttempt(attemptId)
                    .map(AiAttempt::state).orElse(AiAttemptState.FAILED);
            if (currentState == AiAttemptState.ACTIVE) {
                try {
                    repository.transitionAttempt(attemptId, AiAttemptState.ACTIVE,
                            AiAttemptState.OUTPUT_VISIBLE, threadId, turnId);
                    currentState = AiAttemptState.OUTPUT_VISIBLE;
                } catch (RuntimeException fenced) {
                    finish(AiAttemptState.INTERRUPTED, "ATTEMPT_FENCED");
                    return;
                }
            }
            if (currentState != AiAttemptState.OUTPUT_VISIBLE
                    && currentState != AiAttemptState.TOOL_ACTIVE) {
                finish(currentState, terminalErrorFor(currentState));
                return;
            }
            long sequence = outputSequence.incrementAndGet();
            try {
                repository.appendAttemptOutput(attemptId, sequence,
                        reasoningDelta ? AiAttemptOutputKind.REASONING : AiAttemptOutputKind.ASSISTANT_TEXT,
                        delta, true, false);
            } catch (RuntimeException fenced) {
                finish(AiAttemptState.INTERRUPTED, "ATTEMPT_FENCED");
                return;
            }
            if (terminal.get()) {
                return;
            }
            if (reasoningDelta) {
                reasoning.append(delta);
                sendEvent(emitter, "reasoning", Map.of(
                        "type", "reasoning", "messageType", "reasoning", "content", delta,
                        "ts", Instant.now().toEpochMilli()));
            } else {
                answer.append(delta);
                sendEvent(emitter, "answer", Map.of(
                        "type", "answer", "messageType", "answer", "content", delta,
                        "ts", Instant.now().toEpochMilli()));
            }
        }

        private void absoluteTimeout() {
            synchronized (eventGate) {
                if (terminal.get()) {
                    return;
                }
                interruptBestEffort();
                // Keep any already-streamed answer in the done payload; never claim completion.
                finish(AiAttemptState.OUTCOME_UNKNOWN, "TURN_TIMEOUT_OUTCOME_UNKNOWN");
            }
        }

        private void idleTimeout(long generation) {
            synchronized (eventGate) {
                if (terminal.get() || idleGeneration.get() != generation) {
                    return;
                }
                AiAttemptState currentState = runtime.repository().findAttempt(attemptId)
                        .map(AiAttempt::state)
                        .orElse(AiAttemptState.FAILED);
                // Database tools may run for a long time without app-server deltas; keep waiting.
                if (currentState == AiAttemptState.TOOL_ACTIVE) {
                    touchIdleWatchdog();
                    return;
                }
                LOG.warn("subscription turn idle timeout attemptId={} state={} idleMs={}",
                        attemptId, currentState, idleEventTimeout().toMillis());
                interruptBestEffort();
                // Partial answer remains in `answer` and is returned via terminalDonePayload.
                finish(AiAttemptState.OUTCOME_UNKNOWN, "TURN_IDLE_TIMEOUT_OUTCOME_UNKNOWN");
            }
        }

        private void interruptBestEffort() {
            try {
                if (threadId != null && turnId != null) {
                    runtime.appServer().interruptTurn(threadId, turnId);
                }
            } catch (RuntimeException ignored) {
                // Outcome still becomes unknown; never replay turn/start.
            }
        }

        private void finish(AiAttemptState requestedState, String errorCode) {
            synchronized (eventGate) {
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                // Invalidate any pending idle / MCP-start callbacks so they cannot re-enter cleanup.
                idleGeneration.incrementAndGet();
                mcpStartGeneration.incrementAndGet();
                IAiSubscriptionStateRepository repository = runtime.repository();
                AiAttemptState authoritativeState = requestedState;
                try {
                    var attempt = repository.findAttempt(attemptId);
                    if (attempt.isEmpty()) {
                        authoritativeState = AiAttemptState.FAILED;
                    } else if (attempt.get().state() == requestedState) {
                        authoritativeState = requestedState;
                    } else if (attempt.get().state().canTransitionTo(requestedState)) {
                        repository.transitionAttempt(attemptId, attempt.get().state(), requestedState,
                                threadId, turnId);
                        authoritativeState = requestedState;
                    } else {
                        authoritativeState = attempt.get().state();
                    }
                } catch (RuntimeException ledgerFailure) {
                    if (requestedState == AiAttemptState.COMPLETED) {
                        authoritativeState = AiAttemptState.FAILED;
                    }
                }
                boolean completionConfirmed = requestedState == AiAttemptState.COMPLETED
                        && authoritativeState == AiAttemptState.COMPLETED;
                if (completionConfirmed && sessionId != null && !sessionId.isBlank()
                        && answer.length() > 0) {
                    try {
                        historyService.addMessage(addMessage(sessionId, identityService.currentUserId(),
                                "assistant", answer.toString(), reasoning.toString()));
                    } catch (RuntimeException ignored) {
                        // Completion remains authoritative even if optional local history persistence fails.
                    }
                }
                String terminalError = errorCode;
                if (!completionConfirmed && requestedState == AiAttemptState.COMPLETED && terminalError == null) {
                    terminalError = terminalErrorFor(authoritativeState);
                }
                if (terminalError != null) {
                    sendEvent(emitter, "error", Map.of(
                            "type", "error", "messageType", "error", "errorCode", terminalError,
                            "content", terminalError));
                }
                sendEvent(emitter, "done", terminalDonePayload(
                        answer.toString(), sessionId, messageId, attemptId));
                runtime.removeEventListener(this);
                try {
                    DedicatedMcpBridge bridge = runtime.mcpBridge();
                    if (bridge.activeAttemptId().filter(attemptId::equals).isPresent()) {
                        bridge.clearActiveAttempt();
                    }
                } catch (RuntimeException ignored) {
                    // Runtime shutdown may already have stopped the bridge; ledger cleanup must continue.
                }
                try {
                    repository.releaseProviderLease(provider, attemptId);
                } catch (RuntimeException ignored) {
                    // The terminal stream must still close when durable cleanup is temporarily unavailable.
                }
                emitter.complete();
            }
        }

        private String terminalErrorFor(AiAttemptState state) {
            return switch (state) {
                case TOOL_OUTCOME_UNKNOWN -> "TOOL_OUTCOME_UNKNOWN";
                case OUTCOME_UNKNOWN -> "TURN_OUTCOME_UNKNOWN";
                case INTERRUPTED -> "ATTEMPT_FENCED";
                case FAILED -> "SUBSCRIPTION_TURN_FAILED";
                case COMPLETED -> null;
                default -> "ATTEMPT_FENCED";
            };
        }
    }
}
