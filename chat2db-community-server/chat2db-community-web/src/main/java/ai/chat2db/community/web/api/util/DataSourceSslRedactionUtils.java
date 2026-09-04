package ai.chat2db.community.web.api.util;

import java.util.Set;

import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

public final class DataSourceSslRedactionUtils {

    public static final String REDACTED = "[REDACTED]";
    public static final String REDACTED_BODY = "[NON_JSON_BODY_REDACTED]";

    private static final Set<String> TLS_SECRET_FIELDS = Set.of(
            "clientPrivateKeyPem",
            "clientKeyPassword",
            "trustStoreBytes",
            "trustStorePassword",
            "keyStoreBytes",
            "keyStorePassword");

    private DataSourceSslRedactionUtils() {
    }

    public static String redactJsonBody(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        Object parsed;
        try {
            parsed = JSON.parse(body);
        } catch (JSONException | IllegalArgumentException exception) {
            return REDACTED_BODY;
        }
        redactJsonValue(parsed);
        return JSON.toJSONString(parsed, JSONWriter.Feature.WriteNulls);
    }

    public static SSLInfo redactedCopy(SSLInfo ssl) {
        if (ssl == null) {
            return null;
        }
        SSLInfo copy = new SSLInfo();
        copy.setTlsMode(ssl.getTlsMode());
        copy.setCaPem(ssl.getCaPem());
        copy.setClientCertPem(ssl.getClientCertPem());
        copy.setTrustStoreType(ssl.getTrustStoreType());
        copy.setKeyStoreType(ssl.getKeyStoreType());
        return copy;
    }

    private static void redactJsonValue(Object value) {
        if (value instanceof JSONObject object) {
            for (String key : object.keySet()) {
                Object child = object.get(key);
                if (TLS_SECRET_FIELDS.contains(key) && shouldRedact(child)) {
                    object.put(key, REDACTED);
                } else {
                    redactJsonValue(child);
                }
            }
            return;
        }
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                redactJsonValue(item);
            }
        }
    }

    private static boolean shouldRedact(Object value) {
        return value != null && (!(value instanceof String s) || !s.isEmpty());
    }
}
