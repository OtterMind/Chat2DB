package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.cef.OS;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** JCEF-facing Community adapter; all update work is delegated to the secure {@link Updater} facade. */
final class CommunityDesktopUpdater implements IDesktopUpdater {

    static final long MIN_CHECK_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);

    private final Updater updater;
    private final BooleanSupplier windowsPlatform;
    private final LongSupplier nanoClock;
    private long lastCheckNanos = Long.MIN_VALUE;
    private DesktopUpdateCheckResult lastCheckResult;

    CommunityDesktopUpdater() {
        this(Updater.getInstance(), OS::isWindows);
    }

    CommunityDesktopUpdater(Updater updater) {
        this(updater, OS::isWindows);
    }

    CommunityDesktopUpdater(Updater updater, BooleanSupplier windowsPlatform) {
        this(updater, windowsPlatform, System::nanoTime);
    }

    CommunityDesktopUpdater(Updater updater, BooleanSupplier windowsPlatform, LongSupplier nanoClock) {
        this.updater = updater;
        this.windowsPlatform = windowsPlatform;
        this.nanoClock = nanoClock;
    }

    @Override
    public synchronized DesktopUpdateCheckResult appCheckUpdate() {
        long now = nanoClock.getAsLong();
        if (lastCheckResult != null && now - lastCheckNanos < MIN_CHECK_INTERVAL_NANOS) {
            return lastCheckResult;
        }
        Updater.CheckResult result = updater.appCheckUpdate();
        if (result.isCheckFailed()) {
            return rememberCheck(DesktopUpdateCheckResult.failed(result.getReleasePageUrl(), result.getFailureStage(), result.getFailureReason()), now);
        }
        if (!result.isNeedsUpdate()) {
            return rememberCheck(DesktopUpdateCheckResult.notAvailable(), now);
        }
        AvailableSnapshot snapshot = result.getAvailableSnapshot();
        return rememberCheck(DesktopUpdateCheckResult.available(snapshot == null ? "" : snapshot.version(), result.getReleaseNotes(),
                result.getReleasePageUrl()), now);
    }

    private DesktopUpdateCheckResult rememberCheck(DesktopUpdateCheckResult result, long now) {
        lastCheckNanos = now;
        lastCheckResult = result;
        return result;
    }

    @Override
    public boolean triggerDownload(ConsoleResult consoleResult) throws Exception {
        if (!Updater.isAutomaticUpdateSupported(ConfigUtils.isRelease(), isWebFrontend(), windowsPlatform.getAsBoolean())) {
            return false;
        }
        Map<String, Path> downloaded = updater.triggerDownload(consoleResult);
        return downloaded != null;
    }

    @Override
    public boolean triggerInstallation() {
        if (!Updater.isAutomaticUpdateSupported(ConfigUtils.isRelease(), isWebFrontend(), windowsPlatform.getAsBoolean())) {
            return false;
        }
        return updater.triggerInstallationWithAuxiliaryProcess();
    }

    @Override
    public boolean prepareRestart() throws Exception {
        return updater.prepareRestart();
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
        updater.exitCurrentProcessAfterResponse();
    }

    private static boolean isWebFrontend() {
        return Boolean.parseBoolean(System.getProperty("chat2db.jcef.web-frontend", "false"));
    }
}
