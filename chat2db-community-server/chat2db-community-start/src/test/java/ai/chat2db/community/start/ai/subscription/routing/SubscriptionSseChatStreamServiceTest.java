package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.domain.api.service.ai.IAiChatHistoryService;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.domain.api.service.sys.IIdentityService;
import ai.chat2db.community.start.ai.subscription.runtime.SubscriptionAiRuntime;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerEventListener;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.start.ai.subscription.routing.mcp.DedicatedMcpBridge;
import ai.chat2db.community.web.api.adapter.ai.AiChatStreamAdapter;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import org.mockito.ArgumentCaptor;

class SubscriptionSseChatStreamServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void terminalDonePayloadCarriesCompleteAnswerForJcefRecovery() {
        var payload = SubscriptionSseChatStreamService.terminalDonePayload(
                "complete answer", "session-1", "message-1", "attempt-1");

        assertEquals("done", payload.get("messageType"));
        assertEquals("complete answer", payload.get("finalAnswer"));
        assertEquals("[DONE]", payload.get("content"));
    }

    @Test
    void reasoningEffortDefaultsToHighAndRejectsUnsupportedChoice() {
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AiModelSnapshot snapshot = new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null,
                List.of("low", "high", "xhigh"), "medium");

        assertEquals("high", SubscriptionSseChatStreamService.resolveReasoningEffort(snapshot, null));
        assertEquals("xhigh", SubscriptionSseChatStreamService.resolveReasoningEffort(snapshot, "xhigh"));
        assertEquals(null, SubscriptionSseChatStreamService.resolveReasoningEffort(snapshot, "ultra"));
    }

    @Test
    void appServerItemAllowlistRejectsNativeToolsAndOtherMcpServers() throws Exception {
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"commandExecution\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"fileChange\"}")));
        // CodeMode/exec nested MCP is disabled.
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"customToolCall\",\"name\":\"exec\","
                        + "\"input\":\"const r = await tools.mcp__chat2db_subscription__list_all_datasources({})\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"customToolCall\",\"name\":\"shell\","
                        + "\"input\":\"ls\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"other\",\"tool\":\"execute_sql\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"other\",\"tool\":\"list_mcp_resources\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"chat2db_subscription\","
                        + "\"tool\":\"shell\"}")));
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"chat2db_subscription\","
                        + "\"tool\":\"execute_sql\"}")));
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"chat2db_subscription\","
                        + "\"tool\":\"list_all_datasources\"}")));
        // Responses FunctionCall with MCP namespace must be allowed (not interruptTurn / cancelled).
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"functionCall\",\"name\":\"list_all_datasources\","
                        + "\"namespace\":\"mcp__chat2db_subscription\",\"arguments\":\"\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"functionCall\",\"name\":\"list_all_datasources\","
                        + "\"namespace\":\"mcp__other\",\"arguments\":\"{}\"}")));
        assertFalse(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"functionCall\",\"name\":\"shell\","
                        + "\"namespace\":\"mcp__chat2db_subscription\"}")));
        // tool_search discovery helper is tolerated under AlwaysDefer.
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"functionCall\",\"name\":\"tool_search\"}")));
        // Direct MCP with any call_id remains allowed; nested exec wrappers are not.
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"chat2db_subscription\","
                        + "\"tool\":\"list_all_datasources\",\"callId\":\"call-abc\"}")));
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"mcpToolCall\",\"server\":\"codex\","
                        + "\"tool\":\"list_mcp_resources\"}")));
        assertTrue(SubscriptionSseChatStreamService.isAllowedAppServerItem(
                MAPPER.readTree("{\"type\":\"agentMessage\"}")));
        assertEquals("APP_SERVER_CODE_MODE_NOT_ALLOWED",
                SubscriptionSseChatStreamService.denyReasonForItem(
                        MAPPER.readTree("{\"type\":\"customToolCall\",\"name\":\"exec\"}")));
        assertEquals("APP_SERVER_CODE_MODE_NOT_ALLOWED",
                SubscriptionSseChatStreamService.denyReasonForItem(
                        MAPPER.readTree("{\"type\":\"customToolCall\",\"name\":\"shell\"}")));
        assertEquals("APP_SERVER_CODE_MODE_NOT_ALLOWED",
                SubscriptionSseChatStreamService.denyReasonForItem(
                        MAPPER.readTree("{\"type\":\"commandExecution\"}")));
        // Non-MCP functionCall still denied.
        assertEquals("APP_SERVER_CODE_MODE_NOT_ALLOWED",
                SubscriptionSseChatStreamService.denyReasonForItem(
                        MAPPER.readTree("{\"type\":\"functionCall\",\"name\":\"web_search\"}")));
    }

    @Test
    void nonExecCustomToolIsRejectedImmediatelyWithoutWaitingForIdle() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AtomicReference<String> attemptId = new AtomicReference<>();
        AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state =
                new AtomicReference<>();

        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    attemptId.set(invocation.getArgument(0));
                    state.set(invocation.getArgument(3));
                    return true;
                });
        doAnswer(invocation -> {
            var expected = invocation.getArgument(1,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            var target = invocation.getArgument(2,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            assertEquals(expected, state.get());
            state.set(target);
            return null;
        }).when(repository).transitionAttempt(anyString(), any(), any(), any(), any());
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        attemptId.get(), "message-1", AiProviderEnum.OPENAI, state.get(),
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(identity.currentUserId()).thenReturn(1L);
        when(bridge.activeAttemptId()).thenAnswer(invocation -> Optional.ofNullable(attemptId.get()));
        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("run shell");

        service.stream(request);
        verify(runtime).addEventListener(listenerCaptor.capture());
        AppServerEventListener listener = listenerCaptor.getValue();
        listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\"partial\"}"));
        // Any customToolCall (including former code_mode exec) is fail-closed.
        listener.onNotification("item/started", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{"
                        + "\"type\":\"customToolCall\",\"name\":\"shell\",\"input\":\"ls\"}}"));

        assertEquals(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.FAILED, state.get());
        verify(appServer).interruptTurn("thread-1", "turn-1");
        verify(repository, atLeastOnce()).releaseProviderLease(eq(AiProviderEnum.OPENAI), eq(attemptId.get()));
    }

    @Test
    void directMcpToolThatNeverReachesKernelFailsFastAsMcpStalled() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AtomicReference<String> attemptId = new AtomicReference<>();
        AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state =
                new AtomicReference<>();

        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    attemptId.set(invocation.getArgument(0));
                    state.set(invocation.getArgument(3));
                    return true;
                });
        doAnswer(invocation -> {
            var expected = invocation.getArgument(1,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            var target = invocation.getArgument(2,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            assertEquals(expected, state.get());
            state.set(target);
            return null;
        }).when(repository).transitionAttempt(anyString(), any(), any(), any(), any());
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        attemptId.get(), "message-1", AiProviderEnum.OPENAI, state.get(),
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(identity.currentUserId()).thenReturn(1L);
        when(bridge.activeAttemptId()).thenAnswer(invocation -> Optional.ofNullable(attemptId.get()));
        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("list datasources");

        Duration previousMcp = SubscriptionSseChatStreamService.mcpStartTimeout();
        Duration previousIdle = SubscriptionSseChatStreamService.idleEventTimeout();
        try {
            SubscriptionSseChatStreamService.overrideMcpStartTimeout(Duration.ofMillis(60));
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(Duration.ofMinutes(5));
            service.stream(request);
            verify(runtime).addEventListener(listenerCaptor.capture());
            AppServerEventListener listener = listenerCaptor.getValue();
            listener.onNotification("item/started", MAPPER.readTree(
                    "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{"
                            + "\"type\":\"mcpToolCall\",\"server\":\"chat2db_subscription\","
                            + "\"tool\":\"list_all_datasources\"}}"));
            assertTrue(awaitState(state,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.FAILED,
                    2_000));
            verify(appServer).interruptTurn("thread-1", "turn-1");
            verify(repository, atLeastOnce()).releaseProviderLease(eq(AiProviderEnum.OPENAI), eq(attemptId.get()));
        } finally {
            SubscriptionSseChatStreamService.overrideMcpStartTimeout(previousMcp);
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(previousIdle);
        }
    }

    @Test
    void outputThenPinnedResourceMetadataCallStillCompletesWithoutToolNotAllowed() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AtomicReference<String> attemptId = new AtomicReference<>();
        AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state =
                new AtomicReference<>();

        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    attemptId.set(invocation.getArgument(0));
                    state.set(invocation.getArgument(3));
                    return true;
                });
        doAnswer(invocation -> {
            var expected = invocation.getArgument(1,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            var target = invocation.getArgument(2,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            assertEquals(expected, state.get());
            state.set(target);
            return null;
        }).when(repository).transitionAttempt(anyString(), any(), any(), any(), any());
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        attemptId.get(), "message-1", AiProviderEnum.OPENAI, state.get(),
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(identity.currentUserId()).thenReturn(1L);
        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("hello");

        service.stream(request);
        verify(runtime).addEventListener(listenerCaptor.capture());
        AppServerEventListener listener = listenerCaptor.getValue();
        listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\"answer\"}"));
        listener.onNotification("item/started", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"item\":{"
                        + "\"type\":\"mcpToolCall\",\"server\":\"codex\","
                        + "\"tool\":\"list_mcp_resources\"}}"));
        listener.onNotification("turn/completed", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\"}}"));

        assertEquals(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.COMPLETED,
                state.get());
        verify(appServer, never()).interruptTurn(anyString(), anyString());
        ArgumentCaptor<ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest>
                historyCaptor = ArgumentCaptor.forClass(
                ai.chat2db.community.domain.api.model.request.ai.AiChatMessageAddRequest.class);
        verify(history, times(2)).addMessage(historyCaptor.capture());
        assertEquals("assistant", historyCaptor.getAllValues().get(1).getRole());
        assertEquals("answer", historyCaptor.getAllValues().get(1).getContent());
    }

    @Test
    void modelRejectionClassifierRecognizesProviderRejectWithoutTreatingGenericFailureAsReject() throws Exception {
        assertTrue(SubscriptionSseChatStreamService.isModelRejection(
                MAPPER.readTree("{\"error\":{\"code\":\"model_not_allowed\"}}")));
        assertTrue(SubscriptionSseChatStreamService.isModelRejection(
                MAPPER.readTree("{\"error\":{\"message\":\"Requested model is unavailable\"}}")));
        assertFalse(SubscriptionSseChatStreamService.isModelRejection(
                MAPPER.readTree("{\"error\":{\"code\":\"rate_limit\",\"message\":\"retry later\"}}")));
    }

    @Test
    void turnFailureClassifiersSeparateAuthRateLimitAndGeneric() throws Exception {
        assertTrue(SubscriptionSseChatStreamService.isAuthFailure(
                MAPPER.readTree("{\"error\":{\"code\":\"unauthorized\",\"message\":\"token expired\"}}")));
        assertTrue(SubscriptionSseChatStreamService.isRateLimitFailure(
                MAPPER.readTree("{\"error\":{\"code\":\"rate_limit\",\"message\":\"retry later\"}}")));
        assertFalse(SubscriptionSseChatStreamService.isAuthFailure(
                MAPPER.readTree("{\"error\":{\"code\":\"rate_limit\",\"message\":\"retry later\"}}")));
        assertFalse(SubscriptionSseChatStreamService.isRateLimitFailure(
                MAPPER.readTree("{\"error\":{\"code\":\"model_not_allowed\"}}")));
    }

    @Test
    void providerModelRejectionRefreshesThenDurablyDisablesRejectedModel() {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-rejected");
        when(runtime.repository()).thenReturn(repository);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime,
                mock(IAiChatHistoryService.class), mock(IIdentityService.class));

        service.rejectModelAndRefresh(modelRef);

        verify(runtime).refreshModels(AiProviderEnum.OPENAI);
        verify(repository).markModelRejected(modelRef, "MODEL_REJECTED");
    }

    @Test
    void legacyApiKeyRequestDelegatesUnchanged() {
        AiChatStreamAdapter apiKeyDelegate = mock(AiChatStreamAdapter.class);
        SseEmitter expected = new SseEmitter();
        ChatRequest request = new ChatRequest();
        request.setInput("select one");
        when(apiKeyDelegate.stream(request)).thenReturn(expected);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                apiKeyDelegate,
                mock(SubscriptionAiRuntime.class),
                mock(IAiChatHistoryService.class),
                mock(IIdentityService.class));

        assertSame(expected, service.stream(request));
        verify(apiKeyDelegate).stream(request);
    }

    @Test
    void staleSubscriptionSnapshotRefreshesBeforeProviderLeaseAcquisition() {
        AiChatStreamAdapter apiKeyDelegate = mock(AiChatStreamAdapter.class);
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AiModelSnapshot stale = new AiModelSnapshot(modelRef, "GPT Test",
                Instant.now().minusSeconds(3600), true, null);
        AiModelSnapshot fresh = new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null);
        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(stale), List.of(fresh));
        when(runtime.refreshModels(AiProviderEnum.OPENAI)).thenReturn(List.of(fresh));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenReturn(false);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                apiKeyDelegate, runtime, mock(IAiChatHistoryService.class), mock(IIdentityService.class));
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setInput("select one");

        service.stream(request);

        verify(runtime).refreshModels(AiProviderEnum.OPENAI);
        verify(repository, times(2)).listCurrentModels(AiProviderEnum.OPENAI);
        verify(repository).tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void mcpBindFailureStillReleasesAcquiredProviderLease() {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 9, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenReturn(true);
        when(runtime.mcpBridge()).thenThrow(new IllegalStateException("bridge stopped"));
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime,
                mock(IAiChatHistoryService.class), mock(IIdentityService.class));
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setInput("select one");

        service.stream(request);

        verify(repository).releaseProviderLease(any(), anyString());
        verify(runtime).removeEventListener(any());
    }

    @Test
    void durableTerminalFenceRejectsLateDeltaCompletionAndHistory() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenReturn(true);
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> java.util.Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        invocation.getArgument(0), "message-1", AiProviderEnum.OPENAI,
                        ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.TOOL_OUTCOME_UNKNOWN,
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(identity.currentUserId()).thenReturn(1L);
        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("select one");

        service.stream(request);
        verify(runtime).addEventListener(listenerCaptor.capture());
        clearInvocations(history, repository);
        AppServerEventListener listener = listenerCaptor.getValue();
        listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\"late\"}"));
        listener.onNotification("turn/completed", MAPPER.readTree(
                "{\"threadId\":\"thread-1\",\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\"}}"));

        verify(repository, never()).appendAttemptOutput(anyString(), anyLong(), any(), anyString(), anyBoolean(), anyBoolean());
        verify(history, never()).addMessage(any());
    }

    @Test
    void idleEventWatchdogKeepsPartialOutputAndReleasesProviderLease() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AtomicReference<String> attemptId = new AtomicReference<>();
        AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state =
                new AtomicReference<>();

        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    attemptId.set(invocation.getArgument(0));
                    state.set(invocation.getArgument(3));
                    return true;
                });
        doAnswer(invocation -> {
            var expected = invocation.getArgument(1,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            var target = invocation.getArgument(2,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            assertEquals(expected, state.get());
            state.set(target);
            return null;
        }).when(repository).transitionAttempt(anyString(), any(), any(), any(), any());
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        attemptId.get(), "message-1", AiProviderEnum.OPENAI, state.get(),
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(identity.currentUserId()).thenReturn(1L);
        when(bridge.activeAttemptId()).thenAnswer(invocation -> Optional.ofNullable(attemptId.get()));

        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("hello");

        Duration previousIdle = SubscriptionSseChatStreamService.idleEventTimeout();
        Duration previousTurn = SubscriptionSseChatStreamService.turnTimeout();
        try {
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(Duration.ofMillis(80));
            SubscriptionSseChatStreamService.overrideTurnTimeout(Duration.ofMinutes(15));
            service.stream(request);
            verify(runtime).addEventListener(listenerCaptor.capture());
            AppServerEventListener listener = listenerCaptor.getValue();
            listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                    "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\"partial answer\"}"));

            assertEquals(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.OUTPUT_VISIBLE,
                    state.get());
            assertTrue(awaitState(state,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.OUTCOME_UNKNOWN,
                    2_000));

            verify(repository).appendAttemptOutput(eq(attemptId.get()), anyLong(),
                    eq(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind.ASSISTANT_TEXT),
                    eq("partial answer"), eq(true), eq(false));
            verify(appServer).interruptTurn("thread-1", "turn-1");
            verify(repository, atLeastOnce()).releaseProviderLease(eq(AiProviderEnum.OPENAI), eq(attemptId.get()));
            verify(bridge).clearActiveAttempt();
            // Partial output must not be promoted to completed assistant history.
            verify(history, times(1)).addMessage(any());
        } finally {
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(previousIdle);
            SubscriptionSseChatStreamService.overrideTurnTimeout(previousTurn);
        }
    }

    @Test
    void matchingProviderEventsResetIdleWatchdogBeforeSilenceTimeout() throws Exception {
        SubscriptionAiRuntime runtime = mock(SubscriptionAiRuntime.class);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        IAiChatHistoryService history = mock(IAiChatHistoryService.class);
        IIdentityService identity = mock(IIdentityService.class);
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        DedicatedMcpBridge bridge = mock(DedicatedMcpBridge.class);
        AiModelRef modelRef = new AiModelRef(AiAccessType.SUBSCRIPTION, AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER, "gpt-test");
        AtomicReference<String> attemptId = new AtomicReference<>();
        AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state =
                new AtomicReference<>();

        when(runtime.capability()).thenReturn(AiSubscriptionCapability.enabledCapability());
        when(runtime.repository()).thenReturn(repository);
        when(runtime.appServer()).thenReturn(appServer);
        when(runtime.mcpBridge()).thenReturn(bridge);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "account", 7, Instant.now(), null));
        when(repository.listCurrentModels(AiProviderEnum.OPENAI)).thenReturn(List.of(
                new AiModelSnapshot(modelRef, "GPT Test", Instant.now(), true, null)));
        when(repository.tryCreateAttemptAndAcquireProviderLease(
                anyString(), anyString(), any(), any(), anyLong())).thenAnswer(invocation -> {
                    attemptId.set(invocation.getArgument(0));
                    state.set(invocation.getArgument(3));
                    return true;
                });
        doAnswer(invocation -> {
            var expected = invocation.getArgument(1,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            var target = invocation.getArgument(2,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.class);
            assertEquals(expected, state.get());
            state.set(target);
            return null;
        }).when(repository).transitionAttempt(anyString(), any(), any(), any(), any());
        when(repository.findAttempt(anyString())).thenAnswer(invocation -> Optional.of(
                new ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt(
                        attemptId.get(), "message-1", AiProviderEnum.OPENAI, state.get(),
                        "thread-1", "turn-1", Instant.now(), Instant.now())));
        when(appServer.startThread("gpt-test")).thenReturn(new AppServerThreadView("thread-1", "session-1"));
        when(appServer.startTurn(anyString(), anyString(), any()))
                .thenReturn(new AppServerTurnView("turn-1", "thread-1", "active"));
        when(identity.currentUserId()).thenReturn(1L);
        when(bridge.activeAttemptId()).thenAnswer(invocation -> Optional.ofNullable(attemptId.get()));

        ArgumentCaptor<AppServerEventListener> listenerCaptor = ArgumentCaptor.forClass(AppServerEventListener.class);
        SubscriptionSseChatStreamService service = new SubscriptionSseChatStreamService(
                mock(AiChatStreamAdapter.class), runtime, history, identity);
        ChatRequest request = new ChatRequest();
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-test");
        request.setMessageId("message-1");
        request.setSessionId("session-1");
        request.setInput("hello");

        Duration previousIdle = SubscriptionSseChatStreamService.idleEventTimeout();
        Duration previousTurn = SubscriptionSseChatStreamService.turnTimeout();
        try {
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(Duration.ofMillis(120));
            SubscriptionSseChatStreamService.overrideTurnTimeout(Duration.ofMinutes(15));
            service.stream(request);
            verify(runtime).addEventListener(listenerCaptor.capture());
            AppServerEventListener listener = listenerCaptor.getValue();

            listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                    "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\"one\"}"));
            Thread.sleep(70);
            assertEquals(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.OUTPUT_VISIBLE,
                    state.get());
            listener.onNotification("item/agentMessage/delta", MAPPER.readTree(
                    "{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\",\"delta\":\" two\"}"));
            Thread.sleep(70);
            // Still alive because the second delta reset the idle generation.
            assertEquals(ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.OUTPUT_VISIBLE,
                    state.get());
            verify(appServer, never()).interruptTurn(anyString(), anyString());

            assertTrue(awaitState(state,
                    ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState.OUTCOME_UNKNOWN,
                    2_000));
            verify(appServer).interruptTurn("thread-1", "turn-1");
            verify(repository, atLeastOnce()).releaseProviderLease(eq(AiProviderEnum.OPENAI), eq(attemptId.get()));
        } finally {
            SubscriptionSseChatStreamService.overrideIdleEventTimeout(previousIdle);
            SubscriptionSseChatStreamService.overrideTurnTimeout(previousTurn);
        }
    }

    private static boolean awaitState(
            AtomicReference<ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState> state,
            ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState expected,
            long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (expected.equals(state.get())) {
                return true;
            }
            Thread.sleep(10);
        }
        return expected.equals(state.get());
    }
}
