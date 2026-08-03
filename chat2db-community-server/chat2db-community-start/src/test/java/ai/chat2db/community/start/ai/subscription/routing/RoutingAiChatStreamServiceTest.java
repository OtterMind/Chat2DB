package ai.chat2db.community.start.ai.subscription.routing;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.service.ai.IAiChatStreamService;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.AppServerException;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerThreadView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerTurnView;
import ai.chat2db.community.web.api.model.request.ai.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RoutingAiChatStreamServiceTest {

    @Test
    void apiKeyRequestsAreDelegatedUnchanged() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt");
        Object sentinel = new Object();

        @SuppressWarnings("unchecked")
        IAiChatStreamService<ChatRequest, Object> delegate = mock(IAiChatStreamService.class);
        when(delegate.stream(request)).thenReturn(sentinel);

        SubscriptionTurnService subscription = mock(SubscriptionTurnService.class);
        RoutingAiChatStreamService<Object> routing = new RoutingAiChatStreamService<>(
                delegate,
                new AiRouteResolver(),
                subscription,
                result -> result);

        assertSame(sentinel, routing.stream(request));
        verify(delegate, times(1)).stream(request);
        verify(subscription, never()).execute(any(), any());
    }

    @Test
    void subscriptionBusyFailsImmediatelyWithoutQueuing() {
        ChatRequest request = subscriptionRequest();
        CodexAppServerPort appServer = enabledAppServer();
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "u***@x.com",
                0L, Instant.now(), null));
        when(repository.tryCreateAttemptAndAcquireProviderLease(anyString(), anyString(),
                eq(AiProviderEnum.OPENAI), eq(AiAttemptState.CREATED), eq(0L)))
                .thenReturn(false);

        SubscriptionTurnService turnService = new SubscriptionTurnService(appServer, repository, true);
        RoutingAiChatStreamService<SubscriptionTurnResult> routing = new RoutingAiChatStreamService<>(
                req -> {
                    throw new AssertionError("must not call api-key delegate");
                },
                new AiRouteResolver(),
                turnService,
                result -> result);

        SubscriptionTurnResult result = routing.stream(request);
        assertTrue(result.providerBusy());
        assertEquals("PROVIDER_BUSY", result.errorCode());
        assertEquals(null, result.attemptId());
        verify(repository, never()).saveMessageModelSnapshot(anyString(), any());
        verify(appServer, never()).startThread(anyString());
        verify(appServer, never()).startTurn(anyString(), anyString());
    }

    @Test
    void subscriptionHappyPathPersistsSnapshotAttemptAndPartialOutputNotContextEligible() {
        ChatRequest request = subscriptionRequest();
        CodexAppServerPort appServer = enabledAppServer();
        when(appServer.startThread(anyString()))
                .thenReturn(new AppServerThreadView("thr_1", "thr_1"));
        when(appServer.startTurn(eq("thr_1"), anyString()))
                .thenReturn(new AppServerTurnView("turn_1", "thr_1", "completed"));

        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "u***@x.com",
                0L, Instant.now(), null));
        when(repository.tryCreateAttemptAndAcquireProviderLease(anyString(), anyString(),
                eq(AiProviderEnum.OPENAI), eq(AiAttemptState.CREATED), eq(0L)))
                .thenReturn(true);
        when(repository.listAttemptOutputs(anyString())).thenReturn(List.of());

        AtomicReference<Boolean> contextEligible = new AtomicReference<>();
        AtomicReference<Boolean> visible = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            visible.set(invocation.getArgument(4));
            contextEligible.set(invocation.getArgument(5));
            return null;
        }).when(repository).appendAttemptOutput(anyString(), anyLong(), any(), anyString(), anyBoolean(), anyBoolean());

        SubscriptionTurnService turnService = new SubscriptionTurnService(appServer, repository, true);
        RoutingAiChatStreamService<SubscriptionTurnResult> routing = new RoutingAiChatStreamService<>(
                req -> {
                    throw new AssertionError("api-key path");
                },
                new AiRouteResolver(),
                turnService,
                result -> result);

        SubscriptionTurnResult result = routing.stream(request);
        assertEquals(AiAttemptState.COMPLETED, result.state());
        assertEquals("thr_1", result.externalThreadId());
        assertEquals("turn_1", result.externalTurnId());
        assertTrue(Boolean.TRUE.equals(visible.get()));
        assertFalse(Boolean.TRUE.equals(contextEligible.get()));
        verify(repository).saveMessageModelSnapshot(eq("msg-1"), any(AiModelRef.class));
        verify(repository).tryCreateAttemptAndAcquireProviderLease(anyString(), eq("msg-1"),
                eq(AiProviderEnum.OPENAI), eq(AiAttemptState.CREATED), eq(0L));
        verify(appServer, times(1)).startTurn(eq("thr_1"), eq("hello"));
    }

    @Test
    void ambiguousTurnStartMarksOutcomeUnknownAndNeverReplays() {
        ChatRequest request = subscriptionRequest();
        CodexAppServerPort appServer = enabledAppServer();
        when(appServer.startThread(anyString()))
                .thenReturn(new AppServerThreadView("thr_1", "thr_1"));
        AtomicInteger startTurnCalls = new AtomicInteger();
        when(appServer.startTurn(eq("thr_1"), anyString())).thenAnswer(invocation -> {
            startTurnCalls.incrementAndGet();
            throw new AppServerException(AppServerDisabledReason.PROCESS_CRASHED, "transport break");
        });
        when(appServer.readThread(eq("thr_1"), anyBoolean()))
                .thenReturn(new AppServerThreadView("thr_1", "thr_1"));

        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);
        when(repository.connection(AiProviderEnum.OPENAI)).thenReturn(new AiProviderConnection(
                AiProviderEnum.OPENAI, AiProviderConnectionState.CONNECTED, "u***@x.com",
                0L, Instant.now(), null));
        when(repository.tryCreateAttemptAndAcquireProviderLease(anyString(), anyString(),
                eq(AiProviderEnum.OPENAI), eq(AiAttemptState.CREATED), eq(0L)))
                .thenReturn(true);
        when(repository.listAttemptOutputs(anyString())).thenReturn(List.of());

        SubscriptionTurnService turnService = new SubscriptionTurnService(appServer, repository, true);
        SubscriptionTurnResult result = turnService.execute(request, subscriptionModel());

        assertEquals(AiAttemptState.OUTCOME_UNKNOWN, result.state());
        assertEquals("TURN_OUTCOME_UNKNOWN", result.errorCode());
        assertEquals(1, startTurnCalls.get());
        verify(appServer, times(1)).startTurn(anyString(), anyString());
        verify(repository).transitionAttempt(anyString(), eq(AiAttemptState.SUBMITTING),
                eq(AiAttemptState.OUTCOME_UNKNOWN), eq("thr_1"), eq(null));
    }

    @Test
    void disabledFeatureRejectsSubscriptionWithoutTouchingAppServer() {
        ChatRequest request = subscriptionRequest();
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        when(appServer.isEnabled()).thenReturn(false);
        IAiSubscriptionStateRepository repository = mock(IAiSubscriptionStateRepository.class);

        SubscriptionTurnService turnService = new SubscriptionTurnService(appServer, repository, false);
        SubscriptionTurnResult result = turnService.execute(request, subscriptionModel());

        assertEquals("SUBSCRIPTION_ROUTE_DISABLED", result.errorCode());
        verify(appServer, never()).start();
        verify(repository, never()).createAttempt(anyString(), anyString(), any(), any());
    }

    private static ChatRequest subscriptionRequest() {
        ChatRequest request = new ChatRequest();
        request.setInput("hello");
        request.setAccessType(AiAccessType.SUBSCRIPTION);
        request.setProvider(AiProviderEnum.OPENAI);
        request.setModel("gpt-5.4");
        request.setMessageId("msg-1");
        request.setSessionId("session-1");
        return request;
    }

    private static AiModelRef subscriptionModel() {
        return new AiModelRef(
                AiAccessType.SUBSCRIPTION,
                AiProviderEnum.OPENAI,
                AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                "gpt-5.4");
    }

    private static CodexAppServerPort enabledAppServer() {
        CodexAppServerPort appServer = mock(CodexAppServerPort.class);
        when(appServer.isEnabled()).thenReturn(true);
        when(appServer.disabledReason()).thenReturn(Optional.empty());
        return appServer;
    }
}
