package ai.chat2db.community.jcef.update;

import org.cef.OS;

import java.io.IOException;
import java.util.List;

/** Owns process restart and delayed exit mechanics for the desktop runtime. */
final class DesktopRestartController {

    private final RestartCoordinator restartCoordinator;

    DesktopRestartController(RestartCoordinator restartCoordinator) {
        this.restartCoordinator = restartCoordinator;
    }

    void restartApplication() throws IOException {
        if (prepareRestart()) {
            System.exit(0);
        }
    }

    boolean prepareRestart() throws IOException {
        ProcessHandle currentProcess = ProcessHandle.current();
        ProcessHandle.Info info = currentProcess.info();
        String launcherPath = info.command().orElseThrow(() -> new IllegalStateException("Cannot find launcher path"));
        String[] appArgs = info.arguments().orElse(new String[0]);
        List<String> command = RestartCommandFactory.build(OS.isWindows(), OS.isMacintosh(), currentProcess.pid(),
                launcherPath, appArgs);
        return restartCoordinator.prepare(() -> new ProcessBuilder(command).start());
    }

    void exitAfterResponse() {
        Thread exitThread = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "chat2db-restart-exit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

}
