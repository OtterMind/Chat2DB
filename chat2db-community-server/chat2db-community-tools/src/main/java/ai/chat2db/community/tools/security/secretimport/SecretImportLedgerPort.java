package ai.chat2db.community.tools.security.secretimport;

import java.util.Optional;

/** Durable, secret-free two-phase ledger for legacy credential imports. */
public interface SecretImportLedgerPort {

    void startAttempt(String attemptId, long expiresAtEpochMs);

    Optional<MaskedConfigAcknowledgement> findSucceeded(String itemId);

    SecretImportLedgerDecision beginItem(String attemptId, String itemId, String nonceHash,
                                         long expiresAtEpochMs, boolean confirmDefault);

    void markWriteStarted(String attemptId, String itemId);

    void completeItem(String attemptId, String itemId, MaskedConfigAcknowledgement acknowledgement);

    void failBeforeWrite(String attemptId, String itemId, String errorCode);

    void completeAttempt(String attemptId);

    void cancelAttempt(String attemptId);
}

