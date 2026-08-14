package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.LongSupplier;


@Slf4j
@Component
@NotCliRuntime
public class Updater {

    private static final String WEB_FRONTEND_PROPERTY = "chat2db.jcef.web-frontend";

    private Path APP_DIR;
    private Path LOCAL_VERSION_FILE;
    private Path TMP_DIR;

    private final UpdateSource updateSource;
    private final LongSupplier nanosClock;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static volatile Updater instance;
    private final UpdateOperationCoordinator operationCoordinator = new UpdateOperationCoordinator();
    private final UpdatePlanner updatePlanner;
    private final ResumablePayloadDownloader payloadDownloader;
    private final LocalVersionStore localVersionStore;
    private final UpdateChecker updateChecker;
    private final UpdateBackupStore updateBackupStore;
    private final ManifestValidator manifestValidator;
    private final InstallationExecutor inProcessInstallationExecutor;
    private final InstallationExecutor elevatedWindowsInstallationExecutor;
    private final UpdateWorkflow updateWorkflow;
    private final DesktopRestartController desktopRestartController = new DesktopRestartController(new RestartCoordinator());

    public static Updater getInstance() {
        if (instance == null) {
            synchronized (Updater.class) {
                if (instance == null) {
                    instance = new Updater();
                }
            }
        }
        return instance;
    }

    private Updater() {
        this(new GitHubReleaseUpdateSource(), defaultAppDir(), defaultLocalVersionFile(), defaultTmpDir(), System::nanoTime);
    }

    /**
     * Package-private constructor for tests and alternative wiring.
     */
    Updater(UpdateSource updateSource, Path appDir, Path localVersionFile, Path tmpDir, LongSupplier nanosClock) {
        this.updateSource = Objects.requireNonNull(updateSource, "updateSource is required");
        this.APP_DIR = canonicalizeExistingAncestor(Objects.requireNonNull(appDir, "appDir is required"));
        this.LOCAL_VERSION_FILE = Objects.requireNonNull(localVersionFile, "localVersionFile is required");
        this.TMP_DIR = canonicalizeExistingAncestor(Objects.requireNonNull(tmpDir, "tmpDir is required"));
        this.nanosClock = Objects.requireNonNull(nanosClock, "nanosClock is required");
        this.localVersionStore = new LocalVersionStore(this.LOCAL_VERSION_FILE, objectMapper, this::appendUpdateLog,
                message -> log.error(message));
        this.updateChecker = new UpdateChecker(updateSource, localVersionStore);
        this.updateBackupStore = new UpdateBackupStore(this::appendUpdateLog, message -> log.warn(message));
        this.manifestValidator = new ManifestValidator(new ManifestValidator.PathResolver() {
            @Override
            public Path resolveApplicationTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public Path resolveTemporaryPayload(String fileName) throws IOException {
                return resolveTemporaryFile(fileName);
            }
        });
        this.inProcessInstallationExecutor = new InProcessInstallationExecutor(this.APP_DIR,
                this::resolveAppRelativePath, this::saveLocalVersion, this::appendUpdateLog,
                exception -> log.error("Failed to execute update action", exception));
        this.elevatedWindowsInstallationExecutor = new ElevatedWindowsInstallationExecutor(this.APP_DIR, objectMapper,
                this::appendUpdateLog, exception -> log.error("Failed to launch auxiliary updater process", exception));
        this.updatePlanner = new UpdatePlanner(new UpdatePlanner.LocalFileInspector() {
            @Override
            public Path resolveTarget(String relativePath) throws IOException {
                return resolveAppRelativePath(relativePath);
            }

            @Override
            public boolean checksumMatches(Path path, String expectedSha256)
                    throws IOException, NoSuchAlgorithmException {
                return verifyFileChecksum(path, expectedSha256);
            }
        });
        this.payloadDownloader = new ResumablePayloadDownloader(updateSource, new ResumablePayloadDownloader.DownloadPaths() {
            @Override
            public Path resolvePayload(String fileName) throws IOException {
                return resolveTemporaryFile(fileName);
            }

            @Override
            public Path resolvePartial(Path payloadPath) throws IOException {
                return resolvePartialDownloadFile(payloadPath);
            }
        }, this::verifyFileChecksum, this::appendUpdateLog);
        this.updateWorkflow = new UpdateWorkflow(new UpdateRuntimeComponents(operationCoordinator, updateChecker,
                manifestValidator, updatePlanner, payloadDownloader, localVersionStore, updateBackupStore,
                inProcessInstallationExecutor, elevatedWindowsInstallationExecutor, updateSource, objectMapper,
                this.APP_DIR, this.nanosClock, UpdateProgressDialog::new,
                exception -> log.error("Update workflow failed: {}", exception.getMessage(), exception),
                message -> log.warn(message)));
    }

    private static Path canonicalizeExistingAncestor(Path path) {
        Path absolutePath = path.toAbsolutePath().normalize();
        List<Path> missingSegments = new ArrayList<>();
        Path existingPath = absolutePath;
        while (!Files.exists(existingPath, LinkOption.NOFOLLOW_LINKS) && existingPath.getParent() != null) {
            missingSegments.add(existingPath.getFileName());
            existingPath = existingPath.getParent();
        }
        try {
            Path canonicalPath = existingPath.toRealPath();
            for (int index = missingSegments.size() - 1; index >= 0; index--) {
                canonicalPath = canonicalPath.resolve(missingSegments.get(index));
            }
            return canonicalPath.normalize();
        } catch (IOException exception) {
            return absolutePath;
        }
    }

    private static Path defaultAppDir() {
        return Paths.get(OSOperateUtil.getCurrentJarPath());
    }

    private static Path defaultLocalVersionFile() {
        return defaultAppDir().resolve("local_version.json");
    }

    private static Path defaultTmpDir() {
        if (OS.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isEmpty()) {
                localAppData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local";
            }
            return Paths.get(localAppData).resolve("tmp_updater_downloads");
        }
        return defaultAppDir().resolve("tmp_updater_downloads");
    }

    long nanosNow() {
        return nanosClock.getAsLong();
    }

    /**
     * Package-private accessor for tests to read the current check state.
     */
    CheckResult currentCheckResult() {
        return operationCoordinator.currentCheckResult();
    }

    /**
     * Package-private visibility keeps lifecycle assertions out of reflection-based tests without
     * expanding the desktop updater's public API.
     */
    UpdateOperationCoordinator operationCoordinator() {
        return operationCoordinator;
    }

    private void appendUpdateLog(String message) {
        updateWorkflow.appendLog(message);
    }

    static class UpdateProgressDialog implements UpdateWorkflow.ProgressReporter {
        private static final long MIN_PUSH_INTERVAL_MS = 500L;
        private int lastReportedProgress = -1;
        private long lastPushTimeMs = 0L;

        public void appendLog(String message) {
            log.info("update msg: {}", message);
        }


        public void resetProgressTracker() {
            this.lastReportedProgress = -1;
            this.lastPushTimeMs = 0L;
        }

        public void setProgress(int value, String message, ConsoleResult consoleResult) {
            String status = UpdatedStatus.Updating.getName();
            boolean isFinalStatus = UpdatedStatus.Updated.getName().equals(message);
            if (isFinalStatus) {
                status = UpdatedStatus.Updated.getName();
            }
            if (!isFinalStatus) {
                if (value <= lastReportedProgress) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (lastPushTimeMs != 0L && (now - lastPushTimeMs) < MIN_PUSH_INTERVAL_MS) {
                    return;
                }
                lastReportedProgress = value;
                lastPushTimeMs = now;
            } else {
                lastReportedProgress = value;
                lastPushTimeMs = System.currentTimeMillis();
            }

            consoleResult.setMessage(Map.of("progress", value, "status", status));
            consoleResult.setActionType(ActionTypeEnum.UPDATE_PROGRESS.getName());
            String result = JSON.toJSONString(consoleResult);
            CallJsFunctionUtil.callHandleJavaMessage(JcefContext.getInstance().getBrowser_(), result);
            log.info("update process {} ({}%, {})", message, value, result);
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
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

        CheckResult(boolean needsUpdate, String releaseNotes, List<FileUpdateAction> actions, VersionMetadata remoteMetadata) {
            this(needsUpdate, releaseNotes, actions, remoteMetadata, false, null, null, null, null, null);
        }
    }

    public void restartApp() throws IOException {
        desktopRestartController.restartApplication();
    }

    public boolean prepareRestart() throws IOException {
        return desktopRestartController.prepareRestart();
    }

    public void exitCurrentProcessAfterResponse() {
        desktopRestartController.exitAfterResponse();
    }


    public CheckResult appCheckUpdate() {
        return updateWorkflow.check();
    }

    static int compareVersions(String version1, String version2) {
        return UpdateChecker.compareVersions(version1, version2);
    }

    void validateRemoteMetadata(VersionMetadata metadata) throws IOException {
        manifestValidator.validate(metadata);
    }

    void validateRemoteMetadata(VersionMetadata metadata, String expectedVersion) throws IOException {
        manifestValidator.validate(metadata, expectedVersion);
    }

    Path resolveAppRelativePath(String relativePath) throws IOException {
        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolveApplicationRelativePath(relativePath);
    }

    Path resolveTemporaryFile(String fileName) throws IOException {
        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolveTemporaryFile(fileName);
    }

    List<FileUpdateAction> determineUpdateActions(VersionMetadata local, VersionMetadata remote) throws IOException, NoSuchAlgorithmException {
        return updatePlanner.plan(local, remote);
    }


    public Map<String, Path> triggerDownload(ConsoleResult consoleResult) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        requirePackagedRelease();
        UpdateWorkflow.DownloadResult download = updateWorkflow.download(consoleResult);
        if (download.forceUpdate()) {
            if (OS.isWindows()) {
                if (!triggerInstallationWithAuxiliaryProcess()) {
                    throw new IOException("Could not start the Windows updater process");
                }
                return download.downloadedFiles();
            }
            if (triggerInstallation()) {
                restartApp();
            }
        }
        return download.downloadedFiles();
    }

    private Path resolvePartialDownloadFile(Path targetPath) throws IOException {
        return new UpdatePathPolicy(APP_DIR, TMP_DIR).resolvePartialDownloadFile(targetPath);
    }

    static boolean isPartialResponseForOffset(long existingBytes, long expectedSize, int responseCode,
                                              String contentRange, long contentLength) {
        return ResumablePayloadDownloader.isPartialResponseForOffset(existingBytes, expectedSize, responseCode,
                contentRange, contentLength);
    }

    public boolean triggerInstallation() {
        requirePackagedRelease();
        return updateWorkflow.installInProcess();
    }

    void clearOldBackups(Path baseDir) {
        updateBackupStore.clearCompletedSessions(baseDir);
    }

    public void saveLocalVersion(VersionMetadata metadata) throws IOException {
        localVersionStore.save(metadata);
    }

    public boolean triggerInstallationWithAuxiliaryProcess() {
        requirePackagedRelease();
        if (!updateWorkflow.launchElevatedInstaller()) {
            return false;
        }
        desktopRestartController.exitAfterElevatedInstallerLaunch();
        return true;
    }

    List<FileUpdateAction> requireUpdateActions() throws IOException {
        return updateWorkflow.requireUpdateActions();
    }

    private void requirePackagedRelease() {
        if (!isSelfUpdateSupported(ConfigUtils.isRelease(), Boolean.getBoolean(WEB_FRONTEND_PROPERTY))) {
            throw new BusinessException("Self-update is only available from an installed desktop release.");
        }
    }

    private void discardDownloadedFiles() {
        updateWorkflow.discardDownloadedFiles();
    }

    static boolean isSelfUpdateSupported(boolean releaseProfile, boolean webFrontend) {
        return releaseProfile && !webFrontend;
    }

    private boolean verifyFileChecksum(Path filePath, String expectedSha256) throws IOException, NoSuchAlgorithmException {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            String errorMsg = "Cannot verify checksum, file does not exist or is a directory: " + filePath;
            appendUpdateLog("ERROR: " + errorMsg);
            log.error(errorMsg);
            return false;
        }
        appendUpdateLog("Verifying: " + filePath.getFileName());
        MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(filePath))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                sha256Digest.update(buffer, 0, bytesRead);
            }
        }
        String actualSha256 = bytesToHex(sha256Digest.digest());
        boolean match = actualSha256.equals(expectedSha256);
        if (!match) {
            String errorMsg = "Checksum mismatch for " + filePath + ". Expected: " + expectedSha256 + ", Got: " + actualSha256;
            appendUpdateLog("ERROR: " + errorMsg);
            log.error(errorMsg);
        } else {
            appendUpdateLog("Checksum OK: " + filePath.getFileName());
        }
        return match;
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    static void extractZip(Path zipFile, Path destDir, String expectedTopLevelDirectory) throws IOException {
        InProcessInstallationExecutor.extractZip(zipFile, destDir, expectedTopLevelDirectory);
    }
    public static void updateVersionInFile(String newVersion) {
        MacBundleVersionFile.update(defaultAppDir(), newVersion, message -> log.info(message),
                exception -> log.error("Error: An IO error occurred while updating a file", exception));
    }
}
