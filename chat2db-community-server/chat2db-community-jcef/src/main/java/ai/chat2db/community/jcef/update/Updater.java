package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

/**
 * Compatibility facade for the Community desktop updater.
 *
 * <p>The facade preserves JCEF-facing methods while all update work is delegated to
 * {@link UpdateWorkflow}. It deliberately owns no direct network or installation logic.</p>
 */
@Slf4j
@Component
@NotCliRuntime
public class Updater {

    private static volatile Updater instance;

    private Path APP_DIR;
    private Path LOCAL_VERSION_FILE;
    private Path TMP_DIR;

    private final UpdateOperationCoordinator coordinator;
    private final UpdateWorkflow workflow;
    private final UpdateBackupStore backupStore;
    private final DesktopRestartController restartController;
    private final BooleanSupplier windowsPlatform;

    public static Updater getInstance() {
        Updater existing = instance;
        if (existing == null) {
            synchronized (Updater.class) {
                existing = instance;
                if (existing == null) {
                    existing = new Updater();
                    instance = existing;
                }
            }
        }
        return existing;
    }

    private Updater() {
        this(DesktopUpdateSourceFactory.create(), currentApplicationDirectory(), null,
                currentTemporaryDirectory(currentApplicationDirectory()), System::nanoTime, OS::isWindows);
    }

    Updater(UpdateSource source, Path appDirectory, Path localVersionFile, Path temporaryDirectory,
            LongSupplier nanosClock) {
        this(source, appDirectory, localVersionFile, temporaryDirectory, nanosClock, () -> true);
    }

    Updater(UpdateSource source, Path appDirectory, Path localVersionFile, Path temporaryDirectory,
            LongSupplier nanosClock, BooleanSupplier windowsPlatform) {
        this.APP_DIR = appDirectory.toAbsolutePath().normalize();
        this.LOCAL_VERSION_FILE = localVersionFile == null
                ? this.APP_DIR.resolve("local_version.json") : localVersionFile.toAbsolutePath().normalize();
        this.TMP_DIR = temporaryDirectory.toAbsolutePath().normalize();
        this.windowsPlatform = windowsPlatform;

        ObjectMapper mapper = new ObjectMapper();
        UpdateProgressDialog progress = new UpdateProgressDialog();
        LocalVersionStore localVersions = new LocalVersionStore(LOCAL_VERSION_FILE, mapper, progress::appendLog,
                message -> log.warn("{}", message));
        this.coordinator = new UpdateOperationCoordinator();
        ManifestValidator validator = new ManifestValidator(new ManifestValidator.PathResolver() {
            @Override
            public Path resolveApplicationTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public Path resolveTemporaryPayload(String fileName) throws IOException {
                return resolveTemporaryFile(fileName);
            }
        });
        UpdatePlanner planner = new UpdatePlanner(new UpdatePlanner.LocalFileInspector() {
            @Override
            public Path resolveTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public boolean checksumMatches(Path path, String expectedSha256) throws IOException, NoSuchAlgorithmException {
                return Updater.checksumMatches(path, expectedSha256);
            }
        });
        ResumablePayloadDownloader downloader = new ResumablePayloadDownloader(source,
                new ResumablePayloadDownloader.DownloadPaths() {
                    @Override
                    public Path resolvePayload(String fileName) throws IOException {
                        return resolveTemporaryFile(fileName);
                    }

                    @Override
                    public Path resolvePartial(Path payloadPath) throws IOException {
                        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolvePartialDownloadFile(payloadPath);
                    }
                }, Updater::checksumMatches, progress::appendLog);
        this.backupStore = new UpdateBackupStore(progress::appendLog, message -> log.warn("{}", message));
        InstallationExecutor inProcessInstaller = new InProcessInstallationExecutor(APP_DIR, this::resolveAppRelativePath,
                localVersions::save, progress::appendLog, exception -> log.error("Update installation failed", exception));
        InstallationExecutor elevatedWindowsInstaller = new ElevatedWindowsInstallationExecutor(APP_DIR, TMP_DIR, mapper,
                progress::appendLog, exception -> log.error("Elevated update installation failed", exception));
        this.workflow = new UpdateWorkflow(new UpdateRuntimeComponents(coordinator, new UpdateChecker(source, localVersions),
                validator, planner, downloader, localVersions, backupStore, inProcessInstaller, elevatedWindowsInstaller,
                source, mapper, APP_DIR, nanosClock, () -> progress,
                exception -> log.error("Desktop update operation failed", exception), message -> log.warn("{}", message)));
        this.restartController = new DesktopRestartController(new RestartCoordinator());
    }

    public CheckResult appCheckUpdate() {
        return workflow.check();
    }

    public Map<String, Path> triggerDownload(ConsoleResult consoleResult)
            throws IOException, NoSuchAlgorithmException, java.net.URISyntaxException {
        requireAutomaticUpdateSupport();
        return workflow.download(consoleResult).downloadedFiles();
    }

    public boolean triggerInstallation() {
        requireAutomaticUpdateSupport();
        return workflow.installInProcess();
    }

    /** Requests the Community renderer's exit confirmation before Windows elevation starts. */
    public boolean triggerInstallationWithAuxiliaryProcess() {
        requireAutomaticUpdateSupport();
        return ApplicationExitCoordinator.request(ApplicationExitCoordinator.ExitAction.INSTALL_UPDATE.name());
    }

    /** Runs only after the renderer has confirmed the pending Windows exit request. */
    public boolean triggerInstallationWithAuxiliaryProcessNow() {
        requireAutomaticUpdateSupport();
        return workflow.launchElevatedInstaller();
    }

    public void restartApp() throws IOException {
        restartController.restartApplication();
    }

    public void restartAppNow() throws IOException {
        restartController.restartApplication();
    }

    public boolean prepareRestart() throws IOException {
        return restartController.prepareRestart();
    }

    public void exitCurrentProcessAfterResponse() {
        restartController.exitAfterResponse();
    }

    static boolean isSelfUpdateSupported(boolean release, boolean webFrontend) {
        return release && !webFrontend;
    }

    static boolean isAutomaticUpdateSupported(boolean release, boolean webFrontend, boolean windows) {
        return isSelfUpdateSupported(release, webFrontend) && windows;
    }

    static int compareVersions(String left, String right) {
        return UpdateChecker.compareVersions(left, right);
    }

    UpdateOperationCoordinator operationCoordinator() {
        return coordinator;
    }

    CheckResult currentCheckResult() {
        return coordinator.currentCheckResult();
    }

    List<FileUpdateAction> requireUpdateActions() throws IOException {
        return workflow.requireUpdateActions();
    }

    Path resolveAppRelativePath(String relativePath) throws IOException {
        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolveApplicationRelativePath(relativePath);
    }

    Path resolveTemporaryFile(String fileName) throws IOException {
        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolveTemporaryFile(fileName);
    }

    List<FileUpdateAction> determineUpdateActions(VersionMetadata local, VersionMetadata remote)
            throws IOException, NoSuchAlgorithmException {
        return new UpdatePlanner(new UpdatePlanner.LocalFileInspector() {
            @Override
            public Path resolveTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public boolean checksumMatches(Path path, String expectedSha256) throws IOException, NoSuchAlgorithmException {
                return Updater.checksumMatches(path, expectedSha256);
            }
        }).plan(local, remote);
    }

    void validateRemoteMetadata(VersionMetadata metadata) throws IOException {
        new ManifestValidator(new ManifestValidator.PathResolver() {
            @Override
            public Path resolveApplicationTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public Path resolveTemporaryPayload(String fileName) throws IOException {
                return resolveTemporaryFile(fileName);
            }
        }).validate(metadata, metadata == null ? null : metadata.getVersion());
    }

    void clearOldBackups(Path applicationDirectory) {
        backupStore.clearCompletedSessions(applicationDirectory);
    }

    static boolean isPartialResponseForOffset(long existingBytes, long expectedSize, int responseCode,
                                              String contentRange, long contentLength) {
        return ResumablePayloadDownloader.isPartialResponseForOffset(existingBytes, expectedSize, responseCode,
                contentRange, contentLength);
    }

    static void extractZip(Path zipFile, Path destination, String expectedTopLevelDirectory) throws IOException {
        InProcessInstallationExecutor.extractZip(zipFile, destination, expectedTopLevelDirectory);
    }

    private static Path currentApplicationDirectory() {
        return Paths.get(OSOperateUtil.getCurrentJarPath()).toAbsolutePath().normalize();
    }

    private static Path currentTemporaryDirectory(Path applicationDirectory) {
        if (!OS.isWindows()) {
            return applicationDirectory.resolve("tmp_updater_downloads");
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            localAppData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local";
        }
        return Paths.get(localAppData).resolve("tmp_updater_downloads");
    }

    private static boolean checksumMatches(Path path, String expectedSha256) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = java.nio.file.Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            actual.append(String.format("%02x", value));
        }
        return actual.toString().equals(expectedSha256);
    }

    private void requireAutomaticUpdateSupport() {
        boolean webFrontend = Boolean.parseBoolean(System.getProperty("chat2db.jcef.web-frontend", "false"));
        if (!isAutomaticUpdateSupported(ConfigUtils.isRelease(), webFrontend, windowsPlatform.getAsBoolean())) {
            throw new BusinessException("Automatic update installation is available only in packaged Windows desktop releases.");
        }
    }

    static class UpdateProgressDialog implements UpdateWorkflow.ProgressReporter {
        private static final long MIN_PUSH_INTERVAL_MS = 500L;
        private int lastReportedProgress = -1;
        private long lastPushTimeMs;

        @Override
        public void appendLog(String message) {
            log.info("update msg: {}", message);
        }

        @Override
        public void resetProgressTracker() {
            lastReportedProgress = -1;
            lastPushTimeMs = 0L;
        }

        @Override
        public void setProgress(int value, String message, ConsoleResult consoleResult) {
            boolean completed = UpdatedStatus.Updated.getName().equals(message);
            if (!completed) {
                long now = System.currentTimeMillis();
                if (value <= lastReportedProgress || (lastPushTimeMs != 0L && now - lastPushTimeMs < MIN_PUSH_INTERVAL_MS)) {
                    return;
                }
                lastPushTimeMs = now;
            } else {
                lastPushTimeMs = System.currentTimeMillis();
            }
            lastReportedProgress = value;
            consoleResult.setMessage(Map.of("progress", value,
                    "status", completed ? UpdatedStatus.Updated.getName() : UpdatedStatus.Updating.getName()));
            consoleResult.setActionType(ActionTypeEnum.UPDATE_PROGRESS.getName());
            CallJsFunctionUtil.callHandleJavaMessage(JcefContext.getInstance().getBrowser_(), JSON.toJSONString(consoleResult));
        }
    }

    @Getter
    @ToString
    public static class CheckResult {
        private boolean needsUpdate;
        private String releaseNotes;
        private List<FileUpdateAction> actions;
        private VersionMetadata remoteMetadata;
        private boolean checkFailed;
        private LatestVersionInfo latestVersionInfo;
        private AvailableSnapshot availableSnapshot;
        private String releasePageUrl;
        private UpdateFailureStage failureStage;
        private UpdateFailureReason failureReason;

        public CheckResult() {
        }

        public CheckResult(boolean needsUpdate, String releaseNotes, List<FileUpdateAction> actions,
                           VersionMetadata remoteMetadata) {
            this(needsUpdate, releaseNotes, actions, remoteMetadata, false, null, null, null, null, null);
        }

        public CheckResult(boolean needsUpdate, String releaseNotes, List<FileUpdateAction> actions,
                           VersionMetadata remoteMetadata, boolean checkFailed, LatestVersionInfo latestVersionInfo,
                           AvailableSnapshot availableSnapshot, String releasePageUrl,
                           UpdateFailureStage failureStage, UpdateFailureReason failureReason) {
            this.needsUpdate = needsUpdate;
            this.releaseNotes = releaseNotes;
            this.actions = actions;
            this.remoteMetadata = remoteMetadata;
            this.checkFailed = checkFailed;
            this.latestVersionInfo = latestVersionInfo;
            this.availableSnapshot = availableSnapshot;
            this.releasePageUrl = releasePageUrl;
            this.failureStage = failureStage;
            this.failureReason = failureReason;
        }
    }
}
