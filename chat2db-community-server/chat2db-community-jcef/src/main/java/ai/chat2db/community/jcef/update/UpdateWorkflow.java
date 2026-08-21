package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.enums.update.UpdateActionType;
import ai.chat2db.community.jcef.listener.IProgressListener;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Orchestrates the check and download portions of one desktop update. File transfer and manifest
 * validation remain delegated to their dedicated components; this class owns only lifecycle and
 * error-to-state transitions.
 */
final class UpdateWorkflow {

    interface ProgressReporter {
        void appendLog(String message);

        void resetProgressTracker();

        void setProgress(int value, String message, ConsoleResult consoleResult);
    }

    record DownloadResult(Map<String, Path> downloadedFiles, boolean forceUpdate) {
    }

    private final UpdateOperationCoordinator coordinator;
    private final UpdateChecker checker;
    private final ManifestValidator manifestValidator;
    private final UpdatePlanner planner;
    private final ResumablePayloadDownloader downloader;
    private final LocalVersionStore localVersionStore;
    private final UpdateBackupStore backupStore;
    private final InstallationExecutor inProcessInstallationExecutor;
    private final InstallationExecutor elevatedWindowsInstallationExecutor;
    private final UpdateSource updateSource;
    private final ObjectMapper objectMapper;
    private final Path appDirectory;
    private final LongSupplier nanosClock;
    private final Supplier<ProgressReporter> progressReporterFactory;
    private final Consumer<Exception> errorLogger;
    private final Consumer<String> warningLogger;

    private ProgressReporter progressReporter;

    UpdateWorkflow(UpdateRuntimeComponents components) {
        this.coordinator = components.coordinator();
        this.checker = components.checker();
        this.manifestValidator = components.manifestValidator();
        this.planner = components.planner();
        this.downloader = components.downloader();
        this.localVersionStore = components.localVersionStore();
        this.backupStore = components.backupStore();
        this.inProcessInstallationExecutor = components.inProcessInstallationExecutor();
        this.elevatedWindowsInstallationExecutor = components.elevatedWindowsInstallationExecutor();
        this.updateSource = components.updateSource();
        this.objectMapper = components.objectMapper();
        this.appDirectory = components.appDirectory();
        this.nanosClock = components.nanosClock();
        this.progressReporterFactory = components.progressReporterFactory();
        this.errorLogger = components.errorLogger();
        this.warningLogger = components.warningLogger();
    }

    Updater.CheckResult check() {
        UpdateOperationCoordinator.CheckOperation operation = coordinator.beginCheck();
        if (operation.started()) {
            coordinator.completeCheck(checkLatestVersion());
        }
        try {
            return operation.future().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Update check was interrupted", new Object[0], exception);
        } catch (ExecutionException exception) {
            throw new BusinessException("Update check failed", new Object[0], exception.getCause());
        }
    }

    DownloadResult download(ConsoleResult consoleResult) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        if (progressReporter == null) {
            throw new BusinessException("Check for an available update before downloading it.");
        }
        Updater.CheckResult checkedResult = coordinator.beginDownload();
        try {
            backupStore.clearCompletedSessions(appDirectory);
            discardDownloadedFiles();
            progressReporter.resetProgressTracker();

            AvailableSnapshot snapshot = refreshSnapshotIfExpired(checkedResult.getAvailableSnapshot());
            byte[] metadataBytes = updateSource.fetchVersionManifest(snapshot.version());
            verifyMetadataSha256(metadataBytes, snapshot.metadataSha256());
            VersionMetadata remoteMetadata = objectMapper.readValue(metadataBytes, VersionMetadata.class);
            manifestValidator.validate(remoteMetadata, snapshot.version());

            VersionMetadata localMetadata = localVersionStore.load(true);
            List<FileUpdateAction> actions = planner.plan(localMetadata, remoteMetadata);
            coordinator.replaceCheckResult(previous -> new Updater.CheckResult(true, previous.getReleaseNotes(), actions,
                    remoteMetadata, false, null, snapshot, previous.getReleasePageUrl(), null, null));

            long filesToDownload = actions.stream().filter(UpdateWorkflow::requiresDownload).count();
            long totalDownloadSize = totalDownloadSize(actions);
            if (totalDownloadSize > ManifestValidator.MAX_TOTAL_DOWNLOAD_BYTES) {
                throw new IOException("Update exceeds the total download limit");
            }

            downloadActions(consoleResult, actions, remoteMetadata, filesToDownload, totalDownloadSize);
            progressReporter.setProgress(100, UpdatedStatus.Updated.getName(), consoleResult);
            coordinator.markDownloadReady();
            return new DownloadResult(coordinator.downloadedFilesSnapshot(), Boolean.TRUE.equals(remoteMetadata.getForceUpdate()));
        } catch (Exception exception) {
            errorLogger.accept(exception);
            appendLog("ERROR: " + exception.getMessage());
            coordinator.replaceCheckResult(previous -> new Updater.CheckResult(previous.isNeedsUpdate(), previous.getReleaseNotes(),
                    previous.getActions(), previous.getRemoteMetadata(), true, previous.getLatestVersionInfo(),
                    previous.getAvailableSnapshot(), previous.getReleasePageUrl(), UpdateFailureStage.DOWNLOAD,
                    mapFailureReason(exception)));
            rethrowDownloadException(exception);
            throw new AssertionError("unreachable");
        } finally {
            coordinator.finishDownloadAfterFailure();
        }
    }

    void appendLog(String message) {
        if (progressReporter != null) {
            progressReporter.appendLog(message);
        }
    }

    boolean hasProgressReporter() {
        return progressReporter != null;
    }

    boolean installInProcess() {
        coordinator.beginInstallation();
        try {
            boolean installed = inProcessInstallationExecutor.install(requireUpdateActions(),
                    coordinator.downloadedFilesForCurrentOperation(), coordinator.currentCheckResult().getRemoteMetadata());
            if (installed) {
                coordinator.completeInstallation();
            }
            return installed;
        } catch (IOException exception) {
            errorLogger.accept(exception);
            appendLog("ERROR during update execution: " + exception.getMessage());
            return false;
        } finally {
            discardDownloadedFiles();
            coordinator.finishInstallationAfterFailure();
        }
    }

    boolean launchElevatedInstaller() {
        coordinator.beginInstallation();
        try {
            boolean launched = elevatedWindowsInstallationExecutor.install(requireUpdateActions(),
                    coordinator.downloadedFilesForCurrentOperation(), coordinator.currentCheckResult().getRemoteMetadata());
            if (!launched) {
                coordinator.restoreReadyToInstallAfterLaunchFailure();
            }
            return launched;
        } catch (IOException exception) {
            errorLogger.accept(exception);
            appendLog("FATAL ERROR: Could not start the update process. " + exception.getMessage());
            coordinator.restoreReadyToInstallAfterLaunchFailure();
            return false;
        }
    }

    private Updater.CheckResult checkLatestVersion() {
        progressReporter = progressReporterFactory.get();
        try {
            UpdateCheckOutcome outcome = checker.check();
            return new Updater.CheckResult(outcome.needsUpdate(), outcome.releaseNotes(), Collections.emptyList(), null,
                    false, null, outcome.snapshot(), outcome.releasePageUrl(), null, null);
        } catch (Exception exception) {
            errorLogger.accept(exception);
            appendLog("ERROR: " + exception.getMessage());
            return failedCheckResult(exception, UpdateFailureStage.CHECK);
        }
    }

    private void downloadActions(ConsoleResult consoleResult, List<FileUpdateAction> actions, VersionMetadata remoteMetadata,
                                 long filesToDownload, long totalDownloadSize) throws IOException, NoSuchAlgorithmException {
        if (filesToDownload == 0) {
            appendLog("--- No files to download ---");
            return;
        }
        AtomicLong cumulativeBytesDownloaded = new AtomicLong(0);
        progressReporter.setProgress(0, "Initializing update...", consoleResult);
        appendLog("--- Download Phase ---");
        for (FileUpdateAction action : actions) {
            if (!requiresDownload(action)) {
                continue;
            }
            FileInfo remoteFile = action.remoteFileInfo;
            appendLog("Downloading: " + remoteFile.serverFileName + " (ID: " + remoteFile.id + ")");
            IProgressListener listener = bytesWritten -> {
                long totalDownloaded = cumulativeBytesDownloaded.addAndGet(bytesWritten);
                int overallProgress = totalDownloadSize > 0
                        ? (int) Math.min(100, (totalDownloaded * 100) / totalDownloadSize)
                        : 0;
                progressReporter.setProgress(overallProgress,
                        String.format("Downloading %s (%d%%)", remoteFile.serverFileName, overallProgress), consoleResult);
            };
            Path downloadedPath = downloader.download(remoteMetadata.getVersion(), remoteFile, listener);
            coordinator.rememberDownloadedFile(remoteFile.id, downloadedPath);
            appendLog("Downloaded and verified: " + remoteFile.serverFileName);
        }
        appendLog("--- Download Phase Complete ---");
    }

    private AvailableSnapshot refreshSnapshotIfExpired(AvailableSnapshot snapshot) throws IOException {
        if (!snapshot.isExpired(nanosClock.getAsLong())) {
            return snapshot;
        }
        FetchedUpdateManifest refreshed = updateSource.fetchLatestManifest();
        String refreshedVersion = refreshed.version();
        UpdateChecker.validateVersion(refreshedVersion);
        if (!refreshedVersion.equals(snapshot.version())) {
            AvailableSnapshot refreshedSnapshot = new AvailableSnapshot(refreshedVersion, refreshed.exactBytes(),
                    refreshed.metadataSha256(), refreshed.fetchedAtNanos());
            coordinator.replaceCheckResult(previous -> new Updater.CheckResult(true, refreshed.releaseNotes(),
                    Collections.emptyList(), null, false, null, refreshedSnapshot, refreshed.releasePageUrl(), null, null));
            throw new BusinessException("A newer version is available. Please check again before downloading.");
        }
        if (!snapshot.sameBytes(refreshed.exactBytes())) {
            throw new IOException("Immutable Release contract violated: same version has different manifest bytes");
        }
        AvailableSnapshot renewed = new AvailableSnapshot(refreshedVersion, refreshed.exactBytes(),
                refreshed.metadataSha256(), refreshed.fetchedAtNanos());
        coordinator.replaceCheckResult(previous -> new Updater.CheckResult(true, previous.getReleaseNotes(),
                Collections.emptyList(), null, false, null, renewed, previous.getReleasePageUrl(), null, null));
        return renewed;
    }

    void discardDownloadedFiles() {
        coordinator.downloadedFilesSnapshot().values().forEach(tempFile -> {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException exception) {
                warningLogger.accept("Failed to delete temporary download file: " + tempFile + " (" + exception.getMessage() + ")");
            }
        });
        coordinator.clearDownloadedFiles();
    }

    private static boolean requiresDownload(FileUpdateAction action) {
        return action.actionType == UpdateActionType.DOWNLOAD_NEW || action.actionType == UpdateActionType.UPDATE_EXISTING;
    }

    List<FileUpdateAction> requireUpdateActions() throws IOException {
        Updater.CheckResult result = coordinator.currentCheckResult();
        if (result == null || result.getActions() == null) {
            throw new IOException("Update plan is incomplete: update actions are missing.");
        }
        return result.getActions();
    }

    private static long totalDownloadSize(List<FileUpdateAction> actions) throws IOException {
        try {
            return actions.stream().filter(UpdateWorkflow::requiresDownload)
                    .mapToLong(action -> action.remoteFileInfo.fileSizeByte).reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new IOException("Update download size overflow", exception);
        }
    }

    private static void verifyMetadataSha256(byte[] metadataBytes, String expectedSha256)
            throws IOException, NoSuchAlgorithmException {
        if (expectedSha256 == null || !expectedSha256.matches("^[a-f0-9]{64}$")) {
            throw new IOException("Latest manifest has an invalid metadata SHA-256");
        }
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest(metadataBytes)) {
            actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        if (!actual.toString().equals(expectedSha256)) {
            throw new IOException("Version manifest SHA-256 does not match the latest manifest");
        }
    }

    private static Updater.CheckResult failedCheckResult(Exception exception, UpdateFailureStage stage) {
        return new Updater.CheckResult(false, null, Collections.emptyList(), null, true, null, null, null,
                stage, mapFailureReason(exception));
    }

    private static UpdateFailureReason mapFailureReason(Exception exception) {
        if (exception == null) {
            return UpdateFailureReason.UNKNOWN;
        }
        String message = exception.getMessage();
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("redirect") || lower.contains("unsupported redirect")) {
                return UpdateFailureReason.UNSUPPORTED_REDIRECT;
            }
            if (lower.contains("checksum") || lower.contains("sha-256") || lower.contains("size does not match")) {
                return UpdateFailureReason.CHECKSUM_MISMATCH;
            }
            if (lower.contains("manifest") || lower.contains("metadata") || lower.contains("version")
                    || lower.contains("url") || lower.contains("release page") || lower.contains("release notes")
                    || lower.contains("json") || lower.contains("parse") || lower.contains("unexpected")) {
                return UpdateFailureReason.INVALID_MANIFEST;
            }
        }
        return exception instanceof IOException ? UpdateFailureReason.NETWORK : UpdateFailureReason.UNKNOWN;
    }

    private static void rethrowDownloadException(Exception exception)
            throws IOException, NoSuchAlgorithmException, URISyntaxException {
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        if (exception instanceof NoSuchAlgorithmException algorithmException) {
            throw algorithmException;
        }
        if (exception instanceof URISyntaxException uriException) {
            throw uriException;
        }
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IOException("Update download failed", exception);
    }
}
