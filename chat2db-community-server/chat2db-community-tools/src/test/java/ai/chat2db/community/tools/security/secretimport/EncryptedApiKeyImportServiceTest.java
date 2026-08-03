package ai.chat2db.community.tools.security.secretimport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5 encrypted legacy API-key import boundary tests.
 * Canary is used only for absence assertions; production responses must never echo it.
 */
class EncryptedApiKeyImportServiceTest {

    /**
     * Synthetic canary — asserted absent from safe outputs. Not a real credential.
     */
    private static final String CANARY = "sk-canary-T5-7f3a9b2c1e-DO-NOT-LEAK";

    private RecordingPort port;
    private EncryptedApiKeyImportService service;

    @BeforeEach
    void setUp() {
        port = new RecordingPort();
        service = new EncryptedApiKeyImportService(port, 60_000L);
    }

    @AfterEach
    void tearDown() {
        service.destroyAll();
    }

    @Test
    void startAttemptReturnsPublicKeyWithoutPrivateMaterial() {
        SecretImportAttemptStart start = service.startAttempt();

        assertNotNull(start.getAttemptId());
        assertNotNull(start.getPublicKeySpkiBase64());
        assertTrue(start.getExpiresAtEpochMs() > System.currentTimeMillis());
        assertEquals(EncryptedApiKeyImportService.SCHEMA_VERSION, start.getSchemaVersion());
        assertNull(start.getPrivateKeyMaterial());
        assertSafe(start.toSafeMap());
    }

    @Test
    void importsEncryptedEnvelopeWithoutReturningAnyKeyFragment() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-1", legacyConfigJson("cfg-1", "OpenAI Prod", false), CANARY);

        SecretImportItemResult result = service.importItem(envelope);

        assertEquals(SecretImportItemStatus.SUCCEEDED, result.getStatus());
        assertEquals("item-1", result.getItemId());
        assertEquals(start.getAttemptId(), result.getAttemptId());
        assertEquals("cfg-1", result.getConfigId());
        assertEquals("OpenAI Prod", result.getName());
        assertEquals("OPENAI", result.getProvider());
        assertEquals("gpt-4o", result.getModel());
        assertTrue(result.getHasApiKey());
        assertFalse(result.toSafeMap().containsKey("apiKeyMasked"));
        assertFalse(result.toSafeMap().toString().contains(CANARY.substring(0, 8)));
        assertFalse(result.toSafeMap().toString().contains(CANARY.substring(CANARY.length() - 8)));
        assertFalse(Boolean.TRUE.equals(result.getDefaultConfig()));
        assertEquals(1, port.writes.size());
        assertEquals(CANARY, port.writes.get(0).getApiKey());
        assertFalse(Boolean.TRUE.equals(port.writes.get(0).getDefaultConfig()));
        assertSafe(result.toSafeMap());
        assertSafe(String.valueOf(result.getErrorCode()));
    }

    @Test
    void neverSilentlyAppliesDefaultConfigUnlessConfirmed() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        // Payload claims default, but confirmDefault is false.
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-default", legacyConfigJson("cfg-d", "Default Claim", true), CANARY);

        SecretImportItemResult result = service.importItem(envelope);

        assertEquals(SecretImportItemStatus.SUCCEEDED, result.getStatus());
        assertFalse(Boolean.TRUE.equals(result.getDefaultConfig()));
        assertFalse(Boolean.TRUE.equals(port.writes.get(0).getDefaultConfig()));

        SecretImportAttemptStart start2 = service.startAttempt();
        EncryptedSecretImportEnvelope confirmed = buildEnvelope(
                start2, "item-default-2", legacyConfigJson("cfg-d2", "Default Ok", true), CANARY,
                true);
        SecretImportItemResult confirmedResult = service.importItem(confirmed);
        assertEquals(SecretImportItemStatus.SUCCEEDED, confirmedResult.getStatus());
        assertTrue(Boolean.TRUE.equals(port.writes.get(1).getDefaultConfig()));
    }

    @Test
    void rejectsNonceReplayWithSafeErrorAndNoCanaryLeak() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-replay", legacyConfigJson("cfg-r", "Replay", false), CANARY);

        assertEquals(SecretImportItemStatus.SUCCEEDED, service.importItem(envelope).getStatus());
        // Second call with same nonce must fail even if item id differs.
        EncryptedSecretImportEnvelope replay = copyWithItemId(envelope, "item-replay-2");
        SecretImportItemResult failed = service.importItem(replay);

        assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
        assertEquals(SecretImportErrorCode.NONCE_REPLAY, failed.getErrorCode());
        assertSafe(failed.toSafeMap());
        assertSafe(failed.getErrorCode().name());
    }

    @Test
    void rejectsExpiredAttemptWithSafeError() throws Exception {
        EncryptedApiKeyImportService shortLived = new EncryptedApiKeyImportService(port, 1L);
        try {
            SecretImportAttemptStart start = shortLived.startAttempt();
            Thread.sleep(5L);
            EncryptedSecretImportEnvelope envelope = buildEnvelope(
                    start, "item-exp", legacyConfigJson("cfg-e", "Expired", false), CANARY);
            SecretImportItemResult failed = shortLived.importItem(envelope);
            assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
            assertEquals(SecretImportErrorCode.ATTEMPT_EXPIRED, failed.getErrorCode());
            assertSafe(failed.toSafeMap());
        } finally {
            shortLived.destroyAll();
        }
    }

    @Test
    void rejectsTamperedAadBindingWithSafeErrorAndNoCanaryLeak() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-aad", legacyConfigJson("cfg-a", "AAD", false), CANARY);
        // Tamper item id so AAD no longer matches ciphertext binding.
        envelope.setItemId("item-aad-tampered");

        SecretImportItemResult failed = service.importItem(envelope);

        assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
        assertEquals(SecretImportErrorCode.DECRYPT_FAILED, failed.getErrorCode());
        assertSafe(failed.toSafeMap());
        assertNull(failed.getErrorDetail());
    }

    @Test
    void itemIdempotencyReturnsAlreadyImportedWithoutSecondWrite() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope first = buildEnvelope(
                start, "item-idemp", legacyConfigJson("cfg-i", "Idemp", false), CANARY);
        assertEquals(SecretImportItemStatus.SUCCEEDED, service.importItem(first).getStatus());

        EncryptedSecretImportEnvelope second = buildEnvelope(
                start, "item-idemp", legacyConfigJson("cfg-i", "Idemp", false), CANARY);
        SecretImportItemResult again = service.importItem(second);

        assertEquals(SecretImportItemStatus.ALREADY_IMPORTED, again.getStatus());
        assertEquals(1, port.writes.size());
        assertSafe(again.toSafeMap());
    }

    @Test
    void ambiguousPersistenceFailureIsDurablyBlockedFromReplay() throws Exception {
        port.failNextWrites = 1;
        SecretImportAttemptStart start = service.startAttempt();

        EncryptedSecretImportEnvelope first = buildEnvelope(
                start, "item-retry", legacyConfigJson("cfg-t", "Retry", false), CANARY);
        SecretImportItemResult failed = service.importItem(first);
        assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
        assertEquals(SecretImportErrorCode.IMPORT_OUTCOME_UNKNOWN, failed.getErrorCode());
        assertSafe(failed.toSafeMap());
        assertEquals(0, port.writes.size());

        EncryptedSecretImportEnvelope retry = buildEnvelope(
                start, "item-retry", legacyConfigJson("cfg-t", "Retry", false), CANARY);
        SecretImportItemResult blocked = service.importItem(retry);
        assertEquals(SecretImportItemStatus.FAILED, blocked.getStatus());
        assertEquals(SecretImportErrorCode.IMPORT_OUTCOME_UNKNOWN, blocked.getErrorCode());
        assertEquals(0, port.writes.size());
    }

    @Test
    void successfulItemIsIdempotentAcrossServiceRestartWithSharedDurableLedger() throws Exception {
        InMemorySecretImportLedgerPort ledger = new InMemorySecretImportLedgerPort();
        EncryptedApiKeyImportService first = new EncryptedApiKeyImportService(port, ledger, 60_000L);
        SecretImportAttemptStart firstStart = first.startAttempt();
        SecretImportItemResult firstResult = first.importItem(buildEnvelope(
                firstStart, "stable-item", legacyConfigJson("cfg-stable", "Stable", false), CANARY));
        assertEquals(SecretImportItemStatus.SUCCEEDED, firstResult.getStatus());
        first.destroyAll();

        EncryptedApiKeyImportService restarted = new EncryptedApiKeyImportService(port, ledger, 60_000L);
        try {
            SecretImportAttemptStart secondStart = restarted.startAttempt();
            SecretImportItemResult duplicate = restarted.importItem(buildEnvelope(
                    secondStart, "stable-item", legacyConfigJson("cfg-stable", "Stable", false), CANARY));
            assertEquals(SecretImportItemStatus.ALREADY_IMPORTED, duplicate.getStatus());
            assertEquals(1, port.writes.size());
        } finally {
            restarted.destroyAll();
        }
    }

    @Test
    void cancelDestroysPrivateKeyAndRejectsFurtherImport() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        service.cancelAttempt(start.getAttemptId());

        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-cancel", legacyConfigJson("cfg-c", "Cancel", false), CANARY);
        SecretImportItemResult failed = service.importItem(envelope);
        assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
        assertEquals(SecretImportErrorCode.ATTEMPT_NOT_FOUND, failed.getErrorCode());
        assertTrue(service.isPrivateKeyDestroyed(start.getAttemptId()));
        assertSafe(failed.toSafeMap());
    }

    @Test
    void exceptionsAndSafeMapsNeverContainCanaryOnAnyFailurePath() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        List<SecretImportItemResult> results = new ArrayList<>();

        EncryptedSecretImportEnvelope badSchema = buildEnvelope(
                start, "item-s", legacyConfigJson("cfg-s", "S", false), CANARY);
        badSchema.setSchemaVersion(999);
        results.add(service.importItem(badSchema));

        EncryptedSecretImportEnvelope badCipher = buildEnvelope(
                start, "item-c", legacyConfigJson("cfg-c", "C", false), CANARY);
        badCipher.setCiphertextBase64(Base64.getEncoder().encodeToString(new byte[48]));
        results.add(service.importItem(badCipher));

        results.add(service.importItem(null));

        for (SecretImportItemResult result : results) {
            assertEquals(SecretImportItemStatus.FAILED, result.getStatus());
            assertSafe(result.toSafeMap());
            if (result.getErrorCode() != null) {
                assertSafe(result.getErrorCode().name());
            }
            assertNull(result.getErrorDetail());
        }
    }

    @Test
    void boundaryRawJsonPathNeverEchoesEnvelopeSecrets() throws Exception {
        SecretImportBoundary boundary = new SecretImportBoundary(service);
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-raw", legacyConfigJson("cfg-raw", "Raw", false), CANARY);
        String raw = envelopeToJson(envelope);

        Map<String, Object> response = boundary.handleRawRequest(
                SecretImportBoundary.ACTION_IMPORT_ITEM, "post", raw);

        assertEquals("SUCCEEDED", response.get("status"));
        assertSafe(response);
        assertFalse(raw.contains("\"apiKey\"")); // envelope carries ciphertext only
        assertTrue(raw.contains(envelope.getCiphertextBase64()));
        // Canary lives only inside ciphertext bytes as UTF-8 of JSON — still must not appear in response.
        assertFalse(String.valueOf(response).contains(CANARY));
    }

    @Test
    void concurrentSameItemIdPerformsSingleWrite() throws Exception {
        port.writeDelayMs = 80;
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope first = buildEnvelope(
                start, "item-race", legacyConfigJson("cfg-race", "Race", false), CANARY);
        EncryptedSecretImportEnvelope second = buildEnvelope(
                start, "item-race", legacyConfigJson("cfg-race", "Race", false), CANARY);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<SecretImportItemResult> f1 = pool.submit(() -> {
                ready.countDown();
                go.await(2, TimeUnit.SECONDS);
                return service.importItem(first);
            });
            Future<SecretImportItemResult> f2 = pool.submit(() -> {
                ready.countDown();
                go.await(2, TimeUnit.SECONDS);
                return service.importItem(second);
            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();

            SecretImportItemResult r1 = f1.get(5, TimeUnit.SECONDS);
            SecretImportItemResult r2 = f2.get(5, TimeUnit.SECONDS);

            // Exactly one durable write regardless of concurrent submission.
            assertEquals(1, port.writes.size());
            assertEquals(1, port.writeCalls.get());
            assertTrue(isSuccessFamily(r1.getStatus()), "r1=" + r1.getStatus());
            assertTrue(isSuccessFamily(r2.getStatus()), "r2=" + r2.getStatus());
            EnumSet<SecretImportItemStatus> statuses = EnumSet.of(r1.getStatus(), r2.getStatus());
            assertTrue(statuses.contains(SecretImportItemStatus.SUCCEEDED));
            assertSafe(r1.toSafeMap());
            assertSafe(r2.toSafeMap());
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isSuccessFamily(SecretImportItemStatus status) {
        return status == SecretImportItemStatus.SUCCEEDED || status == SecretImportItemStatus.ALREADY_IMPORTED;
    }

    @Test
    void completeDestroysPrivateKeyKeepsIdempotentAckAndBlocksNewItems() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope first = buildEnvelope(
                start, "item-done", legacyConfigJson("cfg-done", "Done", false), CANARY);
        assertEquals(SecretImportItemStatus.SUCCEEDED, service.importItem(first).getStatus());

        service.completeAttempt(start.getAttemptId());
        service.completeAttempt(start.getAttemptId());
        assertTrue(service.isAttemptCompleted(start.getAttemptId()));
        assertTrue(service.isPrivateKeyDestroyed(start.getAttemptId()));

        EncryptedSecretImportEnvelope again = buildEnvelope(
                start, "item-done", legacyConfigJson("cfg-done", "Done", false), CANARY);
        SecretImportItemResult idempotent = service.importItem(again);
        assertEquals(SecretImportItemStatus.ALREADY_IMPORTED, idempotent.getStatus());
        assertEquals(1, port.writes.size());

        EncryptedSecretImportEnvelope freshItem = buildEnvelope(
                start, "item-after-complete", legacyConfigJson("cfg-new", "New", false), CANARY);
        SecretImportItemResult blocked = service.importItem(freshItem);
        assertEquals(SecretImportItemStatus.FAILED, blocked.getStatus());
        assertEquals(SecretImportErrorCode.ATTEMPT_COMPLETED, blocked.getErrorCode());
        assertSafe(blocked.toSafeMap());
    }

    @Test
    void completeDiffersFromCancel() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope first = buildEnvelope(
                start, "item-x", legacyConfigJson("cfg-x", "X", false), CANARY);
        assertEquals(SecretImportItemStatus.SUCCEEDED, service.importItem(first).getStatus());
        service.completeAttempt(start.getAttemptId());
        assertTrue(service.isAttemptCompleted(start.getAttemptId()));
        // complete retains attempt tombstone for ALREADY_IMPORTED
        assertEquals(SecretImportItemStatus.ALREADY_IMPORTED,
                service.importItem(buildEnvelope(start, "item-x", legacyConfigJson("cfg-x", "X", false), CANARY))
                        .getStatus());

        SecretImportAttemptStart start2 = service.startAttempt();
        EncryptedSecretImportEnvelope second = buildEnvelope(
                start2, "item-y", legacyConfigJson("cfg-y", "Y", false), CANARY);
        assertEquals(SecretImportItemStatus.SUCCEEDED, service.importItem(second).getStatus());
        service.cancelAttempt(start2.getAttemptId());
        assertFalse(service.isAttemptCompleted(start2.getAttemptId()));
        SecretImportItemResult afterCancel = service.importItem(
                buildEnvelope(start2, "item-y", legacyConfigJson("cfg-y", "Y", false), CANARY));
        assertEquals(SecretImportErrorCode.ATTEMPT_NOT_FOUND, afterCancel.getErrorCode());
    }

    @Test
    void rejectsOversizedEnvelopeFields() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(
                start, "item-size", legacyConfigJson("cfg-s", "S", false), CANARY);
        envelope.setCiphertextBase64("A".repeat(SecretImportLimits.MAX_CIPHERTEXT_BASE64_CHARS + 1));

        SecretImportItemResult failed = service.importItem(envelope);
        assertEquals(SecretImportItemStatus.FAILED, failed.getStatus());
        assertEquals(SecretImportErrorCode.PAYLOAD_TOO_LARGE, failed.getErrorCode());
        assertSafe(failed.toSafeMap());
    }

    @Test
    void boundaryRejectsOversizedBody() {
        SecretImportBoundary boundary = new SecretImportBoundary(service);
        String huge = "x".repeat(SecretImportLimits.MAX_BODY_CHARS + 8);
        Map<String, Object> response = boundary.handleRawRequest(
                SecretImportBoundary.ACTION_IMPORT_ITEM, "post", huge);
        assertEquals("FAILED", response.get("status"));
        assertEquals(SecretImportErrorCode.PAYLOAD_TOO_LARGE.name(), response.get("errorCode"));
    }

    @Test
    void boundaryCompleteActionDestroysKey() throws Exception {
        SecretImportBoundary boundary = new SecretImportBoundary(service);
        SecretImportAttemptStart start = service.startAttempt();
        Map<String, Object> response = boundary.handleRawRequest(
                SecretImportBoundary.ACTION_COMPLETE, "post",
                "{\"attemptId\":\"" + start.getAttemptId() + "\"}");
        assertEquals("COMPLETED", response.get("status"));
        assertTrue(service.isPrivateKeyDestroyed(start.getAttemptId()));
        assertTrue(service.isAttemptCompleted(start.getAttemptId()));
    }

    private void assertSafe(Object value) {
        String text = String.valueOf(value);
        assertFalse(text.contains(CANARY), "canary leaked into safe surface");
        assertFalse(text.contains("BEGIN PRIVATE KEY"));
        assertFalse(text.toLowerCase().contains("privatekey"));
    }

    private EncryptedSecretImportEnvelope buildEnvelope(
            SecretImportAttemptStart start,
            String itemId,
            String plaintextJson,
            String apiKey) throws Exception {
        return buildEnvelope(start, itemId, plaintextJson.replace("__API_KEY__", apiKey), apiKey, false);
    }

    private EncryptedSecretImportEnvelope buildEnvelope(
            SecretImportAttemptStart start,
            String itemId,
            String plaintextJson,
            String apiKey,
            boolean confirmDefault) throws Exception {
        String payload = plaintextJson.contains("__API_KEY__")
                ? plaintextJson.replace("__API_KEY__", apiKey)
                : plaintextJson;
        byte[] nonce = new byte[12];
        java.security.SecureRandom.getInstanceStrong().nextBytes(nonce);
        String nonceB64 = Base64.getEncoder().encodeToString(nonce);

        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        byte[] aad = EncryptedApiKeyImportService.buildAad(
                EncryptedApiKeyImportService.SCHEMA_VERSION,
                start.getAttemptId(),
                itemId,
                nonceB64,
                start.getExpiresAtEpochMs());

        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
        gcm.updateAAD(aad);
        byte[] ciphertext = gcm.doFinal(payload.getBytes(StandardCharsets.UTF_8));

        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(start.getPublicKeySpkiBase64())));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey,
                new javax.crypto.spec.OAEPParameterSpec(
                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT));
        byte[] wrapped = rsa.doFinal(aesKey.getEncoded());

        EncryptedSecretImportEnvelope envelope = new EncryptedSecretImportEnvelope();
        envelope.setSchemaVersion(EncryptedApiKeyImportService.SCHEMA_VERSION);
        envelope.setAttemptId(start.getAttemptId());
        envelope.setItemId(itemId);
        envelope.setNonceBase64(nonceB64);
        envelope.setExpiresAtEpochMs(start.getExpiresAtEpochMs());
        envelope.setWrappedKeyBase64(Base64.getEncoder().encodeToString(wrapped));
        envelope.setCiphertextBase64(Base64.getEncoder().encodeToString(ciphertext));
        envelope.setConfirmDefault(confirmDefault);
        return envelope;
    }

    private EncryptedSecretImportEnvelope copyWithItemId(EncryptedSecretImportEnvelope source, String itemId) {
        EncryptedSecretImportEnvelope copy = new EncryptedSecretImportEnvelope();
        copy.setSchemaVersion(source.getSchemaVersion());
        copy.setAttemptId(source.getAttemptId());
        copy.setItemId(itemId);
        copy.setNonceBase64(source.getNonceBase64());
        copy.setExpiresAtEpochMs(source.getExpiresAtEpochMs());
        copy.setWrappedKeyBase64(source.getWrappedKeyBase64());
        copy.setCiphertextBase64(source.getCiphertextBase64());
        copy.setConfirmDefault(source.isConfirmDefault());
        return copy;
    }

    private String legacyConfigJson(String id, String name, boolean defaultConfig) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"name\":\"" + name + "\","
                + "\"provider\":\"OPENAI\","
                + "\"model\":\"gpt-4o\","
                + "\"apiKey\":\"__API_KEY__\","
                + "\"baseUrl\":\"https://api.openai.com/v1\","
                + "\"enabled\":true,"
                + "\"defaultConfig\":" + defaultConfig
                + "}";
    }

    private String envelopeToJson(EncryptedSecretImportEnvelope envelope) {
        return "{"
                + "\"schemaVersion\":" + envelope.getSchemaVersion() + ","
                + "\"attemptId\":\"" + envelope.getAttemptId() + "\","
                + "\"itemId\":\"" + envelope.getItemId() + "\","
                + "\"nonceBase64\":\"" + envelope.getNonceBase64() + "\","
                + "\"expiresAtEpochMs\":" + envelope.getExpiresAtEpochMs() + ","
                + "\"wrappedKeyBase64\":\"" + envelope.getWrappedKeyBase64() + "\","
                + "\"ciphertextBase64\":\"" + envelope.getCiphertextBase64() + "\","
                + "\"confirmDefault\":" + envelope.isConfirmDefault()
                + "}";
    }

    private static final class RecordingPort implements SecretImportModelConfigPort {
        private final List<ImportedApiKeyConfig> writes = new ArrayList<>();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private int failNextWrites;
        private volatile long writeDelayMs;

        @Override
        public MaskedConfigAcknowledgement writeAndReadback(ImportedApiKeyConfig config) {
            writeCalls.incrementAndGet();
            if (writeDelayMs > 0) {
                try {
                    Thread.sleep(writeDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            if (failNextWrites > 0) {
                failNextWrites--;
                throw new IllegalStateException("simulated persistence failure");
            }
            // Copy before the service destroys secret fields on the live object.
            ImportedApiKeyConfig snapshot = new ImportedApiKeyConfig();
            snapshot.setId(config.getId());
            snapshot.setName(config.getName());
            snapshot.setProvider(config.getProvider());
            snapshot.setModel(config.getModel());
            snapshot.setApiKey(config.getApiKey());
            snapshot.setBaseUrl(config.getBaseUrl());
            snapshot.setDefaultConfig(config.getDefaultConfig());
            snapshot.setEnabled(config.getEnabled());
            writes.add(snapshot);
            MaskedConfigAcknowledgement ack = new MaskedConfigAcknowledgement();
            ack.setConfigId(config.getId());
            ack.setName(config.getName());
            ack.setProvider(config.getProvider());
            ack.setModel(config.getModel());
            ack.setHasApiKey(config.getApiKey() != null && !config.getApiKey().isBlank());
            ack.setDefaultConfig(Boolean.TRUE.equals(config.getDefaultConfig()));
            return ack;
        }
    }
}
