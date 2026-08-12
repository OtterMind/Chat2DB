package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import java.nio.file.Path;
import java.util.Map;

final class LegacyDesktopUpdater implements IDesktopUpdater {

    @Override
    public DesktopUpdateCheckResult appCheckUpdate() {
        Updater.CheckResult result = Updater.getInstance().appCheckUpdate();
        String version = result.isNeedsUpdate() && result.getLatestVersionInfo() != null
            ? result.getLatestVersionInfo().getLatestVersion()
            : "";
        return new DesktopUpdateCheckResult(result.isNeedsUpdate(), version,
            result.getReleaseNotes() == null ? "" : result.getReleaseNotes(), result.isCheckFailed());
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
