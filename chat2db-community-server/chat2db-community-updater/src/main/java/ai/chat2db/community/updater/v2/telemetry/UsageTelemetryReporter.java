package ai.chat2db.community.updater.v2.telemetry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends one desktop usage event per update check, plus one identify per process so Umami can link the
 * session to the device.
 *
 * <p>Reporting is asynchronous and best effort: failures are swallowed, there is no retry, and the next
 * update check simply reports again.</p>
 */
public final class UsageTelemetryReporter implements TelemetrySink {

    /** Transport seam so tests never touch the network. */
    @FunctionalInterface
    public interface Poster {
        Response post(String url, Map<String, String> headers, String body) throws Exception;
    }

    public record Response(int statusCode, String body) {
    }

    public record Report(String productLabel, String platformLabel, String language, Map<String, Object> data) {
    }

    private static final String CACHE_HEADER = "x-umami-cache";

    private final TelemetryStore store;

    private final Poster poster;

    private final ExecutorService executor;

    private final AtomicBoolean identified = new AtomicBoolean();

    public UsageTelemetryReporter(TelemetryStore store, Poster poster) {
        this.store = store;
        this.poster = poster;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "chat2db-usage-telemetry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** HTTP transport used in production. */
    public static Poster httpPoster() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TelemetryConfig.REQUEST_TIMEOUT)
            .build();
        return (url, headers, body) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TelemetryConfig.REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        };
    }

    @Override
    public void report(Report report) {
        executor.execute(() -> send(report));
    }

    /** Synchronous send, package private so tests can exercise it without the executor. */
    void send(Report report) {
        try {
            String deviceId = store.deviceId();
            if (identified.compareAndSet(false, true)) {
                try {
                    post(TelemetryPayloads.identify(deviceId, report.language(), identityData(report)), report);
                } catch (Exception exception) {
                    // A failed identify has to be retried, otherwise this process never links its device.
                    identified.set(false);
                    throw exception;
                }
            }
            String tag = productTag(report);
            post(TelemetryPayloads.pageView(report.language(), tag, report.data()), report);
            post(TelemetryPayloads.event(report.language(), tag, report.data()), report);
        } catch (Exception ignored) {
            // Usage reporting must never surface an error to the application.
        }
    }

    private void post(String body, Report report) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", TelemetryConfig.userAgent(report.productLabel(), report.platformLabel()));
        String cache = store.cache();
        if (!cache.isBlank()) {
            headers.put(CACHE_HEADER, cache);
        }
        Response response = poster.post(TelemetryConfig.ENDPOINT, headers, body);
        if (response != null && response.body() != null) {
            store.saveCache(TelemetryPayloads.cacheFromResponse(response.body()));
        }
    }

    /** Umami tags are filterable, so the product travels as a tag as well as inside the event data. */
    private String productTag(Report report) {
        return report.productLabel() == null ? "" : report.productLabel().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> identityData(Report report) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : new String[] {"product", "version", "platform", "arch"}) {
            Object value = report.data().get(key);
            if (value != null) {
                data.put(key, value);
            }
        }
        return data;
    }
}
