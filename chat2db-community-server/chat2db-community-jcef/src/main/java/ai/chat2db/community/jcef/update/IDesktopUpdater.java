package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.console.ConsoleResult;

public interface IDesktopUpdater {

    DesktopUpdateCheckResult appCheckUpdate();

    boolean triggerDownload(ConsoleResult consoleResult) throws Exception;

    boolean triggerInstallation() throws Exception;

    boolean prepareRestart() throws Exception;

    void exitCurrentProcessAfterResponse();

    default boolean setBetaEnabled(boolean enabled) {
        return false;
    }

    default boolean isBetaEnabled() {
        return false;
    }

    default DesktopUpdateRecoveryStatus recoveryStatus() {
        return DesktopUpdateRecoveryStatus.none();
    }

    default boolean openRecoveryLog() {
        return false;
    }
}
