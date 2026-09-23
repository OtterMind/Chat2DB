package ai.chat2db.community.updater.v2.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryPayloadsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void eventPayloadCarriesFixedEnvelopeAndCustomData() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", "Pro");
        data.put("trigger", "startup");

        JsonNode body = OBJECT_MAPPER.readTree(TelemetryPayloads.event("zh-CN", "pro", data));

        assertEquals(2, body.size());
        assertEquals("event", body.path("type").asText());
        JsonNode payload = body.path("payload");
        assertEquals(TelemetryConfig.WEBSITE_ID, payload.path("website").asText());
        assertEquals(TelemetryConfig.HOSTNAME, payload.path("hostname").asText());
        assertEquals(TelemetryConfig.PAGE_URL, payload.path("url").asText());
        assertEquals("zh-CN", payload.path("language").asText());
        assertEquals(TelemetryConfig.EVENT_NAME, payload.path("name").asText());
        assertEquals("pro", payload.path("tag").asText());
        assertEquals("Pro", payload.path("data").path("product").asText());
        assertEquals("startup", payload.path("data").path("trigger").asText());
    }

    @Test
    void pageViewPayloadHasNoEventName() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", "Community");

        JsonNode body = OBJECT_MAPPER.readTree(TelemetryPayloads.pageView("zh-CN", "community", data));

        assertEquals("event", body.path("type").asText());
        JsonNode payload = body.path("payload");
        assertFalse(payload.has("name"));
        assertEquals(TelemetryConfig.PAGE_URL, payload.path("url").asText());
        assertEquals("community", payload.path("tag").asText());
        assertEquals("Community", payload.path("data").path("product").asText());
    }

    @Test
    void eventWithoutTagOmitsTheTagField() throws Exception {
        JsonNode payload = OBJECT_MAPPER.readTree(TelemetryPayloads.event("zh-CN", "", new LinkedHashMap<>()))
            .path("payload");

        assertFalse(payload.has("tag"));
    }

    @Test
    void identifyPayloadUsesDistinctIdWithoutEventName() throws Exception {
        Map<String, Object> deviceData = new LinkedHashMap<>();
        deviceData.put("product", "Local");

        JsonNode body = OBJECT_MAPPER.readTree(TelemetryPayloads.identify("d1_abc", "zh-CN", deviceData));

        assertEquals("identify", body.path("type").asText());
        JsonNode payload = body.path("payload");
        assertFalse(payload.has("name"));
        assertEquals("zh-CN", payload.path("language").asText());
        assertEquals("d1_abc", payload.path("data").path("id").asText());
        assertEquals("Local", payload.path("data").path("product").asText());
    }

    @Test
    void readsCacheTokenFromResponse() {
        assertEquals("token-1", TelemetryPayloads.cacheFromResponse("{\"cache\":\"token-1\",\"sessionId\":\"s\"}"));
        assertEquals("", TelemetryPayloads.cacheFromResponse("{}"));
        assertEquals("", TelemetryPayloads.cacheFromResponse("not-json"));
        assertEquals("", TelemetryPayloads.cacheFromResponse(null));
    }

    @Test
    void userAgentStaysParseableAndKeepsTheProductWithoutVersion() {
        String macOS = TelemetryConfig.userAgent("Pro", "macOS");
        String windows = TelemetryConfig.userAgent("Community", "Windows");
        String linux = TelemetryConfig.userAgent("Local", "Linux");

        assertTrue(macOS.startsWith("Mozilla/5.0 (Macintosh;"), macOS);
        assertTrue(macOS.contains("Chat2DB-Pro/1.0"));
        assertTrue(windows.startsWith("Mozilla/5.0 (Windows NT"), windows);
        assertTrue(windows.contains("Chat2DB-Community/1.0"));
        assertTrue(linux.startsWith("Mozilla/5.0 (X11; Linux"), linux);
        assertTrue(linux.contains("Chat2DB-Local/1.0"));
    }
}
