package ai.chat2db.community.start.ai.secret;

import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportBeginDecision;
import ai.chat2db.community.domain.api.model.ai.subscription.AiSecretImportItemAck;
import ai.chat2db.community.domain.api.service.storage.IAiSubscriptionStateRepository;
import ai.chat2db.community.tools.security.secretimport.MaskedConfigAcknowledgement;
import ai.chat2db.community.tools.security.secretimport.SecretImportLedgerDecision;
import ai.chat2db.community.tools.security.secretimport.SecretImportLedgerPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Maps the tools-layer secret import protocol onto the Community H2 control ledger. */
public final class H2SecretImportLedgerPort implements SecretImportLedgerPort {

    private final IAiSubscriptionStateRepository repository;

    public H2SecretImportLedgerPort(IAiSubscriptionStateRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public void startAttempt(String attemptId, long expiresAtEpochMs) {
        repository.startSecretImportAttempt(attemptId, Instant.ofEpochMilli(expiresAtEpochMs));
    }

    @Override
    public Optional<MaskedConfigAcknowledgement> findSucceeded(String itemId) {
        return repository.findSucceededSecretImportItem(itemId).map(H2SecretImportLedgerPort::toToolsAck);
    }

    @Override
    public SecretImportLedgerDecision beginItem(String attemptId, String itemId, String nonceHash,
                                                long expiresAtEpochMs, boolean confirmDefault) {
        AiSecretImportBeginDecision decision = repository.beginSecretImportItem(
                attemptId, itemId, nonceHash, Instant.ofEpochMilli(expiresAtEpochMs), confirmDefault);
        return SecretImportLedgerDecision.valueOf(decision.name());
    }

    @Override
    public void markWriteStarted(String attemptId, String itemId) {
        repository.markSecretImportWriteStarted(attemptId, itemId);
    }

    @Override
    public void completeItem(String attemptId, String itemId, MaskedConfigAcknowledgement acknowledgement) {
        repository.completeSecretImportItem(attemptId, itemId, new AiSecretImportItemAck(
                itemId, acknowledgement.getConfigId(), acknowledgement.getName(),
                acknowledgement.getProvider(), acknowledgement.getModel(),
                acknowledgement.isHasApiKey(), acknowledgement.isDefaultConfig()));
    }

    @Override
    public void failBeforeWrite(String attemptId, String itemId, String errorCode) {
        repository.failSecretImportItemBeforeWrite(attemptId, itemId, errorCode);
    }

    @Override
    public void completeAttempt(String attemptId) {
        repository.completeSecretImportAttempt(attemptId);
    }

    @Override
    public void cancelAttempt(String attemptId) {
        repository.cancelSecretImportAttempt(attemptId);
    }

    private static MaskedConfigAcknowledgement toToolsAck(AiSecretImportItemAck source) {
        MaskedConfigAcknowledgement target = new MaskedConfigAcknowledgement();
        target.setConfigId(source.configId());
        target.setName(source.name());
        target.setProvider(source.provider());
        target.setModel(source.model());
        target.setHasApiKey(source.hasCredential());
        target.setDefaultConfig(source.defaultConfig());
        return target;
    }
}
