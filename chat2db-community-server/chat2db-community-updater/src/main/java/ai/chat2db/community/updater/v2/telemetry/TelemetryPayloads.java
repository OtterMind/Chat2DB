package ai.chat2db.community.updater.v2.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the Umami {@code /api/send} request bodies.
 *
 * <p>Umami validates the payload against a fixed field list and drops unknown top level fields, so every
 * custom value travels inside {@code payload.data}.</p>
 */
public final class TelemetryPayloads {


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TelemetryPayloads() {
    }

    /**
     * The identify call creates the session, so it carries the language too; later writes do not update
     * the session language.
     */
    public static String identify(String deviceId, String language, Map<String, Object> deviceData)
        throws JsonProcessingException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", deviceId);
        data.putAll(deviceData);
        return body("identify", base(language), data, false);
    }

    /**
     * A page view (no {@code name}). Umami derives the overview metrics - pageviews, visitors, visits -
     * from page views only, so the update check reports one of these in addition to its named event.
     */
    public static String pageView(String language, String tag, Map<String, Object> eventData)
        throws JsonProcessingException {
        Map<String, Object> payload = base(language);
        if (tag != null && !tag.isBlank()) {
            payload.put("tag", tag);
        }
        return body("event", payload, eventData, false);
    }

    public static String event(String language, String tag, Map<String, Object> eventData)
        throws JsonProcessingException {
        Map<String, Object> payload = base(language);
        if (tag != null && !tag.isBlank()) {
            payload.put("tag", tag);
        }
        return body("event", payload, eventData, true);
    }

    /** Reads the session cache token the server returns; empty when the response has none. */
    public static String cacheFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(responseBody);
            return node.path("cache").asText("");
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private static Map<String, Object> base(String language) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("website", TelemetryConfig.WEBSITE_ID);
        payload.put("hostname", TelemetryConfig.HOSTNAME);
        payload.put("url", TelemetryConfig.PAGE_URL);
        if (language != null && !language.isBlank()) {
            payload.put("language", language);
        }
        return payload;
    }

    private static String body(String type, Map<String, Object> payload, Map<String, Object> data, boolean named)
        throws JsonProcessingException {
        if (named) {
            payload.put("name", TelemetryConfig.EVENT_NAME);
        }
        payload.put("data", data);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", type);
        root.put("payload", payload);
        return OBJECT_MAPPER.writeValueAsString(root);
    }
}
