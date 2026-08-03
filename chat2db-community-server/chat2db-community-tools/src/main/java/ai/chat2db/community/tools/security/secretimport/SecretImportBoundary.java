package ai.chat2db.community.tools.security.secretimport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Dedicated secret-import boundary API for JCEF early intercept.
 * Never logs request bodies and only returns safe maps.
 */
public final class SecretImportBoundary {

    public static final String ACTION_PREFIX = "api/ai/secret-import/";
    public static final String ACTION_START = "api/ai/secret-import/start";
    public static final String ACTION_IMPORT_ITEM = "api/ai/secret-import/item";
    public static final String ACTION_CANCEL = "api/ai/secret-import/cancel";
    public static final String ACTION_COMPLETE = "api/ai/secret-import/complete";

    private final EncryptedApiKeyImportService service;

    public SecretImportBoundary(EncryptedApiKeyImportService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public static boolean isSecretImportAction(String requestUrl) {
        if (requestUrl == null) {
            return false;
        }
        String normalized = requestUrl.startsWith("/") ? requestUrl.substring(1) : requestUrl;
        return normalized.startsWith(ACTION_PREFIX);
    }

    public Map<String, Object> handleRawRequest(String requestUrl, String method, String rawBody) {
        String action = normalizeAction(requestUrl);
        String httpMethod = method == null ? "post" : method.toLowerCase(Locale.ROOT);
        if (!"post".equals(httpMethod)) {
            return failed(null, null, SecretImportErrorCode.INVALID_ENVELOPE);
        }
        if (SecretImportLimits.exceeds(rawBody, SecretImportLimits.MAX_BODY_CHARS)) {
            return failed(null, null, SecretImportErrorCode.PAYLOAD_TOO_LARGE);
        }

        try {
            if (ACTION_START.equals(action)) {
                return service.startAttempt().toSafeMap();
            }
            if (ACTION_CANCEL.equals(action)) {
                String attemptId = readAttemptId(rawBody);
                service.cancelAttempt(attemptId);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("attemptId", attemptId);
                response.put("status", "CANCELLED");
                return response;
            }
            if (ACTION_COMPLETE.equals(action)) {
                String attemptId = readAttemptId(rawBody);
                service.completeAttempt(attemptId);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("attemptId", attemptId);
                response.put("status", "COMPLETED");
                return response;
            }
            if (ACTION_IMPORT_ITEM.equals(action)) {
                EncryptedSecretImportEnvelope envelope = parseEnvelope(rawBody);
                return service.importItem(envelope).toSafeMap();
            }
            return failed(null, null, SecretImportErrorCode.INVALID_ENVELOPE);
        } catch (RuntimeException exception) {
            // Swallow all detail — never rethrow envelope content into generic JCEF error paths.
            return failed(null, null, SecretImportErrorCode.INVALID_ENVELOPE);
        }
    }

    private EncryptedSecretImportEnvelope parseEnvelope(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        // Body length already enforced in handleRawRequest; never allocate oversized fields here.
        JSONObject json = JSON.parseObject(rawBody);
        if (json == null) {
            return null;
        }
        EncryptedSecretImportEnvelope envelope = new EncryptedSecretImportEnvelope();
        envelope.setSchemaVersion(json.getIntValue("schemaVersion"));
        envelope.setAttemptId(json.getString("attemptId"));
        envelope.setItemId(json.getString("itemId"));
        envelope.setNonceBase64(json.getString("nonceBase64"));
        envelope.setExpiresAtEpochMs(json.getLongValue("expiresAtEpochMs"));
        envelope.setWrappedKeyBase64(json.getString("wrappedKeyBase64"));
        envelope.setCiphertextBase64(json.getString("ciphertextBase64"));
        envelope.setConfirmDefault(json.getBooleanValue("confirmDefault"));
        return envelope;
    }

    private String readAttemptId(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        if (SecretImportLimits.exceeds(rawBody, SecretImportLimits.MAX_BODY_CHARS)) {
            return null;
        }
        JSONObject json = JSON.parseObject(rawBody);
        String attemptId = json == null ? null : json.getString("attemptId");
        if (SecretImportLimits.exceeds(attemptId, SecretImportLimits.MAX_ID_CHARS)) {
            return null;
        }
        return attemptId;
    }

    private static String normalizeAction(String requestUrl) {
        if (requestUrl == null) {
            return "";
        }
        return requestUrl.startsWith("/") ? requestUrl.substring(1) : requestUrl;
    }

    private static Map<String, Object> failed(String attemptId, String itemId, SecretImportErrorCode code) {
        return SecretImportItemResult.failed(attemptId, itemId, code).toSafeMap();
    }
}
