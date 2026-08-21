package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
final class GitHubReleaseDesktopUpdater implements IDesktopUpdater {

    interface Runtime {
        String currentVersion() throws Exception;

        Optional<ReleaseInstaller> findLatest(String currentVersion) throws Exception;

        Path download(ReleaseInstaller release, DownloadProgress progress) throws Exception;

        boolean launchInstaller(ReleaseInstaller release, Path installer, long parentPid) throws Exception;
    }

    @FunctionalInterface
    interface DownloadProgress {
        void update(long downloadedBytes, long totalBytes);
    }

    @FunctionalInterface
    interface ProgressEmitter {
        void emit(int progress, String status, ConsoleResult result);
    }

    enum InstallerKind {
        WINDOWS_MSI,
        MAC_DMG,
        LINUX_DEB,
        LINUX_RPM,
        LINUX_APPIMAGE
    }

    record ReleaseInstaller(
            String version,
            URI releasePage,
            String assetName,
            URI downloadUri,
            long size,
            String sha256,
            InstallerKind kind
    ) {
    }

    private static final long MIN_PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final Runtime runtime;
    private final ProgressEmitter progressEmitter;
    private final boolean releaseRuntime;
    private final RestartCoordinator restartCoordinator = new RestartCoordinator();

    private ReleaseInstaller availableRelease;
    private ReleaseInstaller downloadedRelease;
    private Path downloadedInstaller;
    private boolean installationReady;
    private int lastProgress = -1;
    private long lastProgressNanos;

    GitHubReleaseDesktopUpdater() {
        this(new GitHubReleaseUpdateRuntime(), GitHubReleaseDesktopUpdater::emitJcefProgress, ConfigUtils.isRelease());
    }

    GitHubReleaseDesktopUpdater(Runtime runtime, ProgressEmitter progressEmitter, boolean releaseRuntime) {
        this.runtime = runtime;
        this.progressEmitter = progressEmitter;
        this.releaseRuntime = releaseRuntime;
    }

    @Override
    public synchronized DesktopUpdateCheckResult appCheckUpdate() {
        if (!releaseRuntime) {
            return DesktopUpdateCheckResult.notAvailable();
        }
        try {
            String currentVersion = runtime.currentVersion();
            Optional<ReleaseInstaller> latest = runtime.findLatest(currentVersion);
            if (latest.isEmpty()) {
                clearAvailableUpdate();
                return DesktopUpdateCheckResult.notAvailable();
            }
            availableRelease = latest.get();
            if (downloadedRelease != null && !downloadedRelease.version().equals(availableRelease.version())) {
                discardDownloadedInstaller();
            }
            return new DesktopUpdateCheckResult(true, availableRelease.version());
        } catch (Exception exception) {
            log.warn("Community GitHub Release update check failed", exception);
            clearAvailableUpdate();
            return DesktopUpdateCheckResult.failed();
        }
    }

    @Override
    public synchronized boolean triggerDownload(ConsoleResult consoleResult) throws Exception {
        if (!releaseRuntime || availableRelease == null) {
            return false;
        }
        discardDownloadedInstaller();
        lastProgress = -1;
        lastProgressNanos = 0L;
        ReleaseInstaller release = availableRelease;
        Path installer = runtime.download(release, (downloadedBytes, totalBytes) ->
                reportDownloadProgress(downloadedBytes, totalBytes, consoleResult));
        if (installer == null || !Files.isRegularFile(installer)) {
            return false;
        }
        downloadedRelease = release;
        downloadedInstaller = installer;
        progressEmitter.emit(100, UpdatedStatus.Updated.getName(), consoleResult);
        return true;
    }

    @Override
    public synchronized boolean triggerInstallation() {
        installationReady = releaseRuntime
                && downloadedRelease != null
                && downloadedInstaller != null
                && Files.isRegularFile(downloadedInstaller);
        return installationReady;
    }

    @Override
    public synchronized boolean prepareRestart() throws Exception {
        if (installationReady) {
            boolean launched = runtime.launchInstaller(
                    downloadedRelease,
                    downloadedInstaller,
                    ProcessHandle.current().pid()
            );
            if (launched) {
                installationReady = false;
            }
            return launched;
        }
        ProcessHandle currentProcess = ProcessHandle.current();
        ProcessHandle.Info info = currentProcess.info();
        String launcherPath = info.command()
                .orElseThrow(() -> new IllegalStateException("Cannot find launcher path"));
        String[] appArgs = info.arguments().orElse(new String[0]);
        List<String> command = RestartCommandFactory.build(
                OS.isWindows(),
                OS.isMacintosh(),
                currentProcess.pid(),
                launcherPath,
                appArgs
        );
        return restartCoordinator.prepare(() -> new ProcessBuilder(command).start());
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
        Thread exitThread = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "chat2db-update-exit");
        exitThread.setDaemon(false);
        exitThread.start();
    }

    private void reportDownloadProgress(long downloadedBytes, long totalBytes, ConsoleResult consoleResult) {
        if (totalBytes <= 0) {
            return;
        }
        int progress = (int) Math.min(99L, (downloadedBytes * 100L) / totalBytes);
        long now = System.nanoTime();
        if (progress <= lastProgress
                || (lastProgressNanos != 0L && now - lastProgressNanos < MIN_PROGRESS_INTERVAL_NANOS)) {
            return;
        }
        lastProgress = progress;
        lastProgressNanos = now;
        progressEmitter.emit(progress, UpdatedStatus.Updating.getName(), consoleResult);
    }

    private void clearAvailableUpdate() {
        availableRelease = null;
        discardDownloadedInstaller();
    }

    private void discardDownloadedInstaller() {
        installationReady = false;
        downloadedRelease = null;
        Path installer = downloadedInstaller;
        downloadedInstaller = null;
        if (installer == null) {
            return;
        }
        try {
            Files.deleteIfExists(installer);
        } catch (Exception exception) {
            log.debug("Could not delete stale Community installer {}", installer, exception);
        }
    }

    private static void emitJcefProgress(int progress, String status, ConsoleResult result) {
        result.setMessage(Map.of("progress", progress, "status", status));
        result.setActionType(ActionTypeEnum.UPDATE_PROGRESS.getName());
        String payload = JSON.toJSONString(result);
        CallJsFunctionUtil.callHandleJavaMessage(JcefContext.getInstance().getBrowser_(), payload);
        log.info("Community installer download progress: {}% ({})", progress, status);
    }
}
