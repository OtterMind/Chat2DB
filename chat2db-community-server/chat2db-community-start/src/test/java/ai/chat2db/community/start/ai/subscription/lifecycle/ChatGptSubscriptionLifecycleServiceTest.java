package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSagaState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionDisabledReason;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import ai.chat2db.community.storage.ai.H2AiSubscriptionStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptSubscriptionLifecycleServiceTest {

    private static final String CANARY_TOKEN = "sk-secret-should-never-leak-T3";
    private static final String CANARY_AUTH_URL = "https://chatgpt.com/oauth?token=" + CANARY_TOKEN;

    private H2AiSubscriptionStateRepository repository;
    private FakeCodexAppServerPort appServer;
    private AtomicReference<AiSubscriptionCapability> capability;
    private MutableClock clock;
    private ChatGptSubscriptionLifecycleService service;

    @BeforeEach
    void setUp() {
        String db = "lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        repository = new H2AiSubscriptionStateRepository("jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        repository.initialize();
        appServer = new FakeCodexAppServerPort();
        appServer.setLoginAuthUrl(CANARY_AUTH_URL);
        capability = new AtomicReference<>(AiSubscriptionCapability.enabledCapability());
        clock = new MutableClock(Instant.parse("2026-07-31T04:00:00Z"));
        service = new ChatGptSubscriptionLifecycleService(appServer, repository, capability::get, clock);
    }

    @Test
    void failsClosedWhenCapabilityDisabled() {
        capability.set(AiSubscriptionCapability.disabled(AiSubscriptionDisabledReason.FEATURE_DISABLED));
        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> service.startLogin(LoginType.BROWSER));
        assertEquals(LifecycleErrorCode.FEATURE_DISABLED, ex.errorCode());
    }

    @Test
    void failsClosedWhenAppServerDisabled() {
        appServer.setEnabled(false);
        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> service.startLogin(LoginType.BROWSER));
        assertEquals(LifecycleErrorCode.APP_SERVER_UNAVAILABLE, ex.errorCode());
    }

    @Test
    void browserLoginReturnsAttemptIdWithoutAuthUrlInSafeDto() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);

        assertNotNull(response.attemptId());
        assertEquals(LoginType.BROWSER, response.loginType());
        Map<String, Object> safe = response.toSafeMap();
        assertFalse(safe.containsKey("authUrl"));
        assertFalse(String.valueOf(safe).contains(CANARY_AUTH_URL));
        assertFalse(String.valueOf(safe).contains(CANARY_TOKEN));
        assertEquals(AiProviderConnectionState.CONNECTING,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
        assertEquals("chatgpt", appServer.loginTypeUsed());
    }

    @Test
    void deviceLoginExposesUserCodeButNoTokens() {
        SafeLoginStartResponse response = service.startLogin(LoginType.DEVICE);

        assertEquals(LoginType.DEVICE, response.loginType());
        assertEquals("ABCD-1234", response.userCode());
        assertEquals("https://auth.openai.com/codex/device", response.verificationUrl());
        assertFalse(response.toSafeMap().containsKey("authUrl"));
        assertEquals("chatgptDeviceCode", appServer.loginTypeUsed());
    }

    @Test
    void resolveBrowserTargetAllowlistsHttpsChatgptOnly() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        BrowserTargetResolution ok = service.resolveBrowserTarget(response.attemptId());
        assertTrue(ok.allowed());
        assertEquals(CANARY_AUTH_URL, ok.httpsUrl());

        appServer.setLoginAuthUrl("http://chatgpt.com/oauth");
        // existing attempt still holds previous allowlisted URL
        assertTrue(service.resolveBrowserTarget(response.attemptId()).allowed());

        // Rejected host on a new attempt
        repository.transitionConnection(
                ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER,
                AiProviderConnectionState.CONNECTING,
                AiProviderConnectionState.DISCONNECTED,
                null);
        appServer.setLoginAuthUrl("https://evil.example/oauth");
        SafeLoginStartResponse bad = service.startLogin(LoginType.BROWSER);
        BrowserTargetResolution denied = service.resolveBrowserTarget(bad.attemptId());
        assertFalse(denied.allowed());
        assertEquals(LifecycleErrorCode.BROWSER_TARGET_NOT_ALLOWED, denied.errorCode());
    }

    @Test
    void cancelLoginExpiresAttemptAndRollsBackConnecting() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        service.cancelLogin(response.attemptId());

        assertEquals(1, appServer.cancelCalls());
        assertEquals(AiProviderConnectionState.DISCONNECTED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> service.resolveBrowserTarget(response.attemptId()));
        assertEquals(LifecycleErrorCode.ATTEMPT_NOT_FOUND, ex.errorCode());
    }

    @Test
    void expiredAttemptIsRejected() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        clock.advance(ChatGptSubscriptionLifecycleService.LOGIN_ATTEMPT_TTL.plusSeconds(1));

        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> service.resolveBrowserTarget(response.attemptId()));
        assertEquals(LifecycleErrorCode.ATTEMPT_EXPIRED, ex.errorCode());
    }

    @Test
    void expiredAttemptWithoutRendererIdAllowsRetryAfterTtl() {
        service.startLogin(LoginType.BROWSER);
        assertEquals(AiProviderConnectionState.CONNECTING,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());

        // Renderer lost the attempt id (reload). Advance past TTL then start again —
        // purge must roll CONNECTING back so the second start is not PROVIDER_BUSY.
        clock.advance(ChatGptSubscriptionLifecycleService.LOGIN_ATTEMPT_TTL.plusSeconds(1));
        SafeLoginStartResponse retry = service.startLogin(LoginType.BROWSER);

        assertNotNull(retry.attemptId());
        assertEquals(AiProviderConnectionState.CONNECTING,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
    }

    @Test
    void completeLoginConnectsDiscoversModelsWithoutAutoDefault() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);

        SafeConnectionView view = service.completeLogin(response.attemptId());

        assertEquals(AiProviderConnectionState.CONNECTED, view.state());
        assertEquals("u***@example.com", view.maskedAccount());
        List<SafeModelAvailability> models = service.listRecentlyConfirmedModels();
        assertEquals(1, models.size());
        assertEquals("gpt-5.4", models.get(0).modelRef().modelId());
        assertTrue(models.get(0).recentlyConfirmedAvailable());
        assertTrue(repository.getGlobalDefault().isEmpty());
        assertFalse(String.valueOf(view.toSafeMap()).contains(CANARY_TOKEN));
        assertFalse(String.valueOf(models.get(0).toSafeMap()).contains(CANARY_TOKEN));
    }

    @Test
    void completeLoginDiscoveryFailureKeepsConnectedSemanticsViaDiscoveryFailed() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        appServer.setListModelsFailure(new IllegalStateException("upstream down"));

        SafeConnectionView view = service.completeLogin(response.attemptId());

        assertEquals(AiProviderConnectionState.DISCOVERY_FAILED, view.state());
        assertEquals(LifecycleErrorCode.DISCOVERY_FAILED.name(), view.discoveryErrorCode());
        assertEquals("u***@example.com", view.maskedAccount());
    }

    @Test
    void unauthenticatedCompleteLoginFailsClosed() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(false);

        LifecycleException ex = assertThrows(LifecycleException.class,
                () -> service.completeLogin(response.attemptId()));
        assertEquals(LifecycleErrorCode.LOGIN_NOT_AUTHENTICATED, ex.errorCode());
        assertEquals(AiProviderConnectionState.DISCONNECTED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
    }

    @Test
    void appServerLoginNotificationCanCompleteOrRollbackByOpaqueLoginId() {
        service.startLogin(LoginType.BROWSER);
        service.failLoginByAppServerLoginId("login-1");
        assertEquals(AiProviderConnectionState.DISCONNECTED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());

        service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        SafeConnectionView completed = service.completeLoginByAppServerLoginId("login-1");
        assertEquals(AiProviderConnectionState.CONNECTED, completed.state());
    }

    @Test
    void modelFreshnessMarksStaleAfterFifteenMinutes() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());

        assertTrue(service.listRecentlyConfirmedModels().get(0).recentlyConfirmedAvailable());

        clock.advance(ChatGptSubscriptionLifecycleService.MODEL_FRESHNESS.plusSeconds(1));
        SafeModelAvailability stale = service.listRecentlyConfirmedModels().get(0);
        assertTrue(stale.stale());
        assertFalse(stale.recentlyConfirmedAvailable());
    }

    @Test
    void dynamicDiscoveryExcludesModelsOutsideVersionedTextCompatibilityPolicy() {
        appServer.setModels(List.of(
                new AppServerModelDescriptor("gpt-text", "GPT Text", false, false, List.of("text")),
                new AppServerModelDescriptor("gpt-image-only", "GPT Image", false, false, List.of("image")),
                new AppServerModelDescriptor(
                        "gpt-5.6-luna", "GPT-5.6 Luna", false, false, List.of("text"),
                        List.of(), null, "code_mode_only"),
                new AppServerModelDescriptor(
                        "gpt-5.5", "GPT-5.5", false, false, List.of("text"),
                        List.of(), null, "function")));
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);

        service.completeLogin(response.attemptId());

        assertEquals(Set.of("gpt-text", "gpt-5.5"), service.listRecentlyConfirmedModels().stream()
                .map(model -> model.modelRef().modelId()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void signOutRequiresLogoutThenUnauthenticatedReadBeforeCredentialRemoved() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());

        SafeConnectionView view = service.signOut();

        assertEquals(AiProviderConnectionState.DISCONNECTED, view.state());
        assertEquals(1, appServer.logoutCalls());
        assertTrue(repository.findRecoverableSagas().isEmpty());
        assertTrue(repository.listCurrentModels(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).isEmpty());
        assertTrue(repository.getGlobalDefault().isEmpty());
    }

    @Test
    void signOutRunsActiveWorkFenceBeforeExternalLogout() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());
        AtomicInteger fenceCalls = new AtomicInteger();
        ChatGptSubscriptionLifecycleService withFence = new ChatGptSubscriptionLifecycleService(
                appServer, repository, capability::get, clock, fenceCalls::incrementAndGet);

        withFence.signOut();

        assertEquals(1, fenceCalls.get());
        assertEquals(1, appServer.logoutCalls());
    }

    @Test
    void signOutFailureLeavesFencedRetryableState() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());
        appServer.setReadUnauthenticatedAfterLogout(false);

        LifecycleException ex = assertThrows(LifecycleException.class, service::signOut);
        assertEquals(LifecycleErrorCode.SIGN_OUT_FAILED, ex.errorCode());
        assertEquals(1, appServer.logoutCalls());
        assertEquals(AiProviderConnectionState.DISCONNECT_FAILED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
        assertEquals(1, repository.findRecoverableSagas().size());
        assertEquals(AiProviderSagaState.DISCONNECT_FAILED,
                repository.findRecoverableSagas().get(0).state());

        // Retry after provider actually logs out.
        appServer.setReadUnauthenticatedAfterLogout(true);
        appServer.setAuthenticated(false);
        SafeConnectionView recovered = service.signOut();
        assertEquals(AiProviderConnectionState.DISCONNECTED, recovered.state());
        assertTrue(repository.findRecoverableSagas().isEmpty());
    }

    @Test
    void startupRecoveryResumesIncompleteSignOutSaga() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());

        String sagaId = repository.beginSignOut(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.DISCONNECT_REQUESTED, AiProviderSagaState.WORK_FENCED, null);
        repository.transitionSignOutSaga(sagaId,
                AiProviderSagaState.WORK_FENCED, AiProviderSagaState.LOGOUT_REQUESTED, null);
        assertEquals(AiProviderConnectionState.DISCONNECTING,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());

        appServer.setAuthenticated(true);
        appServer.setReadUnauthenticatedAfterLogout(true);
        service.recoverOnStartup();

        assertEquals(AiProviderConnectionState.DISCONNECTED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
        assertTrue(repository.findRecoverableSagas().isEmpty());
        assertEquals(1, appServer.logoutCalls());
    }

    @Test
    void startupRecoveryRestoresAuthenticatedKeyringAccountAndModels() {
        appServer.setAuthenticated(true);

        service.recoverOnStartup();

        assertEquals(AiProviderConnectionState.CONNECTED,
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).state());
        assertEquals("u***@example.com",
                repository.connection(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).maskedAccount());
        assertEquals(List.of("gpt-5.4"), repository.listCurrentModels(
                        ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).stream()
                .map(model -> model.modelRef().modelId())
                .toList());
        assertTrue(repository.getGlobalDefault().isEmpty());
    }

    @Test
    void startupRecoveryRefreshesReasoningCapabilitiesForAlreadyConnectedAccount() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        appServer.setAuthenticated(true);
        service.completeLogin(response.attemptId());
        assertTrue(repository.listCurrentModels(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER)
                .get(0).supportedReasoningEfforts().isEmpty());

        appServer.setModels(List.of(new AppServerModelDescriptor(
                "gpt-5.4", "GPT-5.4", false, true, List.of("text", "image"),
                List.of("low", "high", "xhigh"), "high")));

        service.recoverOnStartup();

        assertEquals(List.of("low", "high", "xhigh"),
                repository.listCurrentModels(ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER)
                        .get(0).supportedReasoningEfforts());
        assertEquals("high", repository.listCurrentModels(
                ChatGptSubscriptionLifecycleService.CHATGPT_PROVIDER).get(0).defaultReasoningEffort());
    }

    @Test
    void safeMapsNeverContainAuthUrlOrCanaryToken() {
        SafeLoginStartResponse response = service.startLogin(LoginType.BROWSER);
        assertNull(response.toSafeMap().get("authUrl"));
        assertFalse(response.toSafeMap().toString().contains("authUrl"));
        assertFalse(response.toSafeMap().toString().contains(CANARY_TOKEN));
        assertFalse(response.toSafeMap().toString().contains(CANARY_AUTH_URL));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
