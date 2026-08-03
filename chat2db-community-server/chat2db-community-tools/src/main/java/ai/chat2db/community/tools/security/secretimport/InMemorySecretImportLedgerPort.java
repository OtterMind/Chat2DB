package ai.chat2db.community.tools.security.secretimport;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/local fallback. Production packaged JCEF injects the H2-backed ledger adapter. */
public final class InMemorySecretImportLedgerPort implements SecretImportLedgerPort {

    private final Map<String, Entry> items = new ConcurrentHashMap<>();

    @Override
    public void startAttempt(String attemptId, long expiresAtEpochMs) {
        // Attempt key material remains owned by EncryptedApiKeyImportService.
    }

    @Override
    public Optional<MaskedConfigAcknowledgement> findSucceeded(String itemId) {
        Entry entry = items.get(itemId);
        return entry != null && entry.state == State.SUCCEEDED ? Optional.of(entry.ack) : Optional.empty();
    }

    @Override
    public synchronized SecretImportLedgerDecision beginItem(String attemptId, String itemId, String nonceHash,
                                                             long expiresAtEpochMs, boolean confirmDefault) {
        Entry entry = items.get(itemId);
        if (entry != null && entry.state == State.SUCCEEDED) {
            return SecretImportLedgerDecision.ALREADY_SUCCEEDED;
        }
        if (entry != null && entry.state == State.WRITE_STARTED) {
            return SecretImportLedgerDecision.BLOCKED_OUTCOME_UNKNOWN;
        }
        items.put(itemId, new Entry(attemptId, State.PENDING, null));
        return SecretImportLedgerDecision.STARTED;
    }

    @Override
    public synchronized void markWriteStarted(String attemptId, String itemId) {
        Entry entry = require(attemptId, itemId, State.PENDING);
        entry.state = State.WRITE_STARTED;
    }

    @Override
    public synchronized void completeItem(String attemptId, String itemId,
                                          MaskedConfigAcknowledgement acknowledgement) {
        Entry entry = require(attemptId, itemId, State.WRITE_STARTED);
        entry.state = State.SUCCEEDED;
        entry.ack = acknowledgement;
    }

    @Override
    public synchronized void failBeforeWrite(String attemptId, String itemId, String errorCode) {
        Entry entry = require(attemptId, itemId, State.PENDING);
        entry.state = State.FAILED;
    }

    @Override
    public void completeAttempt(String attemptId) {
        // No-op: item results remain available for cross-attempt idempotency.
    }

    @Override
    public void cancelAttempt(String attemptId) {
        // Pending rows are retryable; WRITE_STARTED rows intentionally remain uncertain.
    }

    private Entry require(String attemptId, String itemId, State state) {
        Entry entry = items.get(itemId);
        if (entry == null || !entry.attemptId.equals(attemptId) || entry.state != state) {
            throw new IllegalStateException("Secret import ledger state changed concurrently");
        }
        return entry;
    }

    private enum State { PENDING, WRITE_STARTED, SUCCEEDED, FAILED }

    private static final class Entry {
        private final String attemptId;
        private State state;
        private MaskedConfigAcknowledgement ack;

        private Entry(String attemptId, State state, MaskedConfigAcknowledgement ack) {
            this.attemptId = attemptId;
            this.state = state;
            this.ack = ack;
        }
    }
}

