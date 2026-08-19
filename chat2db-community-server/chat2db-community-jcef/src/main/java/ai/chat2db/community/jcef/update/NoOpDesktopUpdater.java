package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;

final class NoOpDesktopUpdater implements IDesktopUpdater {

    @Override
    public DesktopUpdateCheckResult appCheckUpdate() {
        return DesktopUpdateCheckResult.notAvailable();
    }

    @Override
    public boolean triggerDownload(ConsoleResult consoleResult) {
        return false;
    }

    @Override
    public boolean triggerInstallation() {
        return false;
    }

    @Override
    public boolean prepareRestart() {
        return false;
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
    }
}
