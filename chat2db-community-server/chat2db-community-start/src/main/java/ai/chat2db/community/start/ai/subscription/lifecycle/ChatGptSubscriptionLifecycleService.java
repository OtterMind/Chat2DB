package ai.chat2db.community.start.ai.subscription.lifecycle;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAccessType;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSaga;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSagaState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiRouteKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSubscriptionCapability;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.start.ai.subscription.appserver.CodexAppServerPort;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerAccountView;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerLoginStartResult;
import ai.chat2db.community.start.ai.subscription.appserver.dto.AppServerModelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ChatGPT subscription login, discovery, preference-aware model availability, and crash-recoverable sign-out.
 *
 * <p>Browser auth URLs stay in memory only and are resolved by attempt id for allowlisted open-browser.
 * Safe DTOs and logs never include tokens or browser auth URLs.
 */
public final class ChatGptSubscriptionLifecycleService {

    public static final AiProviderEnum CHATGPT_PROVIDER = AiProviderEnum.OPENAI;
    public static final Duration MODEL_FRESHNESS = Duration.ofMinutes(15);
    public static final Duration LOGIN_ATTEMPT_TTL = Duration.ofMinutes(10);

    private static final Logger log = LoggerFactory.getLogger(ChatGptSubscriptionLifecycleService.class);

    private final CodexAppServerPort appServer;
    private final IAiSubscriptionStateRepository stateRepository;
    private final Supplier<AiSubscriptionCapability> capabilitySupplier;
    private final Clock clock;
    private final Runnable workFenceHook;
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    public ChatGptSubscriptionLifecycleService(
            CodexAppServerPort appServer,
            IAiSubscriptionStateRepository stateRepository,
            Supplier<AiSubscriptionCapability> capabilitySupplier) {
        this(appServer, stateRepository, capabilitySupplier, Clock.systemUTC(), () -> { });
    }

    public ChatGptSubscriptionLifecycleService(
            CodexAppServerPort appServer,
            IAiSubscriptionStateRepository stateRepository,
            Supplier<AiSubscriptionCapability> capabilitySupplier,
            Clock clock) {
        this(appServer, stateRepository, capabilitySupplier, clock, () -> { });
    }

    public ChatGptSubscriptionLifecycleService(
            CodexAppServerPort appServer,
            IAiSubscriptionStateRepository stateRepository,
            Supplier<AiSubscriptionCapability> capabilitySupplier,
            Clock clock,
            Runnable workFenceHook) {
        this.appServer = Objects.requireNonNull(appServer, "appServer");
        this.stateRepository = Objects.requireNonNull(stateRepository, "stateRepository");
        this.capabilitySupplier = Objects.requireNonNull(capabilitySupplier, "capabilitySupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.workFenceHook = Objects.requireNonNull(workFenceHook, "workFenceHook");
    }

    public AiSubscriptionCapability capability() {
        return capabilitySupplier.get();
    }

    public SafeConnectionView connectionView() {
        requireEnabled();
        AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
        return new SafeConnectionView(
                connection.state(),
                connection.maskedAccount(),
                connection.discoveryErrorCode(),
                connection.fenceGeneration());
    }

    /**
     * Starts browser or device login. Safe response never includes browser authUrl.
     */
    public SafeLoginStartResponse startLogin(LoginType loginType) {
        requireEnabled();
        Objects.requireNonNull(loginType, "loginType");
        purgeExpiredLoginAttempts();

        AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
        if (connection.state() != AiProviderConnectionState.DISCONNECTED
                && connection.state() != AiProviderConnectionState.DISABLED) {
            // Allow re-login from DISCOVERY_FAILED / CONNECTED only via sign-out first for honesty.
            if (connection.state() == AiProviderConnectionState.CONNECTING) {
                throw new LifecycleException(LifecycleErrorCode.PROVIDER_BUSY);
            }
            if (connection.state() == AiProviderConnectionState.DISCONNECTING
                    || connection.state() == AiProviderConnectionState.DISCONNECT_FAILED) {
                throw new LifecycleException(LifecycleErrorCode.PROVIDER_BUSY);
            }
            if (connection.state() == AiProviderConnectionState.CONNECTED
                    || connection.state() == AiProviderConnectionState.DISCOVERY_FAILED) {
                throw new LifecycleException(LifecycleErrorCode.ILLEGAL_STATE);
            }
        }
        if (connection.state() == AiProviderConnectionState.DISABLED) {
            throw new LifecycleException(LifecycleErrorCode.FEATURE_DISABLED);
        }

        stateRepository.transitionConnection(
                CHATGPT_PROVIDER,
                AiProviderConnectionState.DISCONNECTED,
                AiProviderConnectionState.CONNECTING,
                null);

        AppServerLoginStartResult startResult;
        try {
            startResult = appServer.startChatGptLogin(loginType.appServerType());
        } catch (RuntimeException exception) {
            rollbackConnectingQuietly();
            log.warn("chatgpt login start failed type={}", loginType.name());
            throw new LifecycleException(LifecycleErrorCode.APP_SERVER_UNAVAILABLE);
        }

        String attemptId = UUID.randomUUID().toString();
        long expiresAt = clock.millis() + LOGIN_ATTEMPT_TTL.toMillis();
        String browserAuthUrl = loginType == LoginType.BROWSER ? startResult.authUrl() : null;
        LoginAttempt attempt = new LoginAttempt(
                attemptId,
                loginType,
                startResult.loginId(),
                browserAuthUrl,
                startResult.verificationUrl(),
                startResult.userCode(),
                expiresAt);
        loginAttempts.put(attemptId, attempt);

        log.info("chatgpt login attempt started type={} attempt={}",
                loginType.name(), safeId(attemptId));

        return new SafeLoginStartResponse(
                attemptId,
                loginType,
                expiresAt,
                startResult.userCode(),
                startResult.verificationUrl());
    }

    /**
     * Resolves the allowlisted browser URL for an attempt id. Used only by the open-browser path.
     */
    public BrowserTargetResolution resolveBrowserTarget(String attemptId) {
        requireEnabled();
        LoginAttempt attempt = requireLiveAttempt(attemptId);
        if (attempt.loginType() != LoginType.BROWSER) {
            return BrowserTargetResolution.denied(LifecycleErrorCode.INVALID_ATTEMPT);
        }
        String authUrl = attempt.browserAuthUrl();
        if (!BrowserTargetAllowlist.isAllowed(authUrl)) {
            log.warn("chatgpt browser target rejected attempt={}", safeId(attemptId));
            return BrowserTargetResolution.denied(LifecycleErrorCode.BROWSER_TARGET_NOT_ALLOWED);
        }
        return BrowserTargetResolution.allowed(authUrl);
    }

    public void cancelLogin(String attemptId) {
        requireEnabled();
        LoginAttempt attempt = loginAttempts.get(attemptId);
        if (attempt == null) {
            throw new LifecycleException(LifecycleErrorCode.ATTEMPT_NOT_FOUND);
        }
        attempt.markCancelled();
        try {
            if (attempt.appServerLoginId() != null) {
                appServer.cancelLogin(attempt.appServerLoginId());
            }
        } catch (RuntimeException exception) {
            log.warn("chatgpt login cancel app-server call failed attempt={}", safeId(attemptId));
        }
        loginAttempts.remove(attemptId);
        rollbackConnectingQuietly();
    }

    /**
     * Completes login after app-server has authenticated the user:
     * account/read → CONNECTED → model/list (or markDiscoveryFailed).
     * Never auto-selects a global default model.
     */
    public SafeConnectionView completeLogin(String attemptId) {
        requireEnabled();
        LoginAttempt attempt = requireLiveAttempt(attemptId);

        AppServerAccountView account;
        try {
            account = appServer.readAccount(false);
        } catch (RuntimeException exception) {
            log.warn("chatgpt account/read failed after login attempt={}", safeId(attemptId));
            failLoginToDisconnected(attemptId);
            throw new LifecycleException(LifecycleErrorCode.LOGIN_NOT_AUTHENTICATED);
        }

        if (!account.authenticated()) {
            failLoginToDisconnected(attemptId);
            throw new LifecycleException(LifecycleErrorCode.LOGIN_NOT_AUTHENTICATED);
        }

        String masked = maskAccount(account.maskedEmail());
        stateRepository.transitionConnection(
                CHATGPT_PROVIDER,
                AiProviderConnectionState.CONNECTING,
                AiProviderConnectionState.CONNECTED,
                masked);

        try {
            discoverAndStoreModels();
        } catch (RuntimeException exception) {
            log.warn("chatgpt model discovery failed after login attempt={}", safeId(attemptId));
            stateRepository.markDiscoveryFailed(CHATGPT_PROVIDER, LifecycleErrorCode.DISCOVERY_FAILED.name());
        }

        attempt.markCompleted();
        loginAttempts.remove(attemptId);
        return connectionView();
    }

    /** Completes the matching renderer-safe attempt from an app-server login notification. */
    public SafeConnectionView completeLoginByAppServerLoginId(String appServerLoginId) {
        if (appServerLoginId == null || appServerLoginId.isBlank()) {
            throw new LifecycleException(LifecycleErrorCode.INVALID_ATTEMPT);
        }
        String attemptId = loginAttempts.entrySet().stream()
                .filter(entry -> appServerLoginId.equals(entry.getValue().appServerLoginId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new LifecycleException(LifecycleErrorCode.ATTEMPT_NOT_FOUND));
        return completeLogin(attemptId);
    }

    /** Rolls back the renderer-safe attempt after a failed app-server login notification. */
    public void failLoginByAppServerLoginId(String appServerLoginId) {
        if (appServerLoginId == null || appServerLoginId.isBlank()) {
            return;
        }
        loginAttempts.entrySet().stream()
                .filter(entry -> appServerLoginId.equals(entry.getValue().appServerLoginId()))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(this::failLoginToDisconnected);
    }

    /**
     * Manual or request-time model refresh. Does not set a global default.
     */
    public List<SafeModelAvailability> refreshModels() {
        requireEnabled();
        AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
        if (connection.state() != AiProviderConnectionState.CONNECTED
                && connection.state() != AiProviderConnectionState.DISCOVERY_FAILED) {
            throw new LifecycleException(LifecycleErrorCode.ILLEGAL_STATE);
        }
        try {
            discoverAndStoreModels();
        } catch (RuntimeException exception) {
            log.warn("chatgpt model refresh failed");
            stateRepository.markDiscoveryFailed(CHATGPT_PROVIDER, LifecycleErrorCode.DISCOVERY_FAILED.name());
            throw new LifecycleException(LifecycleErrorCode.DISCOVERY_FAILED);
        }
        return listRecentlyConfirmedModels();
    }

    /**
     * Returns recently confirmed available models within the freshness window.
     * Stale snapshots are reported as not recently confirmed (never invented entitlement).
     */
    public List<SafeModelAvailability> listRecentlyConfirmedModels() {
        requireEnabled();
        Instant now = clock.instant();
        List<SafeModelAvailability> result = new ArrayList<>();
        for (AiModelSnapshot snapshot : stateRepository.listCurrentModels(CHATGPT_PROVIDER)) {
            boolean stale = Duration.between(snapshot.discoveredAt(), now).compareTo(MODEL_FRESHNESS) > 0;
            boolean recentlyConfirmed = snapshot.available() && !stale;
            result.add(new SafeModelAvailability(
                    snapshot.modelRef(),
                    snapshot.displayName(),
                    snapshot.discoveredAt(),
                    recentlyConfirmed,
                    stale));
        }
        return result;
    }

    /**
     * Crash-recoverable sign-out saga.
     * Credential removal requires account/logout then account/read unauthenticated.
     */
    public SafeConnectionView signOut() {
        requireEnabled();
        AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
        AiProviderConnectionState state = connection.state();
        if (state != AiProviderConnectionState.CONNECTED
                && state != AiProviderConnectionState.DISCOVERY_FAILED
                && state != AiProviderConnectionState.DISCONNECTING
                && state != AiProviderConnectionState.DISCONNECT_FAILED) {
            throw new LifecycleException(LifecycleErrorCode.ILLEGAL_STATE);
        }

        String sagaId;
        if (state == AiProviderConnectionState.DISCONNECTING
                || state == AiProviderConnectionState.DISCONNECT_FAILED) {
            // Retry incomplete fenced sign-out without opening a parallel saga.
            sagaId = findOpenSignOutSagaId()
                    .orElseThrow(() -> new LifecycleException(LifecycleErrorCode.SIGN_OUT_FAILED));
        } else {
            sagaId = stateRepository.beginSignOut(CHATGPT_PROVIDER);
        }
        runSignOutSaga(sagaId);
        return connectionView();
    }

    /**
     * Resumes incomplete sign-out sagas after process restart.
     */
    public void recoverOnStartup() {
        // Recovery is allowed even when capability is temporarily degraded so fenced state can progress.
        for (AiProviderSaga saga : stateRepository.findRecoverableSagas()) {
            if (saga.provider() != CHATGPT_PROVIDER) {
                continue;
            }
            try {
                runSignOutSaga(saga.sagaId());
            } catch (RuntimeException exception) {
                log.warn("chatgpt sign-out recovery failed saga={}", safeId(saga.sagaId()));
            }
        }
        purgeExpiredLoginAttempts();
        AiProviderConnectionState stateBeforeAccountReconciliation =
                stateRepository.connection(CHATGPT_PROVIDER).state();
        reconcileAuthenticatedAccountOnStartup();
        if (stateBeforeAccountReconciliation == AiProviderConnectionState.CONNECTED
                || stateBeforeAccountReconciliation == AiProviderConnectionState.DISCOVERY_FAILED) {
            try {
                discoverAndStoreModels();
            } catch (RuntimeException exception) {
                log.warn("chatgpt model discovery failed for persisted account during startup");
                stateRepository.markDiscoveryFailed(
                        CHATGPT_PROVIDER, LifecycleErrorCode.DISCOVERY_FAILED.name());
            }
        }
    }

    /**
     * Repairs a local disconnected/orphan-connecting projection when the pinned app-server has
     * already persisted an authenticated account in the OS Keyring.
     */
    private void reconcileAuthenticatedAccountOnStartup() {
        if (!appServer.isEnabled()) {
            return;
        }
        AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
        if (connection.state() != AiProviderConnectionState.DISCONNECTED
                && connection.state() != AiProviderConnectionState.CONNECTING) {
            return;
        }

        AppServerAccountView account;
        try {
            account = appServer.readAccount(false);
        } catch (RuntimeException exception) {
            rollbackConnectingQuietly();
            log.warn("chatgpt startup account reconciliation unavailable");
            return;
        }
        if (!account.authenticated()) {
            rollbackConnectingQuietly();
            return;
        }

        try {
            if (connection.state() == AiProviderConnectionState.DISCONNECTED) {
                stateRepository.transitionConnection(
                        CHATGPT_PROVIDER,
                        AiProviderConnectionState.DISCONNECTED,
                        AiProviderConnectionState.CONNECTING,
                        null);
            }
            stateRepository.transitionConnection(
                    CHATGPT_PROVIDER,
                    AiProviderConnectionState.CONNECTING,
                    AiProviderConnectionState.CONNECTED,
                    maskAccount(account.maskedEmail()));
            log.info("chatgpt startup account reconciliation restored authenticated state");
            try {
                discoverAndStoreModels();
            } catch (RuntimeException exception) {
                log.warn("chatgpt model discovery failed during startup reconciliation");
                stateRepository.markDiscoveryFailed(
                        CHATGPT_PROVIDER, LifecycleErrorCode.DISCOVERY_FAILED.name());
            }
        } catch (RuntimeException exception) {
            rollbackConnectingQuietly();
            log.warn("chatgpt startup account reconciliation could not update local state");
        }
    }

    private void runSignOutSaga(String sagaId) {
        AiProviderSaga current = findSaga(sagaId);
        if (current == null) {
            throw new LifecycleException(LifecycleErrorCode.SIGN_OUT_FAILED);
        }

        if (current.state() == AiProviderSagaState.DISCONNECT_REQUESTED) {
            stateRepository.transitionSignOutSaga(
                    sagaId, AiProviderSagaState.DISCONNECT_REQUESTED, AiProviderSagaState.WORK_FENCED, null);
            current = findSaga(sagaId);
        }
        if (current != null && current.state() == AiProviderSagaState.WORK_FENCED) {
            workFenceHook.run();
            stateRepository.transitionSignOutSaga(
                    sagaId, AiProviderSagaState.WORK_FENCED, AiProviderSagaState.LOGOUT_REQUESTED, null);
            current = findSaga(sagaId);
        }
        if (current != null && (current.state() == AiProviderSagaState.LOGOUT_REQUESTED
                || current.state() == AiProviderSagaState.DISCONNECT_FAILED)) {
            if (current.state() == AiProviderSagaState.DISCONNECT_FAILED) {
                stateRepository.transitionSignOutSaga(
                        sagaId, AiProviderSagaState.DISCONNECT_FAILED, AiProviderSagaState.LOGOUT_REQUESTED, null);
            }
            boolean credentialRemoved = performExternalLogoutAndProveUnauthenticated();
            if (!credentialRemoved) {
                stateRepository.transitionSignOutSaga(
                        sagaId,
                        AiProviderSagaState.LOGOUT_REQUESTED,
                        AiProviderSagaState.DISCONNECT_FAILED,
                        LifecycleErrorCode.SIGN_OUT_FAILED.name());
                throw new LifecycleException(LifecycleErrorCode.SIGN_OUT_FAILED);
            }
            stateRepository.transitionSignOutSaga(
                    sagaId, AiProviderSagaState.LOGOUT_REQUESTED, AiProviderSagaState.CREDENTIAL_REMOVED, null);
            current = findSaga(sagaId);
        }
        if (current != null && current.state() == AiProviderSagaState.CREDENTIAL_REMOVED) {
            stateRepository.transitionSignOutSaga(
                    sagaId, AiProviderSagaState.CREDENTIAL_REMOVED, AiProviderSagaState.LOCAL_CLEANUP, null);
            current = findSaga(sagaId);
        }
        if (current != null && current.state() == AiProviderSagaState.LOCAL_CLEANUP) {
            stateRepository.transitionSignOutSaga(
                    sagaId, AiProviderSagaState.LOCAL_CLEANUP, AiProviderSagaState.DISCONNECTED, null);
        }
    }

    private boolean performExternalLogoutAndProveUnauthenticated() {
        try {
            appServer.logout();
        } catch (RuntimeException exception) {
            log.warn("chatgpt account/logout failed");
            return false;
        }
        try {
            AppServerAccountView account = appServer.readAccount(false);
            return !account.authenticated();
        } catch (RuntimeException exception) {
            log.warn("chatgpt account/read after logout failed");
            return false;
        }
    }

    private void discoverAndStoreModels() {
        List<AppServerModelDescriptor> remote = appServer.listModels(false);
        Instant discoveredAt = clock.instant();
        List<AiModelSnapshot> snapshots = new ArrayList<>();
        for (AppServerModelDescriptor descriptor : remote) {
            if (!ChatGptModelCompatibility.isCompatible(descriptor)) {
                continue;
            }
            // Do not treat isDefault as Chat2DB authorization or preference selection.
            AiModelRef modelRef = new AiModelRef(
                    AiAccessType.SUBSCRIPTION,
                    CHATGPT_PROVIDER,
                    AiRouteKind.CHATGPT_CODEX_APP_SERVER,
                    descriptor.id());
            String displayName = descriptor.displayName() == null || descriptor.displayName().isBlank()
                    ? descriptor.id()
                    : descriptor.displayName();
            snapshots.add(new AiModelSnapshot(modelRef, displayName, discoveredAt, true, null,
                    descriptor.supportedReasoningEfforts(), descriptor.defaultReasoningEffort()));
        }
        // Never auto-set global default — preferences require explicit user confirmation (T6).
        stateRepository.replaceModelSnapshot(CHATGPT_PROVIDER, discoveredAt, snapshots);
    }

    private LoginAttempt requireLiveAttempt(String attemptId) {
        if (attemptId == null || attemptId.isBlank()) {
            throw new LifecycleException(LifecycleErrorCode.ATTEMPT_NOT_FOUND);
        }
        LoginAttempt attempt = loginAttempts.get(attemptId);
        if (attempt == null) {
            throw new LifecycleException(LifecycleErrorCode.ATTEMPT_NOT_FOUND);
        }
        if (attempt.cancelled()) {
            throw new LifecycleException(LifecycleErrorCode.ATTEMPT_CANCELLED);
        }
        // Check expiry before purge so callers observe ATTEMPT_EXPIRED rather than a silent miss.
        if (attempt.expired(clock.millis())) {
            loginAttempts.remove(attemptId);
            rollbackConnectingQuietly();
            throw new LifecycleException(LifecycleErrorCode.ATTEMPT_EXPIRED);
        }
        return attempt;
    }

    private void purgeExpiredLoginAttempts() {
        long now = clock.millis();
        boolean removedAny = false;
        for (Map.Entry<String, LoginAttempt> entry : loginAttempts.entrySet()) {
            LoginAttempt attempt = entry.getValue();
            if (!attempt.expired(now)) {
                continue;
            }
            // Best-effort cancel the app-server login so a lost renderer attempt cannot
            // leave the durable connection CONNECTING forever (PROVIDER_BUSY on retry).
            try {
                if (attempt.appServerLoginId() != null) {
                    appServer.cancelLogin(attempt.appServerLoginId());
                }
            } catch (RuntimeException exception) {
                log.warn("chatgpt expired login cancel failed attempt={}", safeId(entry.getKey()));
            }
            if (loginAttempts.remove(entry.getKey(), attempt)) {
                removedAny = true;
            }
        }
        if (removedAny && loginAttempts.isEmpty()) {
            rollbackConnectingQuietly();
        }
    }

    private void failLoginToDisconnected(String attemptId) {
        loginAttempts.remove(attemptId);
        rollbackConnectingQuietly();
    }

    private void rollbackConnectingQuietly() {
        try {
            AiProviderConnection connection = stateRepository.connection(CHATGPT_PROVIDER);
            if (connection.state() == AiProviderConnectionState.CONNECTING) {
                stateRepository.transitionConnection(
                        CHATGPT_PROVIDER,
                        AiProviderConnectionState.CONNECTING,
                        AiProviderConnectionState.DISCONNECTED,
                        null);
            }
        } catch (RuntimeException exception) {
            log.warn("chatgpt connection rollback failed");
        }
    }

    private void requireEnabled() {
        AiSubscriptionCapability capability = capabilitySupplier.get();
        if (capability == null || !capability.enabled()) {
            throw new LifecycleException(LifecycleErrorCode.FEATURE_DISABLED);
        }
        if (!appServer.isEnabled()) {
            throw new LifecycleException(LifecycleErrorCode.APP_SERVER_UNAVAILABLE);
        }
    }

    private AiProviderSaga findSaga(String sagaId) {
        for (AiProviderSaga saga : stateRepository.findRecoverableSagas()) {
            if (saga.sagaId().equals(sagaId)) {
                return saga;
            }
        }
        // Completed sagas leave the recoverable list — re-read connection is enough.
        return null;
    }

    private java.util.Optional<String> findOpenSignOutSagaId() {
        return stateRepository.findRecoverableSagas().stream()
                .filter(saga -> saga.provider() == CHATGPT_PROVIDER)
                .map(AiProviderSaga::sagaId)
                .findFirst();
    }

    private static String maskAccount(String maskedEmail) {
        if (maskedEmail == null || maskedEmail.isBlank()) {
            return "connected";
        }
        return maskedEmail;
    }

    private static String safeId(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder sanitized = new StringBuilder(8);
        for (int i = 0; i < value.length() && sanitized.length() < 8; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c <= 0x7E) {
                sanitized.append(c);
            }
        }
        return sanitized.length() == 0 ? "-" : sanitized.toString();
    }
}
