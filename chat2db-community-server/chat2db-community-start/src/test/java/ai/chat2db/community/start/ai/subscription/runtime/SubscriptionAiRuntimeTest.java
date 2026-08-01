package ai.chat2db.community.start.ai.subscription.runtime;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecutionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartDecision;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.start.ai.subscription.lifecycle.ChatGptSubscriptionLifecycleService;
import ai.chat2db.community.start.ai.subscription.lifecycle.LoginType;
import ai.chat2db.community.start.ai.subscription.routing.mcp.DedicatedMcpBridge;
import ai.chat2db.community.start.ai.subscription.routing.mcp.McpToolCallHandler;
import ai.chat2db.community.start.ai.subscription.routing.tool.ToolExecutionKernel;
import ai.chat2db.community.storage.ai.H2AiSubscriptionStateRepository;
import ai.chat2db.community.web.api.mcp.adapter.AiToolMcpAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class SubscriptionAiRuntimeTest {

    @Test
    void startupFailureCodeContainsOnlyStableStageAndAppServerReason() {
        String canary = "secret-token /Users/example/private-path";

        assertEquals("MANIFEST_LOAD_FAILED", SubscriptionAiRuntime.safeStartupFailureCode(
                SubscriptionAiRuntime.StartupStage.MANIFEST_LOAD,
                new IllegalStateException(canary)));
        assertEquals("APP_SERVER_START_BINARY_CHECKSUM_MISMATCH",
                SubscriptionAiRuntime.safeStartupFailureCode(
                        SubscriptionAiRuntime.StartupStage.APP_SERVER_START,
                        new ai.chat2db.community.start.ai.subscription.appserver.AppServerException(
                                ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason
                                        .BINARY_CHECKSUM_MISMATCH,
                                canary)));
        assertFalse(SubscriptionAiRuntime.safeStartupFailureCode(
                SubscriptionAiRuntime.StartupStage.MANIFEST_LOAD,
                new IllegalStateException(canary)).contains(canary));
    }

    @Test
    void staleSnapshotsAreProjectedUnavailableAtListTime() {
        Instant now = Instant.parse("2026-07-31T12:00:00Z");
        AiModelRef ref = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        List<AiModelSnapshot> projected = SubscriptionAiRuntime.projectModelFreshness(List.of(
                new AiModelSnapshot(ref, "stale", now.minusSeconds(901), true, null),
                new AiModelSnapshot(ref, "fresh", now.minusSeconds(899), true, null)), now);

        assertFalse(projected.get(0).available());
        assertEquals("MODEL_SNAPSHOT_STALE", projected.get(0).disabledReason());
        assertTrue(projected.get(1).available());
    }

    @Test
    void reasoningCapabilityRefreshRetriesAfterAppServerStartupRace() throws Exception {
        Instant discoveredAt = Instant.now();
        AiModelRef ref = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AiModelSnapshot legacySnapshot = new AiModelSnapshot(
                ref, "GPT Test", discoveredAt, true, null);
        AiModelSnapshot refreshedSnapshot = new AiModelSnapshot(
                ref, "GPT Test", discoveredAt, true, null,
                List.of("low", "high", "xhigh"), "high");

        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED,
                "m***@example.com", 0, discoveredAt, null));
        AtomicInteger refreshCalls = new AtomicInteger();
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenAnswer(invocation ->
                refreshCalls.get() >= 2 ? List.of(refreshedSnapshot) : List.of(legacySnapshot));
        ChatGptSubscriptionLifecycleService lifecycle = mock(ChatGptSubscriptionLifecycleService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (refreshCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("app-server not ready");
            }
            return List.of();
        }).when(lifecycle).refreshModels();

        SubscriptionAiRuntime runtime = new SubscriptionAiRuntime(mock(AiToolMcpAdapter.class));
        setField(runtime, "stateRepository", repository);
        setField(runtime, "ledgerReady", true);
        setField(runtime, "lifecycle", lifecycle);

        assertTrue(runtime.refreshMissingReasoningCapabilities(repository, List.of(legacySnapshot))
                .get(0).supportedReasoningEfforts().isEmpty());
        assertEquals(List.of("low", "high", "xhigh"),
                runtime.refreshMissingReasoningCapabilities(repository, List.of(legacySnapshot))
                        .get(0).supportedReasoningEfforts());
        verify(lifecycle, times(2)).refreshModels();
    }

    @Test
    void attemptFenceIsDeliveredOnlyToTheOwningStreamContext() {
        AtomicReference<String> fenced = new AtomicReference<>();
        SubscriptionAttemptFenceListener owner = new SubscriptionAttemptFenceListener() {
            @Override
            public String attemptId() {
                return "attempt-owner";
            }

            @Override
            public void onAttemptFenced(AiAttemptState state, String errorCode) {
                fenced.set(state.name() + ":" + errorCode);
            }

            @Override
            public void onNotification(String method, com.fasterxml.jackson.databind.JsonNode params) {
            }
        };
        AppServerEventListener other = (method, params) -> {
            throw new AssertionError("non-owner listener must not be fenced");
        };

        SubscriptionAiRuntime.fenceListeners(
                List.of(owner, other), "attempt-owner", AiAttemptState.INTERRUPTED, "WORK_FENCED");

        assertEquals("INTERRUPTED:WORK_FENCED", fenced.get());
    }

    @Test
    void loginCompletionNotificationDoesNotBlockTheJsonRpcReaderThread() throws Exception {
        H2AiSubscriptionStateRepository repository = new H2AiSubscriptionStateRepository(
                "jdbc:h2:mem:runtime_login_notification_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        repository.initialize();
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        when(appServer.isEnabled()).thenReturn(true);
        when(appServer.startChatGptLogin("chatgpt")).thenReturn(new AppServerLoginStartResult(
                "chatgpt", "login-runtime", "https://chatgpt.com/oauth?state=test", null, null));
        when(appServer.listModels(false)).thenReturn(List.of(
                new AppServerModelDescriptor("gpt-test", "GPT Test", false, true, List.of("text"))));

        CountDownLatch accountReadStarted = new CountDownLatch(1);
        CountDownLatch releaseAccountRead = new CountDownLatch(1);
        AtomicReference<String> completionThread = new AtomicReference<>();
        when(appServer.readAccount(false)).thenAnswer(invocation -> {
            completionThread.set(Thread.currentThread().getName());
            accountReadStarted.countDown();
            assertTrue(releaseAccountRead.await(2, TimeUnit.SECONDS));
            return new AppServerAccountView(true, "chatgpt", "u***@example.com", "plus");
        });

        ChatGptSubscriptionLifecycleService lifecycle = new ChatGptSubscriptionLifecycleService(
                appServer, repository, AiSubscriptionCapability::enabledCapability);
        lifecycle.startLogin(LoginType.BROWSER);
        SubscriptionAiRuntime runtime = new SubscriptionAiRuntime(mock(AiToolMcpAdapter.class));
        setField(runtime, "lifecycle", lifecycle);

        ExecutorService simulatedReader = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "simulated-jsonrpc-reader"));
        Future<?> callback = simulatedReader.submit(() -> {
            try {
                invokeNotification(runtime, "account/login/completed",
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                                .put("loginId", "login-runtime")
                                .put("success", true));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        try {
            assertTrue(accountReadStarted.await(1, TimeUnit.SECONDS));
            callback.get(250, TimeUnit.MILLISECONDS);
            assertFalse("simulated-jsonrpc-reader".equals(completionThread.get()));
        } finally {
            releaseAccountRead.countDown();
            simulatedReader.shutdownNow();
            runtime.shutdown();
        }
    }

    @Test
    void doubleLedgerFailureFencesAndRestartsAsToolOutcomeUnknown(@TempDir Path tempDir) throws Exception {
        String attemptId = "attempt-double-ledger";
        String jdbcUrl = "jdbc:h2:file:" + tempDir.resolve("runtime-double-ledger").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE";
        H2AiSubscriptionStateRepository durableRepository = new H2AiSubscriptionStateRepository(jdbcUrl);
        durableRepository.initialize();
        prepareActiveAttempt(durableRepository, attemptId);

        IAiSubscriptionStateRepository faultingRepository = spy(durableRepository);
        doThrow(new IllegalStateException("completion ledger unavailable"))
                .when(faultingRepository).completeToolExecution(eq(attemptId), eq(1L), anyString());
        doThrow(new IllegalStateException("unknown ledger unavailable"))
                .when(faultingRepository).markToolOutcomeUnknownAndReleaseLease(attemptId);

        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        when(bridge.activeAttemptId()).thenReturn(Optional.of(attemptId));
        SubscriptionAiRuntime runtime = new SubscriptionAiRuntime(mock(AiToolMcpAdapter.class));
        setField(runtime, "stateRepository", faultingRepository);
        setField(runtime, "ledgerReady", true);
        setField(runtime, "mcpBridge", bridge);

        AtomicReference<AiAttemptState> fencedState = new AtomicReference<>();
        runtime.addEventListener(new SubscriptionAttemptFenceListener() {
            @Override
            public String attemptId() {
                return attemptId;
            }

            @Override
            public void onAttemptFenced(AiAttemptState state, String errorCode) {
                fencedState.set(state);
                AiAttemptState current = durableRepository.findAttempt(attemptId).orElseThrow().state();
                if (current.canTransitionTo(state)) {
                    durableRepository.transitionAttempt(attemptId, current, state, null, null);
                }
            }

            @Override
            public void onNotification(String method, com.fasterxml.jackson.databind.JsonNode params) {
            }
        });
        ToolExecutionKernel kernel = new ToolExecutionKernel(
                faultingRepository, (tool, arguments) -> "effect-completed");

        McpToolCallHandler.McpToolCallResult result = invokeTool(
                runtime, kernel, attemptId, "execute_sql", "UPDATE t SET c = 1");

        assertFalse(result.success());
        assertEquals("TOOL_OUTCOME_UNKNOWN_LEDGER_UNAVAILABLE", result.errorCode());
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN, fencedState.get());
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                durableRepository.findAttempt(attemptId).orElseThrow().state());
        verify(bridge).clearActiveAttempt();

        H2AiSubscriptionStateRepository afterRestart = new H2AiSubscriptionStateRepository(jdbcUrl);
        afterRestart.initialize();
        afterRestart.recoverOrphanedAttemptsAndLeases();
        assertEquals(AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                afterRestart.findAttempt(attemptId).orElseThrow().state());
        assertEquals(AiToolExecutionState.OUTCOME_UNKNOWN,
                afterRestart.findToolExecution(attemptId, 1).orElseThrow().state());
        assertEquals(AiToolStartDecision.BLOCKED_UNCERTAIN,
                afterRestart.beginToolExecution(
                        attemptId, 2, "execute_sql", "second-args", "second-effect").decision());
        assertTrue(afterRestart.listAttemptOutputs(attemptId).isEmpty());
    }

    private static void prepareActiveAttempt(H2AiSubscriptionStateRepository repository, String attemptId) {
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.DISCONNECTED, AiProviderConnectionState.CONNECTING, null);
        repository.transitionConnection(AiProviderEnum.OPENAI,
                AiProviderConnectionState.CONNECTING, AiProviderConnectionState.CONNECTED, "m***@example.com");
        repository.createAttempt(attemptId, "message-double-ledger", AiProviderEnum.OPENAI,
                AiAttemptState.CREATED);
        assertTrue(repository.acquireProviderLease(AiProviderEnum.OPENAI, attemptId, 0));
        repository.transitionAttempt(attemptId, AiAttemptState.CREATED, AiAttemptState.SUBMITTING, null, null);
        repository.transitionAttempt(attemptId, AiAttemptState.SUBMITTING, AiAttemptState.ACTIVE,
                "thread-double-ledger", "turn-double-ledger");
    }

    private static McpToolCallHandler.McpToolCallResult invokeTool(
            SubscriptionAiRuntime runtime,
            ToolExecutionKernel kernel,
            String attemptId,
            String toolName,
            String argumentsJson) throws Exception {
        Method method = SubscriptionAiRuntime.class.getDeclaredMethod(
                "invokeTool", ToolExecutionKernel.class, String.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return (McpToolCallHandler.McpToolCallResult) method.invoke(
                    runtime, kernel, attemptId, toolName, argumentsJson);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static void invokeNotification(
            SubscriptionAiRuntime runtime, String methodName,
            com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        Method method = SubscriptionAiRuntime.class.getDeclaredMethod(
                "onAppServerNotification", String.class, com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        try {
            method.invoke(runtime, methodName, params);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
