package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionRuntimeGate;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerBinarySpec;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerMcpEndpoint;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerSupervisorConfig;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerSupervisor;
import ai.chat2db.community.start.ai.subscription.lifecycle.BrowserTargetResolution;
import ai.chat2db.community.start.ai.subscription.lifecycle.ChatGptSubscriptionLifecycleService;
import ai.chat2db.community.start.ai.subscription.lifecycle.LoginType;
import ai.chat2db.community.start.ai.subscription.lifecycle.SafeLoginStartResponse;
import ai.chat2db.community.start.ai.subscription.routing.mcp.DedicatedMcpBridge;
import ai.chat2db.community.start.ai.subscription.routing.mcp.McpToolCallHandler;
import ai.chat2db.community.start.ai.subscription.routing.mcp.StreamableHttpMcpBridge;
import ai.chat2db.community.start.ai.subscription.routing.tool.ToolExecutionKernel;
import ai.chat2db.community.storage.ai.H2AiSubscriptionStateRepository;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Owns the packaged app-server, dedicated MCP bridge, ledger, and account lifecycle. */
@Component
@Conditional(SubscriptionDesktopRuntimeCondition.class)
public final class SubscriptionAiRuntime implements SubscriptionAiFacade {

    enum StartupStage {
        LEDGER_RECOVERY,
        MANIFEST_LOAD,
        MCP_BIND,
        APP_SERVER_START,
        LIFECYCLE_RECOVERY
    }

    public static final String FEATURE_PROPERTY = "chat2db.ai.subscription.enabled";
    public static final String MANIFEST_PROPERTY = "chat2db.ai.subscription.runtime-manifest";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAiRuntime.class);

    private final AiToolMcpAdapter toolAdapter;
    private final ExecutorService loginCompletionExecutor;
    private final SystemKeyringAvailabilityProbe keyringProbe = new SystemKeyringAvailabilityProbe();
    private final CopyOnWriteArrayList<AppServerEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> toolSequences = new ConcurrentHashMap<>();

    private volatile boolean ledgerReady;
    private volatile IAiSubscriptionStateRepository stateRepository;
    private volatile String startupFailureCode;
    private volatile CodexAppServerSupervisor supervisor;
    private volatile DedicatedMcpBridge mcpBridge;
    private volatile ChatGptSubscriptionLifecycleService lifecycle;
    private volatile boolean reasoningCapabilityRefreshAttempted;

    public SubscriptionAiRuntime(AiToolMcpAdapter toolAdapter) {
        this.toolAdapter = toolAdapter;
        this.loginCompletionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "subscription-login-completion");
            thread.setDaemon(true);
            return thread;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startAfterSpringReady() {
        if (!baseSurfaceEnabled() || supervisor != null) {
            return;
        }
        StartupStage startupStage = StartupStage.LEDGER_RECOVERY;
        try {
            ensureLedger();
            IAiSubscriptionStateRepository repository = stateRepository();
            startupStage = StartupStage.MANIFEST_LOAD;
            SubscriptionRuntimeManifest manifest = SubscriptionRuntimeManifest.load(
                    System.getProperty(MANIFEST_PROPERTY));
            ToolExecutionKernel toolKernel = new ToolExecutionKernel(
                    repository, new SubscriptionMcpToolExecutor(toolAdapter, new ObjectMapper()));
            startupStage = StartupStage.MCP_BIND;
            StreamableHttpMcpBridge bridge = new StreamableHttpMcpBridge(true,
                    (attemptId, toolName, argumentsJson) -> invokeTool(toolKernel, attemptId, toolName, argumentsJson));
            bridge.start();
            if (!bridge.isEnabled()) {
                throw new IllegalStateException("Dedicated MCP bridge failed to bind");
            }
            // Publish immediately so a later startup failure closes this loopback server.
            this.mcpBridge = bridge;
            String mcpUrl = "http://127.0.0.1:" + bridge.boundAddress().orElseThrow().getPort()
                    + StreamableHttpMcpBridge.MCP_PATH;
            AppServerMcpEndpoint mcpEndpoint = new AppServerMcpEndpoint(
                    mcpUrl,
                    AppServerMcpEndpoint.DEFAULT_CAPABILITY_ENV_VAR,
                    bridge.capabilityToken().orElseThrow());
            Path runtimeRoot = Path.of(ConfigUtils.getEnvBasePath(), "runtime", "subscription-ai");
            AppServerBinarySpec binarySpec = new AppServerBinarySpec(
                    manifest.binaryPath(), manifest.version(), manifest.binarySha256(), manifest.protocolLabel());
            AppServerSupervisorConfig config = new AppServerSupervisorConfig(
                    true,
                    binarySpec,
                    runtimeRoot.resolve("codex-home"),
                    runtimeRoot.resolve("workdir"),
                    manifest.stdioLaunchCommand(),
                    manifest.protocolLabel(),
                    mcpEndpoint);
            startupStage = StartupStage.APP_SERVER_START;
            CodexAppServerSupervisor nextSupervisor = new CodexAppServerSupervisor(config, keyringProbe);
            nextSupervisor.addEventListener(this::onAppServerNotification);
            nextSupervisor.start();
            // Publish immediately so lifecycle construction/recovery failures also stop the child process.
            this.supervisor = nextSupervisor;
            ChatGptSubscriptionLifecycleService nextLifecycle = new ChatGptSubscriptionLifecycleService(
                    nextSupervisor, repository, this::capability, java.time.Clock.systemUTC(),
                    this::fenceCurrentActiveAttempt);
            startupStage = StartupStage.LIFECYCLE_RECOVERY;
            nextLifecycle.recoverOnStartup();
            this.lifecycle = nextLifecycle;
            this.startupFailureCode = null;
        } catch (RuntimeException exception) {
            String safeFailureCode = safeStartupFailureCode(startupStage, exception);
            startupFailureCode = safeFailureCode;
            stopRuntimeComponents();
            log.warn("Subscription AI runtime stayed disabled stage={} reason={}",
                    startupStage, safeFailureCode);
        }
    }

    static String safeStartupFailureCode(StartupStage startupStage, RuntimeException exception) {
        if (exception instanceof AppServerException appServerException) {
            return startupStage.name() + "_" + appServerException.reason().name();
        }
        return startupStage.name() + "_FAILED";
    }

    @Override
    public AiSubscriptionCapability capability() {
        boolean keyringAvailable = keyringProbe.isKeyringAvailable();
        CodexAppServerSupervisor current = supervisor;
        return AiSubscriptionRuntimeGate.evaluate(
                Boolean.getBoolean(FEATURE_PROPERTY),
                ConfigUtils.isCommunity(),
                ConfigUtils.isDesktop(),
                ConfigUtils.isShowGUI(),
                ConfigUtils.isRelease(),
                keyringAvailable,
                current != null && current.isEnabled());
    }

    @Override
    public List<ProviderView> providers() {
        ensureLedger();
        IAiSubscriptionStateRepository repository = stateRepository();
        AiSubscriptionCapability capability = capability();
        AiProviderConnection chatGpt = repository.connection(AiProviderEnum.OPENAI);
        AiProviderConnection superGrok = new AiProviderConnection(
                AiProviderEnum.XAI, AiProviderConnectionState.DISABLED, null, 0, null, null);
        return List.of(
                new ProviderView(AiProviderEnum.OPENAI, "ChatGPT", chatGpt,
                        capability.enabled(), true, capability.disabledReason().name()),
                new ProviderView(AiProviderEnum.XAI, "SuperGrok", superGrok,
                        false, false, "PROVIDER_CONTRACT_UNAVAILABLE"));
    }

    @Override
    public SafeLoginStartResponse startConnect(AiProviderEnum provider) {
        requireChatGpt(provider);
        ChatGptSubscriptionLifecycleService current = requireLifecycle();
        SafeLoginStartResponse response = current.startLogin(LoginType.BROWSER);
        BrowserTargetResolution target = current.resolveBrowserTarget(response.attemptId());
        if (!target.allowed()) {
            current.cancelLogin(response.attemptId());
            throw new IllegalStateException("Browser target was rejected");
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IllegalStateException("Desktop browser integration unavailable");
            }
            Desktop.getDesktop().browse(URI.create(target.httpsUrl()));
        } catch (Exception exception) {
            current.cancelLogin(response.attemptId());
            throw new IllegalStateException("Unable to open the ChatGPT login page", exception);
        }
        return response;
    }

    @Override
    public void cancelConnect(AiProviderEnum provider, String attemptId) {
        requireChatGpt(provider);
        requireLifecycle().cancelLogin(attemptId);
    }

    @Override
    public AiProviderConnection disconnect(AiProviderEnum provider) {
        requireChatGpt(provider);
        requireLifecycle().signOut();
        return stateRepository().connection(provider);
    }

    @Override
    public AiProviderConnection retryDiscovery(AiProviderEnum provider) {
        requireChatGpt(provider);
        requireLifecycle().refreshModels();
        return stateRepository().connection(provider);
    }

    @Override
    public List<AiModelSnapshot> models() {
        ensureLedger();
        IAiSubscriptionStateRepository repository = stateRepository();
        List<AiModelSnapshot> snapshots = repository.listCurrentModels(AiProviderEnum.OPENAI);
        if (capability().enabled()) {
            snapshots = refreshMissingReasoningCapabilities(repository, snapshots);
        }
        return projectModelFreshness(snapshots, Instant.now());
    }

    List<AiModelSnapshot> refreshMissingReasoningCapabilities(
            IAiSubscriptionStateRepository repository, List<AiModelSnapshot> snapshots) {
        if (!reasoningCapabilityRefreshAttempted
                && repository.connection(AiProviderEnum.OPENAI).state() == AiProviderConnectionState.CONNECTED
                && snapshots.stream().anyMatch(snapshot -> snapshot.available()
                && snapshot.supportedReasoningEfforts().isEmpty())) {
            try {
                ChatGptSubscriptionLifecycleService current = lifecycle;
                if (current == null) {
                    throw new IllegalStateException("Subscription lifecycle is not ready");
                }
                current.refreshModels();
                snapshots = repository.listCurrentModels(AiProviderEnum.OPENAI);
                reasoningCapabilityRefreshAttempted = true;
            } catch (RuntimeException exception) {
                log.warn("Subscription model reasoning capability refresh failed reason={}",
                        exception.getClass().getSimpleName());
            }
        }
        return snapshots;
    }

    static List<AiModelSnapshot> projectModelFreshness(List<AiModelSnapshot> snapshots, Instant now) {
        Instant cutoff = now.minus(ChatGptSubscriptionLifecycleService.MODEL_FRESHNESS);
        return snapshots.stream().map(snapshot -> {
            if (snapshot.discoveredAt().isBefore(cutoff)) {
                return new AiModelSnapshot(snapshot.modelRef(), snapshot.displayName(), snapshot.discoveredAt(),
                        false, "MODEL_SNAPSHOT_STALE", snapshot.supportedReasoningEfforts(),
                        snapshot.defaultReasoningEffort());
            }
            return snapshot;
        }).toList();
    }

    @Override
    public List<AiModelSnapshot> refreshModels(AiProviderEnum provider) {
        retryDiscovery(provider);
        return models();
    }

    @Override
    public Optional<AiModelRef> globalDefault() {
        ensureLedger();
        return stateRepository().getGlobalDefault();
    }

    @Override
    public Optional<AiModelRef> conversationModel(String conversationId) {
        ensureLedger();
        return stateRepository().getConversationModel(conversationId);
    }

    @Override
    public void setGlobalDefault(AiModelRef modelRef) {
        ensureSelectable(modelRef);
        stateRepository().setGlobalDefault(modelRef);
    }

    @Override
    public void setConversationModel(String conversationId, AiModelRef modelRef) {
        ensureSelectable(modelRef);
        stateRepository().setConversationModel(conversationId, modelRef);
    }

    @Override
    public List<AiAttempt> attempts(String messageId, String conversationId) {
        ensureLedger();
        return messageId == null || messageId.isBlank()
                ? List.of() : stateRepository().listAttemptsByMessageId(messageId);
    }

    public CodexAppServerPort appServer() {
        return requireLifecyclePort();
    }

    public IAiSubscriptionStateRepository repository() {
        ensureLedger();
        return stateRepository();
    }

    public DedicatedMcpBridge mcpBridge() {
        DedicatedMcpBridge current = mcpBridge;
        if (current == null || !current.isEnabled()) {
            throw new IllegalStateException("Dedicated MCP bridge is unavailable");
        }
        return current;
    }

    public void addEventListener(AppServerEventListener listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(AppServerEventListener listener) {
        eventListeners.remove(listener);
    }

    public String startupFailureCode() {
        return startupFailureCode;
    }

    @PreDestroy
    public synchronized void shutdown() {
        loginCompletionExecutor.shutdownNow();
        stopRuntimeComponents();
    }

    private McpToolCallHandler.McpToolCallResult invokeTool(
            ToolExecutionKernel kernel, String attemptId, String toolName, String argumentsJson) {
        long sequence = toolSequences.computeIfAbsent(attemptId, ignored -> new AtomicLong()).incrementAndGet();
        ToolExecutionKernel.ToolInvocationResult result;
        try {
            result = kernel.invoke(attemptId, sequence, toolName, argumentsJson);
        } catch (RuntimeException exception) {
            fenceAttempt(attemptId, AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                    "TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE");
            return McpToolCallHandler.McpToolCallResult.error("TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE");
        }
        if (result.outcome() == ToolExecutionKernel.ToolInvocationResult.Outcome.UNKNOWN
                || result.outcome() == ToolExecutionKernel.ToolInvocationResult.Outcome.BLOCKED_UNCERTAIN) {
            fenceAttempt(attemptId, AiAttemptState.TOOL_OUTCOME_UNKNOWN, result.errorCode());
        }
        return switch (result.outcome()) {
            case EXECUTED, REPLAYED -> McpToolCallHandler.McpToolCallResult.ok(result.responseText());
            case BLOCKED, BLOCKED_UNCERTAIN, UNKNOWN -> McpToolCallHandler.McpToolCallResult.error(result.errorCode());
        };
    }

    private void onAppServerNotification(String method, JsonNode params) {
        if ("account/login/completed".equals(method) && params != null) {
            String loginId = params.path("loginId").asText(null);
            boolean success = params.path("success").asBoolean(false);
            ChatGptSubscriptionLifecycleService currentLifecycle = lifecycle;
            if (loginId != null && currentLifecycle != null) {
                try {
                    loginCompletionExecutor.execute(
                            () -> applyLoginCompletion(currentLifecycle, loginId, success));
                } catch (RejectedExecutionException ignored) {
                    log.warn("ChatGPT login completion was ignored during runtime shutdown");
                }
            }
        }
        for (AppServerEventListener listener : eventListeners) {
            try {
                listener.onNotification(method, params);
            } catch (RuntimeException ignored) {
                // Listener isolation; notification bodies are never logged.
            }
        }
    }

    private void applyLoginCompletion(
            ChatGptSubscriptionLifecycleService currentLifecycle, String loginId, boolean success) {
        try {
            if (success) {
                currentLifecycle.completeLoginByAppServerLoginId(loginId);
            } else {
                currentLifecycle.failLoginByAppServerLoginId(loginId);
            }
        } catch (RuntimeException exception) {
            log.warn("ChatGPT login completion could not be applied: {}",
                    exception.getClass().getSimpleName());
        }
    }

    private synchronized void ensureLedger() {
        if (!ledgerReady) {
            IAiSubscriptionStateRepository repository = stateRepository();
            repository.initialize();
            repository.recoverOrphanedAttemptsAndLeases();
            ledgerReady = true;
        }
    }

    private void fenceCurrentActiveAttempt() {
        Set<String> attemptIds = new LinkedHashSet<>();
        DedicatedMcpBridge bridge = mcpBridge;
        if (bridge != null) {
            bridge.activeAttemptId().ifPresent(attemptIds::add);
        }
        for (AppServerEventListener listener : eventListeners) {
            if (listener instanceof SubscriptionAttemptFenceListener scoped) {
                attemptIds.add(scoped.attemptId());
            }
        }
        for (String attemptId : attemptIds) {
            fenceAttempt(attemptId, null, "WORK_FENCED");
        }
    }

    private void fenceAttempt(String attemptId, AiAttemptState requestedState, String errorCode) {
        IAiSubscriptionStateRepository repository = stateRepository();
        AiAttemptState authoritativeState = requestedState;
        AiAttempt foundAttempt = null;
        try {
            Optional<AiAttempt> found = repository.findAttempt(attemptId);
            if (found.isPresent()) {
                foundAttempt = found.get();
                if (requestedState != AiAttemptState.TOOL_OUTCOME_UNKNOWN) {
                    authoritativeState = foundAttempt.state();
                }
            }
        } catch (RuntimeException ignored) {
            // The in-process fence below must not depend on a second successful ledger read.
        }
        if (foundAttempt != null) {
            CodexAppServerSupervisor current = supervisor;
            if (current != null
                    && foundAttempt.externalThreadId() != null
                    && foundAttempt.externalTurnId() != null) {
                try {
                    current.interruptTurn(foundAttempt.externalThreadId(), foundAttempt.externalTurnId());
                } catch (RuntimeException ignored) {
                    // Durable attempt/lease fencing is authoritative when interrupt acknowledgement is unavailable.
                }
            }
        }
        DedicatedMcpBridge bridge = mcpBridge;
        if (bridge != null && bridge.activeAttemptId().filter(attemptId::equals).isPresent()) {
            bridge.clearActiveAttempt();
        }
        toolSequences.remove(attemptId);
        AiAttemptState terminalState = authoritativeState == null
                || !isTerminal(authoritativeState) ? AiAttemptState.INTERRUPTED : authoritativeState;
        fenceListeners(eventListeners, attemptId, terminalState,
                errorCode == null ? "ATTEMPT_FENCED" : errorCode);
    }

    static void fenceListeners(List<AppServerEventListener> listeners, String attemptId,
                               AiAttemptState state, String errorCode) {
        for (AppServerEventListener listener : listeners) {
            if (listener instanceof SubscriptionAttemptFenceListener scoped
                    && scoped.attemptId().equals(attemptId)) {
                scoped.onAttemptFenced(state, errorCode);
            }
        }
    }

    private static boolean isTerminal(AiAttemptState state) {
        return state == AiAttemptState.COMPLETED
                || state == AiAttemptState.FAILED
                || state == AiAttemptState.INTERRUPTED
                || state == AiAttemptState.OUTCOME_UNKNOWN
                || state == AiAttemptState.TOOL_OUTCOME_UNKNOWN;
    }

    private boolean baseSurfaceEnabled() {
        return Boolean.getBoolean(FEATURE_PROPERTY)
                && ConfigUtils.isCommunity()
                && ConfigUtils.isDesktop()
                && ConfigUtils.isShowGUI()
                && ConfigUtils.isRelease()
                && keyringProbe.isKeyringAvailable();
    }

    private ChatGptSubscriptionLifecycleService requireLifecycle() {
        ChatGptSubscriptionLifecycleService current = lifecycle;
        if (current == null || !capability().enabled()) {
            throw new IllegalStateException("Subscription AI is unavailable");
        }
        return current;
    }

    private CodexAppServerPort requireLifecyclePort() {
        CodexAppServerSupervisor current = supervisor;
        if (current == null || !current.isEnabled()) {
            throw new IllegalStateException("Subscription app-server is unavailable");
        }
        return current;
    }

    private void ensureSelectable(AiModelRef modelRef) {
        AiProviderConnection connection = stateRepository().connection(modelRef.provider());
        if (connection.state() != AiProviderConnectionState.CONNECTED) {
            throw new IllegalArgumentException("Provider is not connected with a current model snapshot");
        }
        Instant cutoff = Instant.now().minus(ChatGptSubscriptionLifecycleService.MODEL_FRESHNESS);
        boolean selectable = models().stream()
                .anyMatch(snapshot -> snapshot.modelRef().equals(modelRef)
                        && snapshot.available()
                        && !snapshot.discoveredAt().isBefore(cutoff));
        if (!selectable) {
            throw new IllegalArgumentException("Model is not in the current available snapshot");
        }
    }

    private static void requireChatGpt(AiProviderEnum provider) {
        if (provider != AiProviderEnum.OPENAI) {
            throw new IllegalArgumentException("Only ChatGPT subscription login is eligible");
        }
    }

    private IAiSubscriptionStateRepository stateRepository() {
        IAiSubscriptionStateRepository current = stateRepository;
        if (current == null) {
            synchronized (this) {
                current = stateRepository;
                if (current == null) {
                    current = H2AiSubscriptionStateRepository.forCommunityProfile();
                    stateRepository = current;
                }
            }
        }
        return current;
    }

    private void stopRuntimeComponents() {
        if (supervisor != null) {
            supervisor.shutdown();
            supervisor = null;
        }
        if (mcpBridge != null) {
            mcpBridge.stop();
            mcpBridge = null;
        }
        lifecycle = null;
        toolSequences.clear();
    }
}
