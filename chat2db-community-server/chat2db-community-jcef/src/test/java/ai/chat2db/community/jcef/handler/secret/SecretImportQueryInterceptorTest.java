package ai.chat2db.community.jcef.handler.secret;

import ai.chat2db.community.tools.security.secretimport.EncryptedApiKeyImportService;
import ai.chat2db.community.tools.security.secretimport.EncryptedSecretImportEnvelope;
import ai.chat2db.community.tools.security.secretimport.ImportedApiKeyConfig;
import ai.chat2db.community.tools.security.secretimport.MaskedConfigAcknowledgement;
import ai.chat2db.community.tools.security.secretimport.SecretImportAttemptStart;
import ai.chat2db.community.tools.security.secretimport.SecretImportBoundary;
import ai.chat2db.community.tools.security.secretimport.SecretImportBoundaryRegistry;
import ai.chat2db.community.tools.security.secretimport.SecretImportLimits;
import ai.chat2db.community.tools.security.secretimport.SecretImportModelConfigPort;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretImportQueryInterceptorTest {

    private static final String CANARY = "sk-canary-JCEF-T5-9c1d-DO-NOT-LEAK";

    private EncryptedApiKeyImportService service;
    private final List<String> successResponses = new ArrayList<>();
    private final List<String> failureResponses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EncryptedApiKeyImportService(new Port());
        SecretImportBoundaryRegistry.register(new SecretImportBoundary(service));
        successResponses.clear();
        failureResponses.clear();
    }

    @AfterEach
    void tearDown() {
        service.destroyAll();
        SecretImportBoundaryRegistry.clear();
    }

    @Test
    void ignoresNonSecretActions() {
        boolean handled = SecretImportQueryInterceptor.tryHandle(
                "{\"requestUrl\":\"api/other\",\"method\":\"post\",\"message\":\"{}\"}",
                callback());
        assertFalse(handled);
        assertTrue(successResponses.isEmpty());
    }

    @Test
    void handlesImportItemWithoutEchoingCanaryOrEnvelopeSecrets() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(start, "item-jcef", CANARY);
        String raw = rawQuery(SecretImportBoundary.ACTION_IMPORT_ITEM, envelopeJson(envelope));

        boolean handled = SecretImportQueryInterceptor.tryHandle(raw, callback());

        assertTrue(handled);
        assertEquals(1, successResponses.size());
        String response = successResponses.get(0);
        assertTrue(response.contains("SUCCEEDED") || response.contains("ALREADY_IMPORTED"));
        assertFalse(response.contains(CANARY));
        assertFalse(response.contains(envelope.getCiphertextBase64()));
        assertFalse(response.contains(envelope.getWrappedKeyBase64()));
        assertFalse(response.contains("BEGIN PRIVATE KEY"));
    }

    @Test
    void returnsBackendNotReadyWithoutGenericExceptionPathWhenUnregistered() {
        SecretImportBoundaryRegistry.clear();
        String raw = rawQuery(SecretImportBoundary.ACTION_IMPORT_ITEM,
                "{\"schemaVersion\":1,\"attemptId\":\"a\",\"itemId\":\"i\","
                        + "\"nonceBase64\":\"n\",\"expiresAtEpochMs\":1,"
                        + "\"wrappedKeyBase64\":\"d\",\"ciphertextBase64\":\"d\"}");

        boolean handled = SecretImportQueryInterceptor.tryHandle(raw, callback());

        assertTrue(handled);
        assertEquals(1, successResponses.size());
        assertTrue(successResponses.get(0).contains("BACKEND_NOT_READY"));
        assertTrue(failureResponses.isEmpty());
    }

    @Test
    void failedDecryptDoesNotEchoCanaryInCallbackPayload() throws Exception {
        SecretImportAttemptStart start = service.startAttempt();
        EncryptedSecretImportEnvelope envelope = buildEnvelope(start, "item-bad", CANARY);
        envelope.setCiphertextBase64(Base64.getEncoder().encodeToString(new byte[48]));
        String raw = rawQuery(SecretImportBoundary.ACTION_IMPORT_ITEM, envelopeJson(envelope));

        assertTrue(SecretImportQueryInterceptor.tryHandle(raw, callback()));
        String response = successResponses.get(0);
        assertTrue(response.contains("FAILED"));
        assertTrue(response.contains("DECRYPT_FAILED"));
        assertFalse(response.contains(CANARY));
    }

    @Test
    void rejectsOversizedRawSecretImportQuery() {
        String prefix = "{\"requestUrl\":\"api/ai/secret-import/item\",\"method\":\"post\",\"message\":\"";
        String raw = prefix + "x".repeat(SecretImportLimits.MAX_RAW_QUERY_CHARS) + "\"}";
        assertTrue(raw.length() > SecretImportLimits.MAX_RAW_QUERY_CHARS);

        boolean handled = SecretImportQueryInterceptor.tryHandle(raw, callback());

        assertTrue(handled);
        assertEquals(1, successResponses.size());
        assertTrue(successResponses.get(0).contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void completeActionThroughInterceptorDestroysPrivateKey() {
        SecretImportAttemptStart start = service.startAttempt();
        String raw = rawQuery(SecretImportBoundary.ACTION_COMPLETE,
                "{\"attemptId\":\"" + start.getAttemptId() + "\"}");

        assertTrue(SecretImportQueryInterceptor.tryHandle(raw, callback()));
        assertTrue(successResponses.get(0).contains("COMPLETED"));
        assertTrue(service.isPrivateKeyDestroyed(start.getAttemptId()));
        assertTrue(service.isAttemptCompleted(start.getAttemptId()));
    }

    private CefQueryCallback callback() {
        return (CefQueryCallback) Proxy.newProxyInstance(
                CefQueryCallback.class.getClassLoader(),
                new Class<?>[]{CefQueryCallback.class},
                (proxy, method, args) -> {
                    if ("success".equals(method.getName()) && args != null && args.length == 1) {
                        successResponses.add(String.valueOf(args[0]));
                    } else if ("failure".equals(method.getName()) && args != null && args.length >= 2) {
                        failureResponses.add(String.valueOf(args[1]));
                    }
                    return null;
                });
    }

    private static String rawQuery(String requestUrl, String messageJson) {
        // Escape message as a JSON string field (as generic JCEF path often does).
        String escaped = messageJson.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{"
                + "\"uuid\":\"u-1\","
                + "\"actionType\":\"execute\","
                + "\"requestUrl\":\"" + requestUrl + "\","
                + "\"method\":\"post\","
                + "\"message\":\"" + escaped + "\""
                + "}";
    }

    private EncryptedSecretImportEnvelope buildEnvelope(SecretImportAttemptStart start, String itemId, String apiKey)
            throws Exception {
        String payload = "{"
                + "\"id\":\"cfg-jcef\","
                + "\"name\":\"JCEF\","
                + "\"provider\":\"OPENAI\","
                + "\"model\":\"gpt-4o\","
                + "\"apiKey\":\"" + apiKey + "\","
                + "\"enabled\":true,"
                + "\"defaultConfig\":false"
                + "}";
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
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] wrapped = rsa.doFinal(aesKey.getEncoded());

        EncryptedSecretImportEnvelope envelope = new EncryptedSecretImportEnvelope();
        envelope.setSchemaVersion(EncryptedApiKeyImportService.SCHEMA_VERSION);
        envelope.setAttemptId(start.getAttemptId());
        envelope.setItemId(itemId);
        envelope.setNonceBase64(nonceB64);
        envelope.setExpiresAtEpochMs(start.getExpiresAtEpochMs());
        envelope.setWrappedKeyBase64(Base64.getEncoder().encodeToString(wrapped));
        envelope.setCiphertextBase64(Base64.getEncoder().encodeToString(ciphertext));
        envelope.setConfirmDefault(false);
        return envelope;
    }

    private static String envelopeJson(EncryptedSecretImportEnvelope envelope) {
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

    private static final class Port implements SecretImportModelConfigPort {
        @Override
        public MaskedConfigAcknowledgement writeAndReadback(ImportedApiKeyConfig config) {
            MaskedConfigAcknowledgement ack = new MaskedConfigAcknowledgement();
            ack.setConfigId(config.getId());
            ack.setName(config.getName());
            ack.setProvider(config.getProvider());
            ack.setModel(config.getModel());
            ack.setHasApiKey(true);
            ack.setDefaultConfig(Boolean.TRUE.equals(config.getDefaultConfig()));
            return ack;
        }
    }
}
