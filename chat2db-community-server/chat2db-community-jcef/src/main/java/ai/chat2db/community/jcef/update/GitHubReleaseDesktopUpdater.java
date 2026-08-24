package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
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

    record ReleaseInstaller(
            String version,
            String assetName,
            URI downloadUri,
            long size,
            String sha256
    ) {
    }

    private static final long MIN_PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final Runtime runtime;
    private final ProgressEmitter progressEmitter;
    private final boolean releaseRuntime;
    private final UpdateAuditLog auditLog;
    private final RestartCoordinator restartCoordinator = new RestartCoordinator();

    private ReleaseInstaller availableRelease;
    private ReleaseInstaller downloadedRelease;
    private Path downloadedInstaller;
    private boolean installationReady;
    private int lastProgress = -1;
    private long lastProgressNanos;

    GitHubReleaseDesktopUpdater() {
        this(UpdateAuditLog.createDefault());
    }

    private GitHubReleaseDesktopUpdater(UpdateAuditLog auditLog) {
        this(
                new GitHubReleaseUpdateRuntime(auditLog),
                GitHubReleaseDesktopUpdater::emitJcefProgress,
                ConfigUtils.isRelease(),
                auditLog
        );
    }

    GitHubReleaseDesktopUpdater(Runtime runtime, ProgressEmitter progressEmitter, boolean releaseRuntime) {
        this(runtime, progressEmitter, releaseRuntime, UpdateAuditLog.disabled());
    }

    GitHubReleaseDesktopUpdater(
            Runtime runtime,
            ProgressEmitter progressEmitter,
            boolean releaseRuntime,
            UpdateAuditLog auditLog
    ) {
        this.runtime = runtime;
        this.progressEmitter = progressEmitter;
        this.releaseRuntime = releaseRuntime;
        this.auditLog = auditLog;
    }

    @Override
    public synchronized DesktopUpdateCheckResult appCheckUpdate() {
        if (!releaseRuntime) {
            return DesktopUpdateCheckResult.notAvailable();
        }
        try {
            auditLog.begin();
            auditLog.info("CHECK", "checking GitHub latest stable release");
            String currentVersion = runtime.currentVersion();
            auditLog.versions(currentVersion, "");
            auditLog.info("CHECK", "local version loaded: " + currentVersion);
            Optional<ReleaseInstaller> latest = runtime.findLatest(currentVersion);
            if (latest.isEmpty()) {
                clearAvailableUpdate();
                auditLog.complete("CHECK", "no newer stable release is available");
                return DesktopUpdateCheckResult.notAvailable();
            }
            availableRelease = latest.get();
            auditLog.versions(currentVersion, availableRelease.version());
            auditLog.info(
                    "RELEASE",
                    "selected asset=" + availableRelease.assetName()
                            + " size=" + availableRelease.size()
                            + " sha256=" + availableRelease.sha256()
            );
            if (downloadedRelease != null && !downloadedRelease.version().equals(availableRelease.version())) {
                discardDownloadedInstaller();
            }
            return new DesktopUpdateCheckResult(true, availableRelease.version());
        } catch (Exception exception) {
            log.warn("Community GitHub Release update check failed", exception);
            auditLog.failure("CHECK", exception);
            clearAvailableUpdate();
            return DesktopUpdateCheckResult.failed();
        }
    }

    @Override
    public synchronized boolean triggerDownload(ConsoleResult consoleResult) throws Exception {
        if (!releaseRuntime || availableRelease == null) {
            auditLog.warn("DOWNLOAD", "download rejected because no checked release is available");
            return false;
        }
        discardDownloadedInstaller();
        lastProgress = -1;
        lastProgressNanos = 0L;
        ReleaseInstaller release = availableRelease;
        auditLog.info("DOWNLOAD", "download started asset=" + release.assetName());
        try {
            Path installer = runtime.download(release, (downloadedBytes, totalBytes) ->
                    reportDownloadProgress(downloadedBytes, totalBytes, consoleResult));
            if (installer == null || !Files.isRegularFile(installer)) {
                auditLog.failure("DOWNLOAD", new IOException("Downloaded installer file is missing"));
                return false;
            }
            downloadedRelease = release;
            downloadedInstaller = installer;
            auditLog.info("DOWNLOAD", "download ready path=" + installer);
            progressEmitter.emit(100, UpdatedStatus.Updated.getName(), consoleResult);
            return true;
        } catch (Exception exception) {
            auditLog.failure("DOWNLOAD", exception);
            throw exception;
        }
    }

    @Override
    public synchronized boolean triggerInstallation() {
        installationReady = releaseRuntime
                && downloadedRelease != null
                && downloadedInstaller != null
                && Files.isRegularFile(downloadedInstaller);
        if (installationReady) {
            auditLog.info("INSTALL_ARM", "native installation armed path=" + downloadedInstaller);
        } else {
            auditLog.warn("INSTALL_ARM", "installation rejected because no verified installer is ready");
        }
        return installationReady;
    }

    @Override
    public synchronized boolean prepareRestart() throws Exception {
        try {
            if (installationReady) {
                auditLog.info("HANDOFF", "preparing native installer process");
                boolean launched = runtime.launchInstaller(
                        downloadedRelease,
                        downloadedInstaller,
                        ProcessHandle.current().pid()
                );
                if (launched) {
                    installationReady = false;
                    auditLog.info("HANDOFF", "native installer process started");
                } else {
                    auditLog.failure("HANDOFF", new IOException("Native installer process was not accepted"));
                }
                return launched;
            }
            auditLog.info("RESTART", "ordinary application restart requested");
            ProcessHandle currentProcess = ProcessHandle.current();
            ProcessHandle.Info info = currentProcess.info();
            String launcherPath = info.command()
                    .orElseThrow(() -> new IllegalStateException("Cannot find launcher path"));
            String[] appArgs = info.arguments().orElse(new String[0]);
            List<String> command = RestartCommandFactory.buildMac(
                    currentProcess.pid(), launcherPath, appArgs
            );
            return restartCoordinator.prepare(() -> new ProcessBuilder(command).start());
        } catch (Exception exception) {
            auditLog.failure("HANDOFF", exception);
            throw exception;
        }
    }

    @Override
    public void exitCurrentProcessAfterResponse() {
        auditLog.info("APP_EXIT", "application exit scheduled after native installer handoff");
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

    @Override
    public DesktopUpdateRecoveryStatus recoveryStatus() {
        return auditLog.recoveryStatus();
    }

    @Override
    public boolean openRecoveryLog() {
        return auditLog.openRecoveryLog();
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
        auditLog.info(
                "DOWNLOAD",
                "progress=" + progress + "% bytes=" + downloadedBytes + "/" + totalBytes
        );
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
            auditLog.info("CLEANUP", "deleted stale installer path=" + installer);
        } catch (Exception exception) {
            log.debug("Could not delete stale Community installer {}", installer, exception);
            auditLog.warn("CLEANUP", "could not delete stale installer: " + exception.getMessage());
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
