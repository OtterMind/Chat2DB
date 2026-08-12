package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.enums.UpdatedStatus;
import ai.chat2db.community.jcef.enums.update.UpdateActionType;
import ai.chat2db.community.jcef.listener.IProgressListener;
import ai.chat2db.community.jcef.utils.CallJsFunctionUtil;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.annotation.NotCliRuntime;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;


@Slf4j
@Component
@NotCliRuntime
public class Updater {

    private static final String WEB_FRONTEND_PROPERTY = "chat2db.jcef.web-frontend";
    private static final String COMMUNITY_UPDATE_HOST = "cdn.chat2db-ai.com";
    private static final String SHA_256_PATTERN = "^[a-fA-F0-9]{64}$";
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_ZIP_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final String PARTIAL_DOWNLOAD_SUFFIX = ".part";
    private static final long MAX_SINGLE_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_DOWNLOAD_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final String BACKUP_DIRECTORY_NAME = ".chat2db-update-backups";
    private static final String BACKUP_OWNER_PID_FILE = ".owner-pid";
    private String SERVER_BASE_URL = "https://cdn.chat2db-ai.com/download/updates/";
    private String LATEST_VERSION_INFO_URL;
    private Path APP_DIR;
    private Path LOCAL_VERSION_FILE;
    private Path TMP_DIR;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private UpdateProgressDialog progressDialog;
    private static volatile Updater instance;
    private final Map<String, Path> downloadedFilesMap = new HashMap<>();
    private final Object updateOperationLock = new Object();
    private CheckResult checkResult = new CheckResult();
    private CompletableFuture<CheckResult> activeCheck;
    private boolean downloadInProgress;
    private boolean installationInProgress;
    private boolean updateReadyToInstall;
    private final RestartCoordinator restartCoordinator = new RestartCoordinator();

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
        if (ConfigUtils.isCommunity()) {
            this.SERVER_BASE_URL = "https://cdn.chat2db-ai.com/community/updates/";
        } else if (ConfigUtils.isLocalEdition()) {
            this.SERVER_BASE_URL = "https://cdn.chat2db-ai.com/offline/updates/";
        }
        this.LATEST_VERSION_INFO_URL = SERVER_BASE_URL + "latest_version.json";
        this.APP_DIR = Paths.get(OSOperateUtil.getCurrentJarPath());
        this.TMP_DIR = APP_DIR.resolve("tmp_updater_downloads");
        if (OS.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isEmpty()) {
                localAppData = System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local";
            }
            this.TMP_DIR = Paths.get(localAppData).resolve("tmp_updater_downloads");
        }
        this.LOCAL_VERSION_FILE = APP_DIR.resolve("local_version.json");
    }


    static class UpdateProgressDialog {
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

        CheckResult(boolean needsUpdate, String releaseNotes, List<FileUpdateAction> actions, VersionMetadata remoteMetadata) {
            this(needsUpdate, releaseNotes, actions, remoteMetadata, false, null);
        }
    }

    public void restartApp() throws IOException {
        if (prepareRestart()) {
            System.exit(0);
        }
    }

    public boolean prepareRestart() throws IOException {
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
        return restartCoordinator.prepare(() -> new ProcessBuilder(command).start());
    }

    public void exitCurrentProcessAfterResponse() {
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


    public CheckResult appCheckUpdate() {
        CompletableFuture<CheckResult> checkFuture;
        boolean startCheck = false;
        synchronized (updateOperationLock) {
            if (updateReadyToInstall || downloadInProgress || installationInProgress) {
                log.info("Skip update check because another update operation is active.");
                return checkResult;
            }
            if (activeCheck == null) {
                activeCheck = new CompletableFuture<>();
                startCheck = true;
            }
            checkFuture = activeCheck;
        }
        if (startCheck) {
            CheckResult result = checkForLatestVersion();
            synchronized (updateOperationLock) {
                checkResult = result;
                checkFuture.complete(result);
                activeCheck = null;
            }
        }
        try {
            return checkFuture.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Update check was interrupted", new Object[0], exception);
        } catch (ExecutionException exception) {
            throw new BusinessException("Update check failed", new Object[0], exception.getCause());
        }
    }

    private CheckResult checkForLatestVersion() {
        progressDialog = new UpdateProgressDialog();
        try {
            VersionMetadata localMetadata = loadLocalVersion(false);
            LatestVersionInfo latestRemoteInfo = fetchJson(LATEST_VERSION_INFO_URL, LatestVersionInfo.class);
            validateLatestVersionInfo(latestRemoteInfo);
            String localVersion = localMetadata == null ? null : localMetadata.getVersion();
            String remoteVersion = latestRemoteInfo.getLatestVersion();
            boolean needsUpdate = !isBlank(remoteVersion)
                    && (isBlank(localVersion) || compareVersions(remoteVersion, localVersion) > 0);
            if (!needsUpdate) {
                log.info("Skip update because latest version {} is not newer than local version {}", remoteVersion, localVersion);
            }
            return new CheckResult(needsUpdate, latestRemoteInfo.getReleaseNotes(), Collections.emptyList(), null,
                    false, latestRemoteInfo);
        } catch (Exception exception) {
            log.error("Update check failed: {}", exception.getMessage(), exception);
            progressDialog.appendLog("ERROR: " + exception.getMessage());
            return new CheckResult(false, null, Collections.emptyList(), null, true, null);
        }
    }

    private void validateLatestVersionInfo(LatestVersionInfo latestVersionInfo) throws IOException {
        if (latestVersionInfo == null || isBlank(latestVersionInfo.getLatestVersion())) {
            throw new IOException("Latest update version is blank");
        }
        validateUpdateUrl(latestVersionInfo.getMetadataUrl());
        if (!isBlank(latestVersionInfo.getMetadataSha256())
                && !latestVersionInfo.getMetadataSha256().matches(SHA_256_PATTERN)) {
            throw new IOException("Latest update metadata checksum is invalid");
        }
    }

    static int compareVersions(String version1, String version2) {
        String normalizedVersion1 = normalizeVersion(version1);
        String normalizedVersion2 = normalizeVersion(version2);

        if (normalizedVersion1.equals(normalizedVersion2)) {
            return 0;
        }

        String[] parts1 = normalizedVersion1.split("\\.");
        String[] parts2 = normalizedVersion2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int part1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int part2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (part1 != part2) {
                return Integer.compare(part1, part2);
            }
        }
        return 0;
    }

    private static String normalizeVersion(String version) {
        if (isBlank(version)) {
            return "0";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static int parseVersionPart(String versionPart) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < versionPart.length(); i++) {
            char current = versionPart.charAt(i);
            if (Character.isDigit(current)) {
                digits.append(current);
                continue;
            }
            break;
        }
        if (digits.length() == 0) {
            return 0;
        }
        return Integer.parseInt(digits.toString());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    void validateRemoteMetadata(VersionMetadata metadata) throws IOException {
        if (isBlank(metadata.version)) {
            throw new IOException("Update metadata version is blank");
        }
        if (metadata.files == null || metadata.files.isEmpty()) {
            throw new IOException("Update metadata must declare at least one file");
        }
        Set<String> fileIds = new HashSet<>();
        for (FileInfo file : metadata.files) {
            if (file == null || isBlank(file.id) || !fileIds.add(file.id)) {
                throw new IOException("Update metadata contains a missing or duplicate file id");
            }
            resolveAppRelativePath(file.localTargetName);
            if (file.deleted) {
                continue;
            }
            resolveTemporaryFile(file.serverFileName);
            if (isBlank(file.sha256) || !file.sha256.matches(SHA_256_PATTERN)) {
                throw new IOException("Update metadata has an invalid SHA-256 for " + file.id);
            }
            if (file.fileSizeByte < 0) {
                throw new IOException("Update metadata has a negative file size for " + file.id);
            }
            if (file.fileSizeByte > MAX_SINGLE_DOWNLOAD_BYTES) {
                throw new IOException("Update metadata file exceeds the download limit for " + file.id);
            }
            if (!"jar".equals(file.type) && !"zip".equals(file.type)) {
                throw new IOException("Update metadata has an unsupported file type for " + file.id);
            }
            validateUpdateUrl(file.url);
        }
        try {
            long totalSize = metadata.files.stream()
                    .filter(file -> !file.deleted)
                    .mapToLong(file -> file.fileSizeByte)
                    .reduce(0L, Math::addExact);
            if (totalSize > MAX_TOTAL_DOWNLOAD_BYTES) {
                throw new IOException("Update metadata exceeds the total download limit");
            }
        } catch (ArithmeticException exception) {
            throw new IOException("Update metadata download size overflow", exception);
        }
    }

    Path resolveAppRelativePath(String relativePath) throws IOException {
        if (isBlank(relativePath)) {
            throw new IOException("Update target path is blank");
        }
        Path appDirectory = APP_DIR.toAbsolutePath().normalize();
        Path resolved = appDirectory.resolve(relativePath).normalize();
        if (resolved.equals(appDirectory) || !resolved.startsWith(appDirectory)) {
            throw new IOException("Update target path escapes the application directory: " + relativePath);
        }
        Path current = appDirectory;
        for (Path segment : appDirectory.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Update target path contains a symbolic link: " + relativePath);
            }
        }
        return resolved;
    }

    private Path resolveTemporaryFile(String fileName) throws IOException {
        if (isBlank(fileName) || fileName.contains("/") || fileName.indexOf('\\') >= 0 || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IOException("Update temporary file name is invalid: " + fileName);
        }
        Path temporaryDirectory = TMP_DIR.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(temporaryDirectory)) {
            throw new IOException("Update temporary directory is a symbolic link");
        }
        Path resolved = temporaryDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(temporaryDirectory)) {
            throw new IOException("Update temporary file path escapes the download directory: " + fileName);
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IOException("Update temporary file is a symbolic link: " + fileName);
        }
        return resolved;
    }

    private static void validateUpdateUrl(String value) throws IOException {
        if (isBlank(value)) {
            throw new IOException("Update URL is blank");
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !COMMUNITY_UPDATE_HOST.equalsIgnoreCase(uri.getHost())) {
                throw new IOException("Update URL is outside of the Community update channel");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Update URL is invalid", e);
        }
    }

    private List<FileUpdateAction> determineUpdateActions(VersionMetadata local, VersionMetadata remote) throws IOException, NoSuchAlgorithmException {
        List<FileUpdateAction> actions = new ArrayList<>();
        Map<String, FileInfo> localFilesMap = (local != null && local.files != null) ? local.getFilesAsMap() : new HashMap<>();
        Map<String, FileInfo> remoteFilesMap = remote.getFilesAsMap();

        for (Map.Entry<String, FileInfo> entry : remoteFilesMap.entrySet()) {
            String fileId = entry.getKey();
            FileInfo remoteFile = entry.getValue();
            FileInfo localFileMeta = localFilesMap.get(fileId);
            if (remoteFile.deleted) {
                if (localFileMeta != null) {
                    actions.add(new FileUpdateAction(UpdateActionType.DELETE_OLD, null, localFileMeta, "Explicitly deleted by remote metadata"));
                }
                continue;
            }
            Path actualLocalPath = resolveAppRelativePath(remoteFile.localTargetName);

            if (localFileMeta == null) {
                actions.add(new FileUpdateAction(UpdateActionType.DOWNLOAD_NEW, remoteFile, null, "New file"));
            } else if (!Files.exists(actualLocalPath)) {
                actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta, "File missing on disk"));
            } else if (!Objects.equals(remoteFile.sha256, localFileMeta.sha256)) {
                actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta, "Metadata checksum changed"));
            } else {
                if ("zip".equals(remoteFile.type)) {
                    if (Files.isDirectory(actualLocalPath)) {
                        actions.add(new FileUpdateAction(UpdateActionType.KEEP_LOCAL, remoteFile, localFileMeta, "ZIP directory exists, metadata matches"));
                    } else {
                        actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta, "ZIP directory missing or is not a directory"));
                    }
                } else {
                    if (verifyFileChecksum(actualLocalPath, remoteFile.sha256)) {
                        actions.add(new FileUpdateAction(UpdateActionType.KEEP_LOCAL, remoteFile, localFileMeta, "On-disk checksum matches"));
                    } else {
                        actions.add(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remoteFile, localFileMeta, "On-disk file corrupt or changed"));
                    }
                }
            }
        }

        return actions;
    }


    public Map<String, Path> triggerDownload(ConsoleResult consoleResult) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        synchronized (updateOperationLock) {
            requirePackagedRelease();
            if (activeCheck != null || downloadInProgress || installationInProgress) {
                throw new BusinessException("Another update operation is already in progress.");
            }
            if (updateReadyToInstall) {
                throw new BusinessException("An update has already been downloaded and is ready to install.");
            }
            if (progressDialog == null || checkResult == null || !checkResult.isNeedsUpdate()
                    || checkResult.getLatestVersionInfo() == null) {
                throw new BusinessException("Check for an available update before downloading it.");
            }
            downloadInProgress = true;
        }
        try {
            clearOldBackups(APP_DIR);
            discardDownloadedFiles();
            progressDialog.resetProgressTracker();
            VersionMetadata localMetadata = loadLocalVersion();
            VersionMetadata remoteMetadata = loadMetadataForDownload(checkResult.getLatestVersionInfo());
            List<FileUpdateAction> actions = determineUpdateActions(localMetadata, remoteMetadata);
            checkResult = new CheckResult(true, checkResult.getReleaseNotes(), actions, remoteMetadata, false,
                    checkResult.getLatestVersionInfo());

            long filesToDownload = actions.stream()
                    .filter(a -> a.actionType == UpdateActionType.DOWNLOAD_NEW || a.actionType == UpdateActionType.UPDATE_EXISTING)
                    .count();
            long totalDownloadSizeInBytes;
            try {
                totalDownloadSizeInBytes = actions.stream()
                        .filter(a -> a.actionType == UpdateActionType.DOWNLOAD_NEW || a.actionType == UpdateActionType.UPDATE_EXISTING)
                        .mapToLong(a -> a.remoteFileInfo.fileSizeByte)
                        .reduce(0L, Math::addExact);
            } catch (ArithmeticException exception) {
                throw new IOException("Update download size overflow", exception);
            }
            if (totalDownloadSizeInBytes > MAX_TOTAL_DOWNLOAD_BYTES) {
                throw new IOException("Update exceeds the total download limit");
            }

        if (filesToDownload == 0) {
            progressDialog.appendLog("--- No files to download ---");
            progressDialog.setProgress(100, UpdatedStatus.Updated.getName(), consoleResult);
            updateReadyToInstall = true;
            return new HashMap<>();
        }

        AtomicLong cumulativeBytesDownloaded = new AtomicLong(0);

        if (filesToDownload > 0) {
            progressDialog.setProgress(0, "Initializing update...", consoleResult);
            progressDialog.appendLog("--- Download Phase ---");
            for (FileUpdateAction action : actions) {
                if (action.actionType == UpdateActionType.DOWNLOAD_NEW || action.actionType == UpdateActionType.UPDATE_EXISTING) {
                    FileInfo remoteFile = action.remoteFileInfo;
                    String downloadMsg = "Downloading: " + remoteFile.serverFileName + " (ID: " + remoteFile.id + ")";
                    progressDialog.appendLog(downloadMsg);

                    IProgressListener listener = (bytesWritten) -> {
                        long totalDownloaded = cumulativeBytesDownloaded.addAndGet(bytesWritten);
                        int overallProgress = totalDownloadSizeInBytes > 0
                                ? (int) Math.min(100, (totalDownloaded * 100) / totalDownloadSizeInBytes)
                                : 0;
                        String progressMsg = String.format("Downloading %s (%d%%)",
                                remoteFile.serverFileName,
                                overallProgress);
                        progressDialog.setProgress(overallProgress, progressMsg, consoleResult);
                    };

                    Path downloadedPath = downloadFile(remoteFile.url, remoteFile.serverFileName, remoteFile.sha256,
                            remoteFile.fileSizeByte, listener);
                    downloadedFilesMap.put(remoteFile.id, downloadedPath);
                    progressDialog.appendLog("Downloaded and verified: " + remoteFile.serverFileName);
                }
            }
            progressDialog.appendLog("--- Download Phase Complete ---");
        } else {
            progressDialog.appendLog("--- No files to download ---");
        }
        progressDialog.setProgress(100, UpdatedStatus.Updated.getName(), consoleResult);
        updateReadyToInstall = true;
        synchronized (updateOperationLock) {
            downloadInProgress = false;
        }
        LatestVersionInfo latestRemoteInfo = checkResult.getLatestVersionInfo();
        if (Objects.nonNull(latestRemoteInfo)) {
            Boolean forceUpdate = latestRemoteInfo.getForceUpdate();
            if (Boolean.TRUE.equals(forceUpdate)) {
                if (OS.isWindows()) {
                    if (!triggerInstallationWithAuxiliaryProcess()) {
                        throw new IOException("Could not start the Windows updater process");
                    }
                    return downloadedFilesMap;
                }
                if (triggerInstallation()) {
                    restartApp();
                }
            }
        }
        return downloadedFilesMap;
        } finally {
            synchronized (updateOperationLock) {
                downloadInProgress = false;
            }
        }
    }

    private VersionMetadata loadMetadataForDownload(LatestVersionInfo latestVersionInfo) throws IOException {
        VersionMetadata remoteMetadata;
        if (isBlank(latestVersionInfo.getMetadataSha256())) {
            remoteMetadata = fetchJson(latestVersionInfo.getMetadataUrl(), VersionMetadata.class);
        } else {
            byte[] metadataBytes = fetchUpdateJsonBytes(latestVersionInfo.getMetadataUrl());
            try {
                String actualSha256 = bytesToHex(MessageDigest.getInstance("SHA-256").digest(metadataBytes));
                if (!actualSha256.equalsIgnoreCase(latestVersionInfo.getMetadataSha256())) {
                    throw new IOException("Update metadata checksum does not match latest version information");
                }
            } catch (NoSuchAlgorithmException exception) {
                throw new IOException("SHA-256 is unavailable for update metadata validation", exception);
            }
            remoteMetadata = objectMapper.readValue(metadataBytes, VersionMetadata.class);
        }
        if (remoteMetadata == null) {
            throw new IOException("Could not fetch metadata for version " + latestVersionInfo.getLatestVersion());
        }
        if (!latestVersionInfo.getLatestVersion().equals(remoteMetadata.getVersion())) {
            throw new IOException("Update metadata version does not match latest version information");
        }
        validateRemoteMetadata(remoteMetadata);
        return remoteMetadata;
    }

    private byte[] fetchUpdateJsonBytes(String urlString) throws IOException {
        HttpURLConnection connection = openUpdateConnection(urlString, 30000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Failed to fetch update metadata: HTTP " + connection.getResponseCode());
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 1024 * 1024) {
                throw new IOException("Update metadata exceeds the size limit");
            }
            try (InputStream inputStream = connection.getInputStream(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                inputStream.transferTo(outputStream);
                if (outputStream.size() > 1024 * 1024) {
                    throw new IOException("Update metadata exceeds the size limit");
                }
                return outputStream.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    public boolean triggerInstallation() {
        synchronized (updateOperationLock) {
            requirePackagedRelease();
            if (downloadInProgress || installationInProgress) {
                throw new BusinessException("Update installation is already in progress.");
            }
            if (!updateReadyToInstall) {
                throw new BusinessException("No downloaded update is ready to install.");
            }
            installationInProgress = true;
        }
        List<Runnable> rollbackOperations = new ArrayList<>();
        Path backupSession = null;
        boolean installationSucceeded = false;
        try {
            List<FileUpdateAction> actions = requireUpdateActions();
            long filesToApplyOrDelete = actions.stream()
                    .filter(a -> a.actionType != UpdateActionType.KEEP_LOCAL)
                    .count();
            backupSession = createBackupSession();
            final Path currentBackupSession = backupSession;
            progressDialog.appendLog("Starting update execution phase...");
            if (filesToApplyOrDelete > 0) {
                progressDialog.appendLog("--- Apply Phase ---");
                for (FileUpdateAction action : actions) {
                    if (action.actionType == UpdateActionType.KEEP_LOCAL) {
                        continue;
                    }
                    FileInfo remoteFile = action.remoteFileInfo;
                    FileInfo localFileMeta = action.localFileInfo;
                    Path targetLocalPath = resolveAppRelativePath(remoteFile != null ? remoteFile.localTargetName : localFileMeta.localTargetName);

                    if (targetLocalPath.getParent() != null) {
                        Files.createDirectories(targetLocalPath.getParent());
                    }

                    String currentOpDisplay = "";

                    switch (action.actionType) {
                        case DOWNLOAD_NEW:
                        case UPDATE_EXISTING:
                            assert remoteFile != null;
                            currentOpDisplay = "Installing: " + remoteFile.localTargetName;
                            progressDialog.appendLog(currentOpDisplay);
                            Path sourcePath = downloadedFilesMap.get(remoteFile.id);
                            if (sourcePath == null)
                                throw new IOException("Downloaded file not found in map for ID: " + remoteFile.id);
                            Path stagedZipContent = null;
                            Path zipStagingDirectory = null;
                            if ("zip".equals(remoteFile.type)) {
                                zipStagingDirectory = Files.createTempDirectory(targetLocalPath.getParent(), ".update-stage-");
                                final Path finalZipStagingDirectory = zipStagingDirectory;
                                rollbackOperations.add(() -> {
                                    try {
                                        deleteDirectoryRecursively(finalZipStagingDirectory);
                                    } catch (IOException e) {
                                        progressDialog.appendLog("ERROR during staged ZIP cleanup: " + e.getMessage());
                                    }
                                });
                                extractZip(sourcePath, zipStagingDirectory, targetLocalPath.getFileName().toString());
                                stagedZipContent = zipStagingDirectory.resolve(targetLocalPath.getFileName());
                                if (!Files.isDirectory(stagedZipContent)) {
                                    throw new IOException("ZIP archive does not contain the expected directory: " + targetLocalPath.getFileName());
                                }
                            }
                            if (Files.exists(targetLocalPath)) {
                                Path backupPath = resolveBackupPath(currentBackupSession, targetLocalPath);
                                Files.createDirectories(backupPath.getParent());
                                progressDialog.appendLog("Backing up " + targetLocalPath.getFileName() + " to " + backupPath.getFileName());
                                Files.move(targetLocalPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                                final Path finalBackupPath = backupPath;
                                final Path finalTargetLocalPath = targetLocalPath;
                                rollbackOperations.add(() -> {
                                    try {
                                        progressDialog.appendLog("Rollback: Restoring " + finalBackupPath.getFileName() + " to " + finalTargetLocalPath.getFileName());
                                        if (Files.exists(finalTargetLocalPath)) {
                                            if (Files.isDirectory(finalTargetLocalPath))
                                                deleteDirectoryRecursively(finalTargetLocalPath);
                                            else Files.delete(finalTargetLocalPath);
                                        }
                                        Files.move(finalBackupPath, finalTargetLocalPath, StandardCopyOption.REPLACE_EXISTING);
                                    } catch (IOException e) {
                                        progressDialog.appendLog("ERROR during rollback move: " + e.getMessage());
                                    }
                                });
                            } else {
                                final Path finalTargetLocalPath = targetLocalPath;
                                rollbackOperations.add(() -> {
                                    try {
                                        progressDialog.appendLog("Rollback: Deleting newly placed " + finalTargetLocalPath.getFileName());
                                        if (Files.exists(finalTargetLocalPath)) {
                                            if (Files.isDirectory(finalTargetLocalPath))
                                                deleteDirectoryRecursively(finalTargetLocalPath);
                                            else Files.delete(finalTargetLocalPath);
                                        }
                                    } catch (IOException e) {
                                        progressDialog.appendLog("ERROR during rollback delete: " + e.getMessage());
                                    }
                                });
                            }
                            if ("zip".equals(remoteFile.type)) {
                                progressDialog.appendLog("Installing staged ZIP " + sourcePath.getFileName() + " to " + targetLocalPath.getFileName());
                                moveIntoPlace(stagedZipContent, targetLocalPath);
                                Files.delete(sourcePath);
                                deleteDirectoryRecursively(zipStagingDirectory);
                            } else {
                                progressDialog.appendLog("Moving " + sourcePath.getFileName() + " to " + targetLocalPath.getFileName());
                                moveIntoPlace(sourcePath, targetLocalPath);
                            }
                            progressDialog.appendLog("Applied: " + remoteFile.localTargetName);
                            break;

                        case DELETE_OLD:
                            assert localFileMeta != null;
                            currentOpDisplay = "Deleting: " + localFileMeta.localTargetName;
                            progressDialog.appendLog(currentOpDisplay);
                            Path pathToDelete = resolveAppRelativePath(localFileMeta.localTargetName);
                            if (Files.exists(pathToDelete)) {
                                Path deleteBackupPath = resolveBackupPath(currentBackupSession, pathToDelete);
                                Files.createDirectories(deleteBackupPath.getParent());
                                progressDialog.appendLog("Backing up deleted file " + pathToDelete.getFileName()
                                        + " to " + deleteBackupPath.getFileName());
                                Files.move(pathToDelete, deleteBackupPath, StandardCopyOption.REPLACE_EXISTING);
                                final Path finalDeleteBackupPath = deleteBackupPath;
                                final Path finalPathToDelete = pathToDelete;
                                rollbackOperations.add(() -> {
                                    try {
                                        progressDialog.appendLog("Rollback: Restoring deleted " + finalPathToDelete.getFileName());
                                        if (Files.exists(finalPathToDelete)) {
                                            if (Files.isDirectory(finalPathToDelete)) {
                                                deleteDirectoryRecursively(finalPathToDelete);
                                            } else {
                                                Files.delete(finalPathToDelete);
                                            }
                                        }
                                        Files.move(finalDeleteBackupPath, finalPathToDelete, StandardCopyOption.REPLACE_EXISTING);
                                    } catch (IOException e) {
                                        progressDialog.appendLog("ERROR during rollback restore: " + e.getMessage());
                                    }
                                });
                                progressDialog.appendLog("Staged deletion: " + localFileMeta.localTargetName);
                            } else {
                                progressDialog.appendLog("Skipped delete (already gone): " + localFileMeta.localTargetName);
                            }
                            break;
                    }
                }
                progressDialog.appendLog("--- Apply Phase Complete ---");
            } else {
                progressDialog.appendLog("--- No files to apply or delete ---");
            }
            saveLocalVersion(checkResult.getRemoteMetadata());
            downloadedFilesMap.clear();
            checkResult = new CheckResult();
            updateReadyToInstall = false;
            installationSucceeded = true;
            return true;
        } catch (Exception e) {
            log.error("Failed to execute update action", e);
            progressDialog.appendLog("ERROR during update execution: " + e.getMessage());
            for (int i = rollbackOperations.size() - 1; i >= 0; i--) {
                try {
                    rollbackOperations.get(i).run();
                } catch (Exception re) {
                    progressDialog.appendLog("ERROR during rollback operation: " + re.getMessage());
                }
            }
            return false;
        } finally {
            discardDownloadedFiles();
            updateReadyToInstall = false;
            synchronized (updateOperationLock) {
                installationInProgress = false;
            }
            if (!installationSucceeded && backupSession != null) {
                try {
                    deleteDirectoryRecursively(backupSession);
                } catch (IOException exception) {
                    log.warn("Failed to clean incomplete update backup session: {}", backupSession, exception);
                }
            }
        }
    }

    private Path createBackupSession() throws IOException {
        Path backupRoot = resolveAppRelativePath(BACKUP_DIRECTORY_NAME);
        Path session = Files.createDirectories(backupRoot.resolve(UUID.randomUUID().toString()));
        Files.writeString(session.resolve(BACKUP_OWNER_PID_FILE), Long.toString(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE_NEW);
        return session;
    }

    private Path resolveBackupPath(Path backupSession, Path targetPath) throws IOException {
        Path appDirectory = APP_DIR.toAbsolutePath().normalize();
        Path relativeTarget = appDirectory.relativize(targetPath.toAbsolutePath().normalize());
        Path backupPath = backupSession.resolve(relativeTarget).normalize();
        if (!backupPath.startsWith(backupSession)) {
            throw new IOException("Update backup path escapes the backup session");
        }
        return backupPath;
    }

    void clearOldBackups(Path baseDir) {
        Path backupDirectory = baseDir.toAbsolutePath().normalize().resolve(BACKUP_DIRECTORY_NAME);
        if (Files.isSymbolicLink(backupDirectory) || !Files.isDirectory(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(backupDirectory)) {
                log.warn("Keeping symbolic-link update backup directory: {}", backupDirectory);
            }
            return;
        }
        List<Path> backupSessions;
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            backupSessions = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        } catch (IOException e) {
            if (progressDialog != null) {
                progressDialog.appendLog("ERROR: Could not list old backups: " + e.getMessage());
            }
            return;
        }
        for (Path backupSession : backupSessions) {
            try {
                if (!isBackupFromPreviousProcess(backupSession)) {
                    continue;
                }
                if (progressDialog != null) {
                    progressDialog.appendLog("Deleting completed update backup session: " + backupSession.getFileName());
                }
                if (Files.isDirectory(backupSession)) {
                    deleteDirectoryRecursively(backupSession);
                } else {
                    Files.deleteIfExists(backupSession);
                }
            } catch (IOException e) {
                if (progressDialog != null) {
                    progressDialog.appendLog("ERROR: Failed to delete update backup session " + backupSession.getFileName() + ": " + e.getMessage());
                }
            }
        }
        try (Stream<Path> remainingSessions = Files.list(backupDirectory)) {
            if (!remainingSessions.findAny().isPresent()) {
                Files.deleteIfExists(backupDirectory);
            }
        } catch (IOException e) {
            if (progressDialog != null) {
                progressDialog.appendLog("ERROR: Failed to delete empty update backup directory: " + e.getMessage());
            }
        }
    }

    private boolean isBackupFromPreviousProcess(Path backupSession) {
        Path ownerPidFile = backupSession.resolve(BACKUP_OWNER_PID_FILE);
        try {
            if (!Files.isRegularFile(ownerPidFile)) {
                return false;
            }
            return Long.parseLong(Files.readString(ownerPidFile).trim()) != ProcessHandle.current().pid();
        } catch (IOException | NumberFormatException exception) {
            log.warn("Keeping update backup session with an invalid owner marker: {}", backupSession, exception);
            return false;
        }
    }

    private VersionMetadata loadLocalVersion() {
        return loadLocalVersion(true);
    }

    private VersionMetadata loadLocalVersion(boolean repairCorruptFile) {
        if (Files.exists(LOCAL_VERSION_FILE)) {
            if (progressDialog != null) progressDialog.appendLog("Loading local version from: " + LOCAL_VERSION_FILE);
            try (InputStream is = Files.newInputStream(LOCAL_VERSION_FILE)) {
                return objectMapper.readValue(is, VersionMetadata.class);
            } catch (Exception e) {
                String errorMsg = "Failed to load local_version.json: " + e.getMessage() + ". Assuming no local version.";
                if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
                log.error(errorMsg);
                if (repairCorruptFile) {
                    try {
                        Files.move(LOCAL_VERSION_FILE, LOCAL_VERSION_FILE.resolveSibling("local_version.json.corrupted_" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException moveEx) {
                        log.error("Could not rename corrupted local_version.json: {}", moveEx.getMessage());
                    }
                }
                return null;
            }
        }
        if (progressDialog != null) progressDialog.appendLog("Local version file not found: " + LOCAL_VERSION_FILE);
        return null;
    }

    public void saveLocalVersion(VersionMetadata metadata) throws IOException {
        String msg = "Saving local_version.json for version " + metadata.version + " to: " + LOCAL_VERSION_FILE.toAbsolutePath();
        if (progressDialog != null) progressDialog.appendLog(msg);
        log.info(msg);
        try (OutputStream os = Files.newOutputStream(LOCAL_VERSION_FILE)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(os, metadata);
        }
    }

    private <T> T fetchJson(String urlString, Class<T> clazz) throws IOException {
        if (progressDialog != null) progressDialog.appendLog("Fetching JSON: " + urlString);
        if (urlString.toLowerCase().startsWith("file:")) {
            URL fileUrl = URI.create(urlString).toURL();
            try (InputStream inputStream = fileUrl.openStream()) {
                return objectMapper.readValue(inputStream, clazz);
            } catch (FileNotFoundException e) {
                String errorMsg = "Local JSON file not found: " + urlString;
                if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
                log.error(errorMsg);
                return null;
            }
        } else if (urlString.startsWith("/")) {
            Path localPath = Paths.get(urlString);
            if (Files.exists(localPath)) {
                try (InputStream inputStream = Files.newInputStream(localPath)) {
                    return objectMapper.readValue(inputStream, clazz);
                }
            } else {
                String errorMsg = "Local JSON file (absolute path) not found: " + urlString;
                if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
                log.error(errorMsg);
                return null;
            }
        }

        HttpURLConnection connection = openUpdateConnection(urlString, 30000);
        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (InputStream inputStream = connection.getInputStream()) {
                return objectMapper.readValue(inputStream, clazz);
            }
        } else {
            String errorMsg = "Failed to fetch JSON from " + urlString + ". Status: " + responseCode + " " + connection.getResponseMessage();
            if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
            log.error(errorMsg);
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    String errorDetails = new String(errorStream.readAllBytes());
                    log.error("Error details: {}", errorDetails);
                    if (progressDialog != null)
                        progressDialog.appendLog("Server error details: " + errorDetails.substring(0, Math.min(errorDetails.length(), 100)) + "...");

                }
            } catch (IOException ex) {   }
            throw new IOException(errorMsg);
        }
    }


    private Path downloadFile(String urlString, String targetFileNameInTmp, String expectedSha256, long expectedSize,
                              IProgressListener progressListener) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        validateUpdateUrl(urlString);
        Path targetPath = resolveTemporaryFile(targetFileNameInTmp);
        Path partialPath = resolvePartialDownloadFile(targetPath);
        Files.createDirectories(targetPath.getParent());
        if (Files.exists(targetPath)) {
            if (progressDialog != null) {
                progressDialog.appendLog("File already exists, verifying: " + targetFileNameInTmp);
            }
            if (verifyFileChecksum(targetPath, expectedSha256)) {
                if (Files.size(targetPath) != expectedSize) {
                    Files.deleteIfExists(targetPath);
                    throw new IOException("Existing update file size does not match metadata");
                }
                if (progressDialog != null) {
                    progressDialog.appendLog("Checksum matches. Skipping download.");
                }
                if (progressListener != null) {
                    try {
                        long fileSize = Files.size(targetPath);
                        progressListener.onProgress(fileSize);
                    } catch (IOException e) {
                    }
                }
                return targetPath;
            } else {
                if (progressDialog != null) {
                    progressDialog.appendLog("Checksum mismatch. Re-downloading...");
                }
                Files.deleteIfExists(targetPath);
            }
        }

        long existingBytes = Files.exists(partialPath) ? Files.size(partialPath) : 0L;
        if (existingBytes > expectedSize) {
            if (progressDialog != null) {
                progressDialog.appendLog("Partial download exceeds expected size. Starting over.");
            }
            Files.deleteIfExists(partialPath);
            existingBytes = 0L;
        }
        if (existingBytes == expectedSize) {
            if (verifyFileChecksum(partialPath, expectedSha256)) {
                moveIntoPlace(partialPath, targetPath);
                if (progressListener != null) {
                    progressListener.onProgress(existingBytes);
                }
                return targetPath;
            }
            Files.deleteIfExists(partialPath);
            existingBytes = 0L;
        }

        if (progressDialog != null) {
            progressDialog.appendLog("Starting download: " + targetFileNameInTmp + " from " + urlString);
        }

        String actualSha256 = null;

        if (urlString.toLowerCase().startsWith("file:")) {
            URL fileUrl = URI.create(urlString).toURL();
            Path sourcePath = Paths.get(fileUrl.toURI());
            if (!Files.exists(sourcePath)) {
                throw new FileNotFoundException("Source file not found for local download: " + sourcePath);
            }
            if (progressDialog != null) {
                progressDialog.appendLog("Copying local file " + sourcePath.getFileName() + " to " + targetPath.getFileName());
            }
            try (InputStream in = Files.newInputStream(sourcePath);
                 OutputStream out = Files.newOutputStream(partialPath)) {
                copyWithLimit(in, out, expectedSize, progressListener);
            }
        } else {
            if (progressDialog != null) progressDialog.appendLog("Downloading remote file " + targetFileNameInTmp);
            HttpURLConnection connection = openUpdateConnection(urlString, 120000,
                    existingBytes == 0 ? Collections.emptyMap() : Map.of("Range", "bytes=" + existingBytes + "-"));
            int responseCode = connection.getResponseCode();
            boolean append = isPartialResponseForOffset(existingBytes, expectedSize, responseCode,
                    connection.getHeaderField("Content-Range"), connection.getContentLengthLong());
            if (existingBytes > 0 && !append) {
                if (progressDialog != null) {
                    progressDialog.appendLog("Update server cannot resume this download. Starting over.");
                }
                connection.disconnect();
                Files.deleteIfExists(partialPath);
                existingBytes = 0L;
                connection = openUpdateConnection(urlString, 120000);
                responseCode = connection.getResponseCode();
            }
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new IOException("Failed to download update file. Status: " + responseCode);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_SINGLE_DOWNLOAD_BYTES) {
                connection.disconnect();
                throw new IOException("Update file exceeds the download limit");
            }
            long expectedRemainingBytes = expectedSize - existingBytes;
            if (contentLength >= 0 && contentLength != expectedRemainingBytes) {
                connection.disconnect();
                throw new IOException("Update file size does not match metadata");
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            if (existingBytes > 0) {
                updateDigestFromFile(partialPath, sha256);
                if (progressListener != null) {
                    progressListener.onProgress(existingBytes);
                }
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = Files.newOutputStream(partialPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                byte[] buffer = new byte[8192];
                long bytesWritten = existingBytes;
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    bytesWritten = Math.addExact(bytesWritten, bytesRead);
                    if (bytesWritten > MAX_SINGLE_DOWNLOAD_BYTES || bytesWritten > expectedSize) {
                        throw new IOException("Update file exceeds its declared size");
                    }
                    out.write(buffer, 0, bytesRead);
                    sha256.update(buffer, 0, bytesRead);
                    if (progressListener != null) {
                        progressListener.onProgress(bytesRead);
                    }
                }
                if (bytesWritten != expectedSize) {
                    throw new IOException("Update file size does not match metadata");
                }
            }
            byte[] hash = sha256.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            actualSha256 = hexString.toString();
        }

        if (Files.size(partialPath) != expectedSize) {
            Files.deleteIfExists(partialPath);
            throw new IOException("Update file size does not match metadata");
        }

        if (progressDialog != null) progressDialog.appendLog("Verifying checksum for " + targetFileNameInTmp);

        boolean checksumVerified;
        if (actualSha256 != null) {
            checksumVerified = actualSha256.equalsIgnoreCase(expectedSha256);
        } else {
            checksumVerified = verifyFileChecksum(partialPath, expectedSha256);
        }

        if (!checksumVerified) {
            Files.deleteIfExists(partialPath);
            throw new IOException("Checksum mismatch for " + partialPath.getFileName() + ". Expected: " + expectedSha256 + ", Actual: " + (actualSha256 != null ? actualSha256 : "re-calculated"));
        }

        moveIntoPlace(partialPath, targetPath);

        if (progressDialog != null) {
            progressDialog.appendLog("Download & verification complete: " + targetFileNameInTmp);
        }

        return targetPath;
    }

    private static Path partialDownloadPath(Path targetPath) {
        return targetPath.resolveSibling(targetPath.getFileName() + PARTIAL_DOWNLOAD_SUFFIX);
    }

    private Path resolvePartialDownloadFile(Path targetPath) throws IOException {
        Path partialPath = partialDownloadPath(targetPath);
        Path temporaryDirectory = TMP_DIR.toAbsolutePath().normalize();
        if (!partialPath.startsWith(temporaryDirectory) || Files.isSymbolicLink(partialPath)) {
            throw new IOException("Update partial download file is unsafe: " + partialPath.getFileName());
        }
        return partialPath;
    }

    static boolean isPartialResponseForOffset(long existingBytes, long expectedSize, int responseCode,
                                              String contentRange, long contentLength) {
        if (existingBytes <= 0 || responseCode != HttpURLConnection.HTTP_PARTIAL || contentRange == null) {
            return false;
        }
        String expectedPrefix = "bytes " + existingBytes + "-";
        String expectedSuffix = "/" + expectedSize;
        return contentRange.startsWith(expectedPrefix)
                && contentRange.endsWith(expectedSuffix)
                && (contentLength < 0 || contentLength == expectedSize - existingBytes);
    }

    private static void updateDigestFromFile(Path path, MessageDigest digest) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
    }

    private void copyWithLimit(InputStream inputStream, OutputStream outputStream, long expectedSize,
                               IProgressListener progressListener) throws IOException {
        byte[] buffer = new byte[8192];
        long bytesWritten = 0;
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            bytesWritten = Math.addExact(bytesWritten, bytesRead);
            if (bytesWritten > MAX_SINGLE_DOWNLOAD_BYTES || bytesWritten > expectedSize) {
                throw new IOException("Update file exceeds its declared size");
            }
            outputStream.write(buffer, 0, bytesRead);
            if (progressListener != null) {
                progressListener.onProgress(bytesRead);
            }
        }
        if (bytesWritten != expectedSize) {
            throw new IOException("Update file size does not match metadata");
        }
    }

    private HttpURLConnection openUpdateConnection(String urlString, int readTimeoutMs) throws IOException {
        return openUpdateConnection(urlString, readTimeoutMs, Collections.emptyMap());
    }

    private HttpURLConnection openUpdateConnection(String urlString, int readTimeoutMs,
                                                   Map<String, String> requestHeaders) throws IOException {
        URI currentUri;
        try {
            currentUri = URI.create(urlString);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Update URL is invalid", exception);
        }
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            validateUpdateUrl(currentUri.toString());
            HttpURLConnection connection = (HttpURLConnection) currentUri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", "JavaUpdater/1.0");
            connection.setRequestProperty("Referer", "https://chat2db.ai");
            requestHeaders.forEach(connection::setRequestProperty);
            int responseCode = connection.getResponseCode();
            if (responseCode < HttpURLConnection.HTTP_MULT_CHOICE || responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (isBlank(location)) {
                throw new IOException("Update server returned a redirect without a location");
            }
            try {
                currentUri = currentUri.resolve(location).normalize();
            } catch (IllegalArgumentException exception) {
                throw new IOException("Update server returned an invalid redirect", exception);
            }
        }
        throw new IOException("Update server exceeded the redirect limit");
    }

    private boolean verifyFileChecksum(Path filePath, String expectedSha256) throws IOException, NoSuchAlgorithmException {
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            String errorMsg = "Cannot verify checksum, file does not exist or is a directory: " + filePath;
            if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
            log.error(errorMsg);
            return false;
        }
        if (progressDialog != null) progressDialog.appendLog("Verifying: " + filePath.getFileName());
        MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(filePath))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                sha256Digest.update(buffer, 0, bytesRead);
            }
        }
        String actualSha256 = bytesToHex(sha256Digest.digest());
        boolean match = actualSha256.equalsIgnoreCase(expectedSha256);
        if (!match) {
            String errorMsg = "Checksum mismatch for " + filePath + ". Expected: " + expectedSha256 + ", Got: " + actualSha256;
            if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
            log.error(errorMsg);
        } else {
            if (progressDialog != null) progressDialog.appendLog("Checksum OK: " + filePath.getFileName());
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
        Files.createDirectories(destDir);
        byte[] buffer = new byte[8192];
        int entryCount = 0;
        long extractedBytes = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("ZIP archive has too many entries");
                }
                String entryName = zipEntry.getName();
                int separator = entryName.indexOf('/');
                String topLevelDirectory = separator >= 0 ? entryName.substring(0, separator) : entryName;
                if (!expectedTopLevelDirectory.equals(topLevelDirectory)) {
                    throw new IOException("ZIP entry is outside of the expected top-level directory: " + entryName);
                }
                Path newFile = destDir.resolve(zipEntry.getName()).normalize();
                if (!newFile.startsWith(destDir.normalize())) {
                    throw new IOException("Zip entry is outside of the target dir: " + zipEntry.getName());
                }
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(newFile);
                } else {
                    Files.createDirectories(newFile.getParent());
                    try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(newFile))) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            extractedBytes += len;
                            if (extractedBytes > MAX_ZIP_UNCOMPRESSED_BYTES) {
                                throw new IOException("ZIP archive exceeds the uncompressed size limit");
                            }
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
                zipEntry = zis.getNextEntry();
            }
        }
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        deleteDirectoryRecursively(entry);
                    }
                }
            }
            Files.delete(path);
        }
    }
    public boolean triggerInstallationWithAuxiliaryProcess() {
        synchronized (updateOperationLock) {
            requirePackagedRelease();
            if (downloadInProgress || installationInProgress) {
                throw new BusinessException("Update installation is already in progress.");
            }
            if (!updateReadyToInstall) {
                throw new BusinessException("No downloaded update is ready to install.");
            }
            installationInProgress = true;
        }
        progressDialog.appendLog("Preparing for update via auxiliary process...");
        try {
            UpdatePlan plan = new UpdatePlan();
            plan.setTasks(requireUpdateActions());
            plan.setRemoteMetadata(checkResult.getRemoteMetadata());
            plan.setDownloadedFiles(downloadedFilesMap.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toAbsolutePath().toString())));
            Path planPath = Files.createTempFile("chat2db-update-plan-", ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
            progressDialog.appendLog("Update plan created at: " + planPath);
            Path updaterJarPath = APP_DIR.resolve("updater.jar");
            if (!Files.exists(updaterJarPath)) {
                throw new FileNotFoundException("Updater executable not found at: " + updaterJarPath);
            }

            String java_home = System.getProperty("java.home");
            String javaExecutable = Paths.get(java_home, "bin", "java.exe").toAbsolutePath().toString();
            ProcessBuilder pb = getProcessBuilder(javaExecutable, updaterJarPath, planPath);
            log.info("Launching updater with command: {}", String.join(" ", pb.command()));
            progressDialog.appendLog("Launching updater process. The application will now close.");
            pb.start();
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
            return true;

        } catch (Exception e) {
            log.error("Failed to launch auxiliary updater process", e);
            progressDialog.appendLog("FATAL ERROR: Could not start the update process. " + e.getMessage());
            synchronized (updateOperationLock) {
                installationInProgress = false;
            }
            return false;
        }
    }

    private @NotNull ProcessBuilder getProcessBuilder(String javaExecutable, Path updaterJarPath, Path planPath) {
        String restartUri = "chat2db-pro://restart";
        if (ConfigUtils.isCommunity()) {
            restartUri = "chat2db-community://restart";
        } else if (ConfigUtils.isLocalEdition()) {
            restartUri = "chat2db-local://restart";
        }
        ProcessBuilder pb = new ProcessBuilder(
                "wscript.exe",
                APP_DIR.resolve("run-as-admin.vbs").toAbsolutePath().toString(),
                javaExecutable,
                updaterJarPath.toAbsolutePath().toString(),
                planPath.toAbsolutePath().toString(),
                APP_DIR.toAbsolutePath().toString(),
                restartUri
        );

        pb.redirectErrorStream(true);
        return pb;
    }

    List<FileUpdateAction> requireUpdateActions() throws IOException {
        if (checkResult == null || checkResult.getActions() == null) {
            throw new IOException("Update plan is incomplete: update actions are missing.");
        }
        return checkResult.getActions();
    }

    private void requirePackagedRelease() {
        if (!isSelfUpdateSupported(ConfigUtils.isRelease(), Boolean.getBoolean(WEB_FRONTEND_PROPERTY))) {
            throw new BusinessException("Self-update is only available from an installed desktop release.");
        }
    }

    private void discardDownloadedFiles() {
        downloadedFilesMap.values().forEach(tempFile -> {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                log.warn("Failed to delete temporary download file: {}", tempFile, ex);
            }
        });
        downloadedFilesMap.clear();
    }

    static boolean isSelfUpdateSupported(boolean releaseProfile, boolean webFrontend) {
        return releaseProfile && !webFrontend;
    }


    public static void updateVersionInFile(String newVersion) {
        String filePath = Paths.get(OSOperateUtil.getCurrentJarPath()).resolve("../info.plist").toString();
        log.info("Start updating the version number in the file...");
        log.info("VERSION FILE_PATH: {}", filePath);
        log.info("new app version: {}", newVersion);

        try {
            Path file = Paths.get(filePath);
            if (!Files.exists(file) || !Files.isReadable(file)) {
                log.info("Error: The file does not exist or is unreadable: {}", filePath);
                return;
            }
            Path backupFile = Paths.get(filePath + ".bak");
            Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("A backup of the original file has been created: {}", backupFile);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String shortVersionRegex = "(<key>CFBundleShortVersionString</key>\\s*<string>)[^<]+(</string>)";
            String bundleVersionRegex = "(<key>CFBundleVersion</key>\\s*<string>)[^<]+(</string>)";

            String updatedContent = content.replaceAll(shortVersionRegex, "$1" + newVersion + "$2");
            updatedContent = updatedContent.replaceAll(bundleVersionRegex, "$1" + newVersion + "$2");
            Files.writeString(file, updatedContent, StandardCharsets.UTF_8);

            log.info("The version number in the file has been updated ");

        } catch (IOException e) {
            log.error("Error: An IO error occurred while updating a file", e);
        }
    }
}
