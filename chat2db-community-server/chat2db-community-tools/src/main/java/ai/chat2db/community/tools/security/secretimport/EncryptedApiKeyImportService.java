package ai.chat2db.community.tools.security.secretimport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral RSA-OAEP + AES-256-GCM import boundary for legacy renderer API keys.
 *
 * <p>Private keys exist only in memory and are destroyed on complete, cancel, timeout, or destroyAll.
 * Responses never include secrets, ciphertext fragments, fingerprints, or exception detail.
 *
 * <p>Decrypted payload bytes are zeroed in {@code finally}. Intermediate {@link String} objects
 * created by JSON parsing cannot be securely erased by the JVM; callers must not treat them as wiped.
 */
public final class EncryptedApiKeyImportService {

    public static final int SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(EncryptedApiKeyImportService.class);
    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int RSA_KEY_BITS = 2048;
    private static final int GCM_TAG_BITS = 128;
    private static final long DEFAULT_TTL_MS = 5 * 60_000L;

    private final SecretImportModelConfigPort modelConfigPort;
    private final SecretImportLedgerPort ledgerPort;
    private final long attemptTtlMs;
    private final Map<String, ImportAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> usedNonces = new ConcurrentHashMap<>();

    public EncryptedApiKeyImportService(SecretImportModelConfigPort modelConfigPort) {
        this(modelConfigPort, new InMemorySecretImportLedgerPort(), DEFAULT_TTL_MS);
    }

    public EncryptedApiKeyImportService(SecretImportModelConfigPort modelConfigPort, long attemptTtlMs) {
        this(modelConfigPort, new InMemorySecretImportLedgerPort(), attemptTtlMs);
    }

    public EncryptedApiKeyImportService(SecretImportModelConfigPort modelConfigPort,
                                        SecretImportLedgerPort ledgerPort,
                                        long attemptTtlMs) {
        this.modelConfigPort = Objects.requireNonNull(modelConfigPort, "modelConfigPort");
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort");
        this.attemptTtlMs = attemptTtlMs;
    }

    public SecretImportAttemptStart startAttempt() {
        purgeExpired();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS);
            KeyPair keyPair = generator.generateKeyPair();
            String attemptId = UUID.randomUUID().toString();
            long expiresAt = System.currentTimeMillis() + attemptTtlMs;
            ImportAttempt attempt = new ImportAttempt(attemptId, keyPair, expiresAt);
            attempts.put(attemptId, attempt);
            try {
                ledgerPort.startAttempt(attemptId, expiresAt);
            } catch (RuntimeException exception) {
                destroyAttempt(attemptId, true);
                throw new IllegalStateException("Unable to start secret import attempt");
            }
            String publicKeySpki = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            return new SecretImportAttemptStart(attemptId, publicKeySpki, expiresAt, SCHEMA_VERSION);
        } catch (GeneralSecurityException exception) {
            log.warn("secret-import start failed: key generation unavailable");
            throw new IllegalStateException("Unable to start secret import attempt");
        }
    }

    public SecretImportItemResult importItem(EncryptedSecretImportEnvelope envelope) {
        purgeExpired();
        if (envelope == null
                || isBlank(envelope.getAttemptId())
                || isBlank(envelope.getItemId())
                || isBlank(envelope.getNonceBase64())
                || isBlank(envelope.getWrappedKeyBase64())
                || isBlank(envelope.getCiphertextBase64())) {
            return SecretImportItemResult.failed(
                    envelope == null ? null : envelope.getAttemptId(),
                    envelope == null ? null : envelope.getItemId(),
                    SecretImportErrorCode.INVALID_ENVELOPE);
        }

        SecretImportErrorCode sizeError = validateEnvelopeSizes(envelope);
        if (sizeError != null) {
            return SecretImportItemResult.failed(
                    envelope.getAttemptId(), envelope.getItemId(), sizeError);
        }

        if (envelope.getSchemaVersion() != SCHEMA_VERSION) {
            return SecretImportItemResult.failed(
                    envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.UNSUPPORTED_SCHEMA);
        }

        long now = System.currentTimeMillis();
        ImportAttempt attempt = attempts.get(envelope.getAttemptId());
        if (attempt == null || attempt.destroyed) {
            if (envelope.getExpiresAtEpochMs() > 0 && now > envelope.getExpiresAtEpochMs()) {
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_EXPIRED);
            }
            return SecretImportItemResult.failed(
                    envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_NOT_FOUND);
        }
        if (attempt.cancelled) {
            return SecretImportItemResult.failed(
                    envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_CANCELLED);
        }
        if (now > attempt.expiresAtEpochMs || now > envelope.getExpiresAtEpochMs()
                || envelope.getExpiresAtEpochMs() != attempt.expiresAtEpochMs) {
            destroyAttempt(attempt.attemptId, true);
            return SecretImportItemResult.failed(
                    envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_EXPIRED);
        }

        // Per-item gate: concurrent submits of the same itemId serialize so only one write occurs.
        Object itemGate = attempt.itemGates.computeIfAbsent(envelope.getItemId(), key -> new Object());
        synchronized (itemGate) {
            MaskedConfigAcknowledgement prior = attempt.succeededItems.get(envelope.getItemId());
            if (prior != null) {
                return SecretImportItemResult.alreadyImported(envelope.getAttemptId(), envelope.getItemId(), prior);
            }
            try {
                prior = ledgerPort.findSucceeded(envelope.getItemId()).orElse(null);
            } catch (RuntimeException ledgerFailure) {
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.PERSISTENCE_FAILED);
            }
            if (prior != null) {
                attempt.succeededItems.put(envelope.getItemId(), prior);
                return SecretImportItemResult.alreadyImported(envelope.getAttemptId(), envelope.getItemId(), prior);
            }

            if (attempt.completed) {
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_COMPLETED);
            }
            if (attempt.privateKey == null) {
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.ATTEMPT_NOT_FOUND);
            }

            String nonceKey = attempt.attemptId + ":" + envelope.getNonceBase64();
            if (usedNonces.putIfAbsent(nonceKey, Boolean.TRUE) != null) {
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.NONCE_REPLAY);
            }

            ImportedApiKeyConfig config = null;
            byte[] aesKeyBytes = null;
            byte[] plaintextBytes = null;
            boolean writeStarted = false;
            try {
                aesKeyBytes = unwrapAesKey(attempt.privateKey, envelope.getWrappedKeyBase64());
                byte[] aad = buildAad(
                        envelope.getSchemaVersion(),
                        envelope.getAttemptId(),
                        envelope.getItemId(),
                        envelope.getNonceBase64(),
                        envelope.getExpiresAtEpochMs());
                plaintextBytes = decryptPayloadBytes(
                        aesKeyBytes, envelope.getNonceBase64(), envelope.getCiphertextBase64(), aad);
                config = parsePayload(plaintextBytes);
                boolean applyDefault = envelope.isConfirmDefault() && Boolean.TRUE.equals(config.getDefaultConfig());
                config.setDefaultConfig(applyDefault);
                if (isBlank(config.getProvider()) || isBlank(config.getModel()) || isBlank(config.getName())) {
                    return SecretImportItemResult.failed(
                            envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.INVALID_PAYLOAD);
                }
                if (isBlank(config.getId())) {
                    config.setId(UUID.randomUUID().toString());
                }

                SecretImportLedgerDecision decision = ledgerPort.beginItem(
                        envelope.getAttemptId(), envelope.getItemId(), hashNonce(envelope.getNonceBase64()),
                        envelope.getExpiresAtEpochMs(), envelope.isConfirmDefault());
                if (decision == SecretImportLedgerDecision.ALREADY_SUCCEEDED) {
                    MaskedConfigAcknowledgement succeeded = ledgerPort.findSucceeded(envelope.getItemId())
                            .orElseThrow(() -> new IllegalStateException("Missing durable import acknowledgement"));
                    attempt.succeededItems.put(envelope.getItemId(), succeeded);
                    return SecretImportItemResult.alreadyImported(
                            envelope.getAttemptId(), envelope.getItemId(), succeeded);
                }
                if (decision == SecretImportLedgerDecision.BLOCKED_OUTCOME_UNKNOWN) {
                    return SecretImportItemResult.failed(envelope.getAttemptId(), envelope.getItemId(),
                            SecretImportErrorCode.IMPORT_OUTCOME_UNKNOWN);
                }

                ledgerPort.markWriteStarted(envelope.getAttemptId(), envelope.getItemId());
                writeStarted = true;

                MaskedConfigAcknowledgement ack = modelConfigPort.writeAndReadback(config);
                ledgerPort.completeItem(envelope.getAttemptId(), envelope.getItemId(), ack);
                attempt.succeededItems.put(envelope.getItemId(), ack);
                return SecretImportItemResult.succeeded(envelope.getAttemptId(), envelope.getItemId(), ack);
            } catch (SecretImportCryptoException cryptoException) {
                log.warn("secret-import decrypt failed for attempt {} item {}",
                        SecretImportSafety.safeId(envelope.getAttemptId()),
                        SecretImportSafety.safeId(envelope.getItemId()));
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), SecretImportErrorCode.DECRYPT_FAILED);
            } catch (RuntimeException persistenceException) {
                log.warn("secret-import persistence failed for attempt {} item {}",
                        SecretImportSafety.safeId(envelope.getAttemptId()),
                        SecretImportSafety.safeId(envelope.getItemId()));
                if (!writeStarted) {
                    try {
                        ledgerPort.failBeforeWrite(envelope.getAttemptId(), envelope.getItemId(),
                                SecretImportErrorCode.PERSISTENCE_FAILED.name());
                    } catch (RuntimeException ignored) {
                        // The safe error below is authoritative; never expose ledger detail.
                    }
                }
                return SecretImportItemResult.failed(
                        envelope.getAttemptId(), envelope.getItemId(), writeStarted
                                ? SecretImportErrorCode.IMPORT_OUTCOME_UNKNOWN
                                : SecretImportErrorCode.PERSISTENCE_FAILED);
            } finally {
                if (aesKeyBytes != null) {
                    Arrays.fill(aesKeyBytes, (byte) 0);
                }
                if (plaintextBytes != null) {
                    Arrays.fill(plaintextBytes, (byte) 0);
                }
                if (config != null) {
                    config.destroySecret();
                }
            }
        }
    }

    /**
     * Mark the import attempt complete and destroy its private key.
     * Succeeded item acknowledgements remain available for idempotent ALREADY_IMPORTED responses
     * until the attempt expires; new item decryption is refused.
     */
    public void completeAttempt(String attemptId) {
        if (isBlank(attemptId) || SecretImportLimits.exceeds(attemptId, SecretImportLimits.MAX_ID_CHARS)) {
            return;
        }
        ImportAttempt attempt = attempts.get(attemptId);
        if (attempt == null || attempt.destroyed) {
            return;
        }
        if (attempt.completed) {
            return;
        }
        attempt.completed = true;
        destroyPrivateKeyMaterial(attempt);
        ledgerPort.completeAttempt(attemptId);
    }

    /**
     * Cancel the attempt: destroy private key and drop all attempt state.
     */
    public void cancelAttempt(String attemptId) {
        if (isBlank(attemptId) || SecretImportLimits.exceeds(attemptId, SecretImportLimits.MAX_ID_CHARS)) {
            return;
        }
        ImportAttempt attempt = attempts.get(attemptId);
        if (attempt != null) {
            attempt.cancelled = true;
            destroyAttempt(attemptId, true);
            ledgerPort.cancelAttempt(attemptId);
        }
    }

    public void destroyAll() {
        for (String attemptId : attempts.keySet()) {
            destroyAttempt(attemptId, true);
        }
        usedNonces.clear();
    }

    public boolean isPrivateKeyDestroyed(String attemptId) {
        ImportAttempt attempt = attempts.get(attemptId);
        return attempt == null || attempt.destroyed || attempt.privateKey == null;
    }

    public boolean isAttemptCompleted(String attemptId) {
        ImportAttempt attempt = attempts.get(attemptId);
        return attempt != null && attempt.completed;
    }

    public static byte[] buildAad(int schemaVersion, String attemptId, String itemId, String nonceBase64,
                                  long expiresAtEpochMs) {
        String bound = schemaVersion + "|" + attemptId + "|" + itemId + "|" + nonceBase64 + "|" + expiresAtEpochMs;
        return bound.getBytes(StandardCharsets.UTF_8);
    }

    private static SecretImportErrorCode validateEnvelopeSizes(EncryptedSecretImportEnvelope envelope) {
        if (SecretImportLimits.exceeds(envelope.getAttemptId(), SecretImportLimits.MAX_ID_CHARS)
                || SecretImportLimits.exceeds(envelope.getItemId(), SecretImportLimits.MAX_ID_CHARS)) {
            return SecretImportErrorCode.PAYLOAD_TOO_LARGE;
        }
        if (SecretImportLimits.exceeds(envelope.getNonceBase64(), SecretImportLimits.MAX_NONCE_BASE64_CHARS)
                || SecretImportLimits.exceeds(envelope.getWrappedKeyBase64(),
                SecretImportLimits.MAX_WRAPPED_KEY_BASE64_CHARS)
                || SecretImportLimits.exceeds(envelope.getCiphertextBase64(),
                SecretImportLimits.MAX_CIPHERTEXT_BASE64_CHARS)) {
            return SecretImportErrorCode.PAYLOAD_TOO_LARGE;
        }
        return null;
    }

    private static String hashNonce(String nonceBase64) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(nonceBase64.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Nonce hashing unavailable");
        }
    }

    private byte[] unwrapAesKey(PrivateKey privateKey, String wrappedKeyBase64) {
        try {
            byte[] wrapped = Base64.getDecoder().decode(wrappedKeyBase64);
            Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
            OAEPParameterSpec oaep = new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            rsa.init(Cipher.DECRYPT_MODE, privateKey, oaep);
            return rsa.doFinal(wrapped);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new SecretImportCryptoException();
        }
    }

    /**
     * Decrypt to raw bytes. Caller must zero the returned array in {@code finally}.
     * A UTF-8 decode to String is intentionally avoided here so the secret-bearing buffer can be wiped.
     */
    private byte[] decryptPayloadBytes(byte[] aesKeyBytes, String nonceBase64, String ciphertextBase64, byte[] aad) {
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            nonce = Base64.getDecoder().decode(nonceBase64);
            ciphertext = Base64.getDecoder().decode(ciphertextBase64);
            SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
            Cipher gcm = Cipher.getInstance(AES_TRANSFORMATION);
            gcm.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            gcm.updateAAD(aad);
            return gcm.doFinal(ciphertext);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new SecretImportCryptoException();
        } finally {
            if (nonce != null) {
                Arrays.fill(nonce, (byte) 0);
            }
            if (ciphertext != null) {
                Arrays.fill(ciphertext, (byte) 0);
            }
        }
    }

    private ImportedApiKeyConfig parsePayload(byte[] plaintextBytes) {
        try {
            // JSON parsing creates intermediate Strings (including apiKey). Those cannot be wiped;
            // only the source byte[] is zeroed by the caller after this method returns.
            JSONObject json = JSON.parseObject(plaintextBytes);
            if (json == null) {
                throw new SecretImportCryptoException();
            }
            ImportedApiKeyConfig config = new ImportedApiKeyConfig();
            config.setId(json.getString("id"));
            config.setName(json.getString("name"));
            config.setProvider(json.getString("provider"));
            config.setModel(json.getString("model"));
            config.setApiKey(json.getString("apiKey"));
            config.setBaseUrl(json.getString("baseUrl"));
            config.setProjectId(json.getString("projectId"));
            config.setLocation(json.getString("location"));
            config.setTemperature(json.getDouble("temperature"));
            config.setMaxTokens(json.getInteger("maxTokens"));
            config.setEnabled(json.getBoolean("enabled"));
            config.setDefaultConfig(json.getBoolean("defaultConfig"));
            return config;
        } catch (RuntimeException exception) {
            throw new SecretImportCryptoException();
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        for (ImportAttempt attempt : attempts.values()) {
            if (now > attempt.expiresAtEpochMs) {
                destroyAttempt(attempt.attemptId, true);
            }
        }
    }

    private void destroyAttempt(String attemptId, boolean remove) {
        ImportAttempt attempt = remove ? attempts.remove(attemptId) : attempts.get(attemptId);
        if (attempt == null) {
            return;
        }
        attempt.destroyed = remove;
        destroyPrivateKeyMaterial(attempt);
        if (remove) {
            usedNonces.keySet().removeIf(key -> key.startsWith(attemptId + ":"));
            attempt.succeededItems.clear();
            attempt.itemGates.clear();
        }
    }

    private static void destroyPrivateKeyMaterial(ImportAttempt attempt) {
        attempt.privateKey = null;
        attempt.keyPair = null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class ImportAttempt {
        private final String attemptId;
        private final long expiresAtEpochMs;
        private final Map<String, MaskedConfigAcknowledgement> succeededItems = new ConcurrentHashMap<>();
        /**
         * Per-item synchronization for concurrent import of the same itemId.
         */
        private final Map<String, Object> itemGates = new ConcurrentHashMap<>();
        private volatile KeyPair keyPair;
        private volatile PrivateKey privateKey;
        private volatile boolean cancelled;
        private volatile boolean completed;
        private volatile boolean destroyed;

        private ImportAttempt(String attemptId, KeyPair keyPair, long expiresAtEpochMs) {
            this.attemptId = attemptId;
            this.keyPair = keyPair;
            this.privateKey = keyPair.getPrivate();
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    private static final class SecretImportCryptoException extends RuntimeException {
        private SecretImportCryptoException() {
            super((String) null, null, false, false);
        }
    }
}
