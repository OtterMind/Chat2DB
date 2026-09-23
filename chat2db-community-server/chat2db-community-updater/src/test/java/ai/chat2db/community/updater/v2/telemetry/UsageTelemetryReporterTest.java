package ai.chat2db.community.updater.v2.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTelemetryReporterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private record Sent(String url, Map<String, String> headers, String body) {
    }

    @Test
    void sendsIdentifyOnceThenEventsAndReusesSessionCache() throws Exception {
        List<Sent> sent = new ArrayList<>();
        TelemetryStore store = new TelemetryStore(temporaryDirectory.resolve("telemetry.json"));
        UsageTelemetryReporter reporter = new UsageTelemetryReporter(store, (url, headers, body) -> {
            sent.add(new Sent(url, headers, body));
            return new UsageTelemetryReporter.Response(200, "{\"cache\":\"cache-1\"}");
        });

        reporter.send(report());
        reporter.send(report());

        assertEquals(5, sent.size(), "identify + page view + event, then page view + event");
        assertEquals(TelemetryConfig.ENDPOINT, sent.get(0).url());
        assertTrue(sent.get(0).headers().get("User-Agent").startsWith("Mozilla/5.0 (Macintosh;"));
        assertTrue(sent.get(0).headers().get("User-Agent").contains("Chat2DB-Pro/1.0"));
        assertEquals("identify", OBJECT_MAPPER.readTree(sent.get(0).body()).path("type").asText());
        JsonNode pageView = OBJECT_MAPPER.readTree(sent.get(1).body());
        assertEquals("event", pageView.path("type").asText());
        assertFalse(pageView.path("payload").has("name"), "the overview metrics need a page view");
        JsonNode event = OBJECT_MAPPER.readTree(sent.get(2).body());
        assertEquals("event", event.path("type").asText());
        assertEquals("pro", event.path("payload").path("tag").asText());
        assertEquals(TelemetryConfig.EVENT_NAME, event.path("payload").path("name").asText());
        assertFalse(sent.get(0).headers().containsKey("x-umami-cache"));
        assertEquals("cache-1", store.cache());
        assertEquals("cache-1", sent.get(2).headers().get("x-umami-cache"));
        assertTrue(store.deviceId().startsWith("d1_") || store.deviceId().startsWith("r1_"));
    }

    @Test
    void identifyIsRetriedAfterAFailedAttempt() throws Exception {
        List<Sent> sent = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        TelemetryStore store = new TelemetryStore(temporaryDirectory.resolve("telemetry.json"));
        UsageTelemetryReporter reporter = new UsageTelemetryReporter(store, (url, headers, body) -> {
            sent.add(new Sent(url, headers, body));
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("offline");
            }
            return new UsageTelemetryReporter.Response(200, "{}");
        });

        reporter.send(report());
        reporter.send(report());

        List<String> types = new ArrayList<>();
        for (Sent request : sent) {
            types.add(OBJECT_MAPPER.readTree(request.body()).path("type").asText());
        }
        assertEquals(List.of("identify", "identify", "event", "event"), types,
            "the next report has to link the device again after a failed identify");
    }

    @Test
    void failingTransportIsSwallowed() {
        UsageTelemetryReporter reporter = new UsageTelemetryReporter(
            new TelemetryStore(temporaryDirectory.resolve("telemetry.json")),
            (url, headers, body) -> {
                throw new IllegalStateException("offline");
            });

        reporter.send(report());

        assertTrue(true);
    }

    private UsageTelemetryReporter.Report report() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", "Pro");
        data.put("version", "5.3.7-beta.2");
        data.put("platform", "macOS");
        data.put("arch", "arm64");
        data.put("trigger", "startup");
        data.put("result", "no_update");
        return new UsageTelemetryReporter.Report("Pro", "macOS", "zh-CN", data);
    }
}
