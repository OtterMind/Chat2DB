package ai.chat2db.community.start.ai.subscription.appserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes provider credential material before logging, persistence, or DTO mapping.
 * Chat2DB must never receive or emit OAuth tokens.
 */
public final class SensitivePayloadRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "idtoken",
            "id_token",
            "token",
            "apitoken",
            "api_token",
            "apikey",
            "api_key",
            "authorization",
            "password",
            "secret",
            "clientsecret",
            "client_secret",
            "bearer",
            "credentials",
            "authjson",
            "auth_json"
    );

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._\\-]+");
    private static final Pattern JWT_LIKE = Pattern.compile(
            "\\beyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\b");

    private SensitivePayloadRedactor() {
    }

    public static String redactText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String out = BEARER.matcher(raw).replaceAll("Bearer " + REDACTED);
        out = JWT_LIKE.matcher(out).replaceAll(REDACTED);
        return out;
    }

    public static JsonNode redactTree(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (isSensitiveKey(key)) {
                    copy.set(key, TextNode.valueOf(REDACTED));
                } else {
                    copy.set(key, redactTree(entry.getValue()));
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, redactTree(copy.get(i)));
            }
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(redactText(node.asText()));
        }
        return node;
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (SENSITIVE_KEYS.contains(normalized) || SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
            return true;
        }
        // Catch nested variants like chatgptAccessToken without inventing protocol fields.
        return normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.contains("clientsecret")
                || normalized.endsWith("apikey");
    }
}
