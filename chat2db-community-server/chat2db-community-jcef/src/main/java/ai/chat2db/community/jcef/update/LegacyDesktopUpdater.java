package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.OS;

import java.nio.file.Path;
import java.util.Map;

final class LegacyDesktopUpdater implements IDesktopUpdater {

    @Override
    public DesktopUpdateCheckResult appCheckUpdate() {
        Updater.CheckResult result = Updater.getInstance().appCheckUpdate();
        String version = result.isNeedsUpdate() && result.getRemoteMetadata() != null
            ? result.getRemoteMetadata().getVersion()
            : "";
        return new DesktopUpdateCheckResult(result.isNeedsUpdate(), version);
    }

    @Override
    public boolean triggerDownload(ConsoleResult consoleResult) throws Exception {
        Map<String, Path> files = Updater.getInstance().triggerDownload(consoleResult);
        return files != null;
    }

    @Override
    public boolean triggerInstallation() {
        Updater updater = Updater.getInstance();
        if (OS.isWindows()) {
            updater.triggerInstallationWithAuxiliaryProcess();
            return true;
        }
        return updater.triggerInstallation();
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
