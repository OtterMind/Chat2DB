package ai.chat2db.community.updater.v2.telemetry;

import ai.chat2db.community.updater.v2.enums.UpdateArchitectureEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import ai.chat2db.community.updater.v2.runtime.RuntimePlatformDetector;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Desktop usage reporting attached to update checks.
 *
 * <p>Community, Pro and Local share this entry point: the runtime mode only changes the reported product
 * label. Nothing here may delay or fail an update check, and nothing is reported when the product was
 * activated offline.</p>
 */
public final class DesktopUsageTelemetry {

    private static final DesktopUsageTelemetry INSTANCE = new DesktopUsageTelemetry(
        new UsageTelemetryReporter(new TelemetryStore(), UsageTelemetryReporter.httpPoster()));

    private final TelemetrySink telemetrySink;

    DesktopUsageTelemetry(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    public static DesktopUsageTelemetry get() {
        return INSTANCE;
    }

    /**
     * @param offlineActivation true when the license was activated offline; such installs are not reported
     */
    public void reportCheck(TelemetryTrigger trigger, boolean offlineActivation, String version,
        boolean betaChannel, boolean updateAvailable, String latestVersion) {
        if (offlineActivation) {
            return;
        }
        try {
            UpdatePlatformEnum platform = RuntimePlatformDetector.platform();
            String platformLabel = platformLabel(platform);
            String productLabel = productLabel(System.getProperty("chat2db.runtime.mode", ""));
            Locale locale = Locale.getDefault();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("product", productLabel);
            if (version != null && !version.isBlank()) {
                data.put("version", version);
            }
            data.put("channel", betaChannel ? "BETA" : "STABLE");
            data.put("platform", platformLabel);
            data.put("arch", archLabel(RuntimePlatformDetector.architecture()));
            String osVersion = System.getProperty("os.version", "").trim();
            data.put("osVersion", osVersion.isEmpty() ? platformLabel : platformLabel + " " + osVersion);
            data.put("locale", locale.toLanguageTag());
            data.put("timezone", ZoneId.systemDefault().getId());
            if (!locale.getCountry().isBlank()) {
                data.put("region", locale.getCountry());
            }
            data.put("activityDate", LocalDate.now().toString());
            data.put("activityHour", LocalTime.now().getHour());
            data.put("trigger", trigger.wireName());
            data.put("result", updateAvailable ? "available" : "no_update");
            if (updateAvailable && latestVersion != null && !latestVersion.isBlank()) {
                data.put("latestVersion", latestVersion);
            }
            telemetrySink.report(new UsageTelemetryReporter.Report(productLabel, platformLabel,
                locale.toLanguageTag(), data));
        } catch (Exception ignored) {
            // Usage reporting is best effort and must never disturb the update check.
        }
    }

    static String productLabel(String runtimeMode) {
        String normalized = runtimeMode == null ? "" : runtimeMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "community" -> "Community";
            case "pro" -> "Pro";
            case "local" -> "Local";
            case "" -> "Unknown";
            default -> Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
        };
    }

    static String platformLabel(UpdatePlatformEnum platform) {
        return switch (platform) {
            case MACOS -> "macOS";
            case WINDOWS -> "Windows";
            case LINUX -> "Linux";
        };
    }

    static String archLabel(UpdateArchitectureEnum architecture) {
        return switch (architecture) {
            case ARM64 -> "arm64";
            case X64 -> "x64";
        };
    }
}
