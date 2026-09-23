package ai.chat2db.community.updater.v2.telemetry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Fixed Umami endpoint and identifiers for the desktop usage reporting that rides along update checks.
 *
 * <p>The website id is a public value (it is embedded in the published tracking snippet) and is not a
 * secret, so it stays in source instead of being injected at package time.</p>
 */
public final class TelemetryConfig {

    public static final String ENDPOINT = "https://um.ottermind.ai/api/send";

    public static final String WEBSITE_ID = "51e9aab7-a6cd-44fb-8aa3-1db200cfaec7";

    public static final String HOSTNAME = "chat2db-desktop";

    public static final String PAGE_URL = "/update-check";

    public static final String EVENT_NAME = "update_check";

    /**
     * Umami derives the environment from the user agent, so the desktop agent keeps a parseable
     * platform prefix and appends the product token. The version stays fixed on purpose: the agent
     * feeds the session hash, so changing it would split a device into new sessions.
     */
    private static final String AGENT_MACOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chat2DB-%s/1.0";

    private static final String AGENT_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chat2DB-%s/1.0";

    private static final String AGENT_LINUX = "Mozilla/5.0 (X11; Linux x86_64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chat2DB-%s/1.0";

    /** The reporting request must never delay or fail an update check. */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3L);

    public static final String STORE_FILE = "device_id.json";

    /**
     * Config directory of the running product, set by the caller that owns the runtime identity
     * ({@code ConfigUtils.getBasePath() + "/config"}), so the file sits next to the product config.
     */
    private static volatile String configDirectory;

    private TelemetryConfig() {
    }

    public static void configDirectory(String directory) {
        if (directory != null && !directory.isBlank()) {
            configDirectory = directory;
        }
    }

    /**
     * @return the state file inside the product config directory, or {@code null} while the caller has
     *     not provided that directory; nothing is written in that case and the device id stays derived
     *     from the machine
     */
    public static Path storeFile() {
        String directory = configDirectory;
        if (directory == null || directory.isBlank()) {
            return null;
        }
        return Path.of(directory, STORE_FILE);
    }

    public static String userAgent(String productLabel, String platformLabel) {
        String template = switch (platformLabel == null ? "" : platformLabel) {
            case "Windows" -> AGENT_WINDOWS;
            case "Linux" -> AGENT_LINUX;
            default -> AGENT_MACOS;
        };
        return String.format(Locale.ROOT, template, productLabel);
    }
}
