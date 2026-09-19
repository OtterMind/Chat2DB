package ai.chat2db.community.jcef.update;

import org.cef.OS;

import java.io.IOException;
import java.util.List;

/**
 * Restart and exit support shared by the desktop application and the desktop
 * updater. The restart is scheduled once per process so a repeated request
 * cannot start a second application instance.
 */
public final class DesktopRestartSupport {

    private static final RestartCoordinator RESTART_COORDINATOR = new RestartCoordinator();

    private DesktopRestartSupport() {
    }

    public static boolean prepareRestart() throws IOException {
        ProcessHandle currentProcess = ProcessHandle.current();
        ProcessHandle.Info info = currentProcess.info();
        String launcherPath = info.command().orElseThrow(() -> new IllegalStateException("Cannot find launcher path"));
        String[] appArgs = info.arguments().orElse(new String[0]);
        List<String> command = RestartCommandFactory.build(
                OS.isWindows(),
                OS.isMacintosh(),
                currentProcess.pid(),
                launcherPath,
                appArgs
        );
        return RESTART_COORDINATOR.prepare(() -> new ProcessBuilder(command).start());
    }

    public static void exitCurrentProcessAfterResponse() {
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
