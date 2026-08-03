package ai.chat2db.community.jcef.handler.secret;

import ai.chat2db.community.tools.security.secretimport.SecretImportBoundary;
import ai.chat2db.community.tools.security.secretimport.SecretImportBoundaryRegistry;
import ai.chat2db.community.tools.security.secretimport.SecretImportErrorCode;
import ai.chat2db.community.tools.security.secretimport.SecretImportItemResult;
import ai.chat2db.community.tools.security.secretimport.SecretImportLimits;
import ai.chat2db.community.tools.security.secretimport.SecretImportSafety;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.cef.callback.CefQueryCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Early JCEF intercept for encrypted API-key import.
 *
 * <p>Must run before generic ConsoleMessage retention, request logging of bodies,
 * bridge.doController, and generic exception formatting that can echo request payloads.
 */
public final class SecretImportQueryInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SecretImportQueryInterceptor.class);

    private SecretImportQueryInterceptor() {
    }

    /**
     * @return true when the raw query was handled entirely inside the secret boundary
     */
    public static boolean tryHandle(String rawData, CefQueryCallback callback) {
        if (SecretImportLimits.exceeds(rawData, SecretImportLimits.MAX_RAW_QUERY_CHARS)) {
            // Treat oversized payloads as secret-import defense when they might be intended for this path.
            // We only claim the request when peek of a truncated prefix indicates the secret-import action,
            // otherwise oversized non-secret traffic must still fall through... but we cannot safely parse
            // a multi-MB body. Reject only if the prefix suggests secret-import.
            if (looksLikeSecretImportPrefix(rawData)) {
                respondSafe(callback, null, null, null, null,
                        SecretImportItemResult.failed(null, null, SecretImportErrorCode.PAYLOAD_TOO_LARGE).toSafeMap());
                return true;
            }
            return false;
        }

        ParsedAction action = peekAction(rawData);
        if (action == null || !SecretImportBoundary.isSecretImportAction(action.requestUrl)) {
            return false;
        }

        // Never log rawData / message body. Only log sanitized routing fields.
        log.info("Java received a secret-import query, request URL: {}, UUID: {}",
                SecretImportSafety.safeId(action.requestUrl),
                SecretImportSafety.safeId(action.uuid));

        Map<String, Object> payload;
        if (SecretImportLimits.exceeds(action.messageBody, SecretImportLimits.MAX_BODY_CHARS)
                || SecretImportLimits.exceeds(action.requestUrl, SecretImportLimits.MAX_ID_CHARS * 4)
                || SecretImportLimits.exceeds(action.uuid, SecretImportLimits.MAX_ID_CHARS)) {
            payload = SecretImportItemResult.failed(null, null, SecretImportErrorCode.PAYLOAD_TOO_LARGE).toSafeMap();
        } else if (!SecretImportBoundaryRegistry.isReady()) {
            payload = SecretImportItemResult.failed(null, null, SecretImportErrorCode.BACKEND_NOT_READY).toSafeMap();
        } else {
            SecretImportBoundary boundary = SecretImportBoundaryRegistry.getBoundary();
            String body = action.messageBody == null ? "" : action.messageBody;
            payload = boundary.handleRawRequest(action.requestUrl, action.method, body);
        }

        respondSafe(callback, action.uuid, action.actionType, action.requestUrl, action.method, payload);
        return true;
    }

    private static void respondSafe(CefQueryCallback callback,
                                    String uuid,
                                    String actionType,
                                    String requestUrl,
                                    String method,
                                    Map<String, Object> payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("uuid", uuid);
        response.put("actionType", actionType);
        response.put("requestUrl", requestUrl);
        response.put("method", method);
        // Always use a successful generic transport envelope. Item-level failure remains
        // inside the safe data payload so the generic error path never retains or renders
        // the encrypted request body.
        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("success", true);
        transport.put("data", payload);
        response.put("message", transport);

        try {
            String json = JSON.toJSONString(response);
            callback.success(new String(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            log.warn("secret-import response serialization failed for action {}",
                    SecretImportSafety.safeId(requestUrl));
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("uuid", uuid);
            fallback.put("requestUrl", requestUrl);
            Map<String, Object> fallbackTransport = new LinkedHashMap<>();
            fallbackTransport.put("success", true);
            fallbackTransport.put("data", SecretImportItemResult.failed(
                    null, null, SecretImportErrorCode.INVALID_ENVELOPE).toSafeMap());
            fallback.put("message", fallbackTransport);
            callback.success(JSON.toJSONString(fallback));
        }
    }

    /**
     * Cheap prefix check without full parse — used only for oversized raw payloads.
     */
    static boolean looksLikeSecretImportPrefix(String rawData) {
        if (rawData == null) {
            return false;
        }
        int sampleLen = Math.min(rawData.length(), 512);
        String sample = rawData.substring(0, sampleLen);
        return sample.contains("api/ai/secret-import/");
    }

    /**
     * Peek only routing fields from the raw query. Does not retain the body beyond this call stack.
     */
    static ParsedAction peekAction(String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(rawData);
            if (json == null) {
                return null;
            }
            String requestUrl = json.getString("requestUrl");
            if (requestUrl == null) {
                return null;
            }
            ParsedAction action = new ParsedAction();
            action.requestUrl = requestUrl;
            action.method = json.getString("method");
            if (action.method == null) {
                action.method = "post";
            } else {
                action.method = action.method.toLowerCase(Locale.ROOT);
            }
            action.uuid = json.getString("uuid");
            action.actionType = json.getString("actionType");
            Object message = json.get("message");
            if (message == null) {
                action.messageBody = "";
            } else if (message instanceof String) {
                action.messageBody = (String) message;
            } else {
                action.messageBody = JSON.toJSONString(message);
            }
            return action;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static final class ParsedAction {
        private String uuid;
        private String actionType;
        private String requestUrl;
        private String method;
        private String messageBody;
    }
}
