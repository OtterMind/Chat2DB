package ai.chat2db.community.domain.api.service.storage;

import ai.chat2db.community.domain.api.enums.ai.AiProviderEnum;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttempt;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutput;
import ai.chat2db.community.domain.api.model.ai.subscription.AiAttemptOutputKind;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelRef;
import ai.chat2db.community.domain.api.model.ai.subscription.AiModelSnapshot;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnection;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderConnectionState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderLease;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSaga;
import ai.chat2db.community.domain.api.model.ai.subscription.AiProviderSagaState;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportBeginDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportItemAck;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolExecution;
import ai.chat2db.community.domain.api.model.ai.subscription.AiToolStartResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Transactional, secret-free local state for subscription-backed AI routes.
 */
public interface IAiSubscriptionStateRepository {

    void initialize();

    int schemaVersion();

    AiProviderConnection connection(AiProviderEnum provider);

    void transitionConnection(AiProviderEnum provider, AiProviderConnectionState expected,
                              AiProviderConnectionState target, String maskedAccount);

    void replaceModelSnapshotAndGlobalDefault(AiProviderEnum provider, Instant discoveredAt,
                                              List<AiModelSnapshot> models, AiModelRef globalDefault);

    void replaceModelSnapshot(AiProviderEnum provider, Instant discoveredAt, List<AiModelSnapshot> models);

    void markDiscoveryFailed(AiProviderEnum provider, String errorCode);

    /** Marks one provider-rejected model unavailable and clears preferences that reference it. */
    void markModelRejected(AiModelRef modelRef, String errorCode);

    List<AiModelSnapshot> listCurrentModels(AiProviderEnum provider);

    Optional<AiModelRef> getGlobalDefault();

    void setGlobalDefault(AiModelRef modelRef);

    void setConversationModel(String conversationId, AiModelRef modelRef);

    Optional<AiModelRef> getConversationModel(String conversationId);

    void createAttempt(String attemptId, String messageId, AiProviderEnum provider, AiAttemptState state);

    /**
     * Atomically creates an attempt and acquires the provider lease. When the provider is busy or fenced,
     * neither the attempt nor a message snapshot is created by the caller.
     */
    boolean tryCreateAttemptAndAcquireProviderLease(String attemptId, String messageId,
                                                    AiProviderEnum provider, AiAttemptState state,
                                                    long expectedFenceGeneration);

    Optional<AiAttempt> findAttempt(String attemptId);

    List<AiAttempt> listAttemptsByMessageId(String messageId);

    void transitionAttempt(String attemptId, AiAttemptState expected, AiAttemptState target,
                           String externalThreadId, String externalTurnId);

    void appendAttemptOutput(String attemptId, long sequence, AiAttemptOutputKind kind,
                             String content, boolean visible, boolean contextEligible);

    List<AiAttemptOutput> listAttemptOutputs(String attemptId);

    void saveMessageModelSnapshot(String messageId, AiModelRef modelRef);

    Optional<AiModelRef> getMessageModelSnapshot(String messageId);

    boolean acquireProviderLease(AiProviderEnum provider, String attemptId, long expectedFenceGeneration);

    /**
     * Releases the provider lease only when it is still owned by the named attempt.
     * This compare-and-clear prevents an old terminal callback from releasing a newer attempt.
     */
    boolean releaseProviderLease(AiProviderEnum provider, String attemptId);

    Optional<AiProviderLease> currentLease(AiProviderEnum provider);

    /** Rechecks that the attempt still owns a connected provider lease before a tool result is returned. */
    boolean isAttemptLeaseActive(String attemptId);

    AiToolStartResult beginToolExecution(String attemptId, long sequence, String toolName,
                                         String argumentsHash, String effectFingerprint);

    void completeToolExecution(String attemptId, long sequence, String safeResultReference);

    Optional<AiToolExecution> findToolExecution(String attemptId, long sequence);

    int recoverStartedToolsAsOutcomeUnknown();

    /** Atomically marks the named attempt/tool effect unknown and compare-clears its provider lease. */
    boolean markToolOutcomeUnknownAndReleaseLease(String attemptId);

    /** Terminalizes process-orphaned attempts and clears their persisted provider leases on startup. */
    int recoverOrphanedAttemptsAndLeases();

    String beginSignOut(AiProviderEnum provider);

    void transitionSignOutSaga(String sagaId, AiProviderSagaState expected,
                               AiProviderSagaState target, String errorCode);

    List<AiProviderSaga> findRecoverableSagas();

    void startSecretImportAttempt(String importId, Instant expiresAt);

    Optional<AiSecretImportItemAck> findSucceededSecretImportItem(String itemId);

    AiSecretImportBeginDecision beginSecretImportItem(String importId, String itemId,
                                                      String nonceHash, Instant expiresAt,
                                                      boolean confirmDefault);

    void markSecretImportWriteStarted(String importId, String itemId);

    void completeSecretImportItem(String importId, String itemId, AiSecretImportItemAck acknowledgement);

    void failSecretImportItemBeforeWrite(String importId, String itemId, String errorCode);

    void completeSecretImportAttempt(String importId);

    void cancelSecretImportAttempt(String importId);
}
