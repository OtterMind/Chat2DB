package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import java.nio.file.Path;
import java.util.Map;

final class LegacyDesktopUpdater implements IDesktopUpdater {

    @Override
    public DesktopUpdateCheckResult appCheckUpdate() {
        Updater.CheckResult result = Updater.getInstance().appCheckUpdate();
        if (result.isCheckFailed()) {
            return DesktopUpdateCheckResult.failed(result.getReleasePageUrl(), result.getFailureStage(), result.getFailureReason());
        }
        if (!result.isNeedsUpdate()) {
            return DesktopUpdateCheckResult.notAvailable();
        }
        String version = "";
        if (result.getAvailableSnapshot() != null) {
            version = result.getAvailableSnapshot().version();
        }
        return DesktopUpdateCheckResult.available(version,
                result.getReleaseNotes() == null ? "" : result.getReleaseNotes(),
                result.getReleasePageUrl());
    }

    @Override
    public boolean triggerDownload(ConsoleResult consoleResult) throws Exception {
        Map<String, Path> files = Updater.getInstance().triggerDownload(consoleResult);
        return files != null;
    }

    @Override
    public boolean triggerInstallation() {
        Updater updater = Updater.getInstance();
        return org.cef.OS.isWindows()
            ? updater.triggerInstallationWithAuxiliaryProcess()
            : updater.triggerInstallation();
    }

    @Override
    public boolean prepareRestart() throws Exception {
        return Updater.getInstance().prepareRestart();
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
        Updater.getInstance().exitCurrentProcessAfterResponse();
    }
}
