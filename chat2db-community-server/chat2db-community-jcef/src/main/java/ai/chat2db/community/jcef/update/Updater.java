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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;


@Slf4j
@Component
@NotCliRuntime
public class Updater {

    private String SERVER_BASE_URL = "https://cdn.chat2db-ai.com/download/updates/";
    private String LATEST_VERSION_INFO_URL;
    private Path APP_DIR;
    private Path LOCAL_VERSION_FILE;
    private Path TMP_DIR;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private UpdateProgressDialog progressDialog;
    private static volatile Updater instance;
    private static final Map<String, Path> downloadedFilesMap = new HashMap<>();
    private static CheckResult checkResult = new CheckResult();
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
        progressDialog = new UpdateProgressDialog();
        try {
            VersionMetadata localMetadata = loadLocalVersion();
            LatestVersionInfo latestRemoteInfo = fetchJson(LATEST_VERSION_INFO_URL, LatestVersionInfo.class);

            if (latestRemoteInfo == null) {
                throw new BusinessException("Could not fetch latest version information from server.");
            }
            VersionMetadata remoteMetadata = fetchJson(latestRemoteInfo.metadataUrl, VersionMetadata.class);
            if (remoteMetadata == null) {
                log.warn("Could not fetch metadata for version {}", latestRemoteInfo.latestVersion);
                return new CheckResult(false, null, null, null);
            }
            String localVersion = localMetadata == null ? null : localMetadata.getVersion();
            String remoteVersion = firstNonBlank(remoteMetadata.getVersion(), latestRemoteInfo.getLatestVersion());
            if (isBlank(remoteVersion)) {
                log.warn("Remote version is blank, skip update check.");
                return new CheckResult(false, null, Collections.emptyList(), remoteMetadata);
            }
            if (!isBlank(localVersion) && compareVersions(remoteVersion, localVersion) <= 0) {
                log.info("Skip update because remote version {} is not higher than local version {}", remoteVersion, localVersion);
                return new CheckResult(false, null, null, null);
            }
            List<FileUpdateAction> actions = determineUpdateActions(localMetadata, remoteMetadata);
            actions.forEach(action -> progressDialog.appendLog("- " + action.toString()));
            boolean needsUpdate = actions.stream()
                    .anyMatch(a -> a.actionType == UpdateActionType.DOWNLOAD_NEW
                            || a.actionType == UpdateActionType.UPDATE_EXISTING
                            || a.actionType == UpdateActionType.DELETE_OLD
                    );
            String releaseNotes = remoteMetadata.releaseNotes;
            checkResult = new CheckResult(needsUpdate, releaseNotes, actions, remoteMetadata);
        } catch (Exception e) {
            log.error("Update check/process failed: {}", e.getMessage(), e);
            progressDialog.appendLog("ERROR: " + e.getMessage());
            checkResult = new CheckResult(false, e.getMessage(), Collections.emptyList(), null);
        }
        return checkResult;
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

    private List<FileUpdateAction> determineUpdateActions(VersionMetadata local, VersionMetadata remote) throws IOException, NoSuchAlgorithmException {
        List<FileUpdateAction> actions = new ArrayList<>();
        Map<String, FileInfo> localFilesMap = (local != null && local.files != null) ? local.getFilesAsMap() : new HashMap<>();
        Map<String, FileInfo> remoteFilesMap = remote.getFilesAsMap();

        for (Map.Entry<String, FileInfo> entry : remoteFilesMap.entrySet()) {
            String fileId = entry.getKey();
            FileInfo remoteFile = entry.getValue();
            FileInfo localFileMeta = localFilesMap.get(fileId);
            Path actualLocalPath = APP_DIR.resolve(remoteFile.localTargetName);

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

        for (Map.Entry<String, FileInfo> entry : localFilesMap.entrySet()) {
            if (!remoteFilesMap.containsKey(entry.getKey())) {
                actions.add(new FileUpdateAction(UpdateActionType.DELETE_OLD, null, entry.getValue(), "File no longer in new version"));
            }
        }
        return actions;
    }


    public Map<String, Path> triggerDownload(ConsoleResult consoleResult) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        progressDialog.resetProgressTracker();
        List<FileUpdateAction> actions = checkResult.getActions();

        long totalDownloadSizeInBytes = actions.stream()
                .filter(a -> a.actionType == UpdateActionType.DOWNLOAD_NEW || a.actionType == UpdateActionType.UPDATE_EXISTING)
                .mapToLong(a -> a.remoteFileInfo.fileSizeByte)
                .sum();

        if (totalDownloadSizeInBytes == 0) {
            progressDialog.appendLog("--- No files to download or total size is zero ---");
            progressDialog.setProgress(100, UpdatedStatus.Updated.getName(), consoleResult);
            return new HashMap<>();
        }

        AtomicLong cumulativeBytesDownloaded = new AtomicLong(0);

        long filesToDownload = actions.stream()
                .filter(a -> a.actionType == UpdateActionType.DOWNLOAD_NEW || a.actionType == UpdateActionType.UPDATE_EXISTING)
                .count();
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
                        int overallProgress = (int) ((totalDownloaded * 100) / totalDownloadSizeInBytes);
                        String progressMsg = String.format("Downloading %s (%d%%)",
                                remoteFile.serverFileName,
                                overallProgress);
                        progressDialog.setProgress(overallProgress, progressMsg, consoleResult);
                    };

                    Path downloadedPath = downloadFile(remoteFile.url, remoteFile.serverFileName, remoteFile.sha256, listener);
                    downloadedFilesMap.put(remoteFile.id, downloadedPath);
                    progressDialog.appendLog("Downloaded and verified: " + remoteFile.serverFileName);
                }
            }
            progressDialog.appendLog("--- Download Phase Complete ---");
        } else {
            progressDialog.appendLog("--- No files to download ---");
        }
        progressDialog.setProgress(100, UpdatedStatus.Updated.getName(), consoleResult);
        LatestVersionInfo latestRemoteInfo = fetchJson(LATEST_VERSION_INFO_URL, LatestVersionInfo.class);
        if (Objects.nonNull(latestRemoteInfo)) {
            Boolean forceUpdate = latestRemoteInfo.getForceUpdate();
            if (Boolean.TRUE.equals(forceUpdate)) {
                if (OS.isWindows()) {
                    triggerInstallationWithAuxiliaryProcess();
                    return downloadedFilesMap;
                }
                triggerInstallation();
                restartApp();
            }
        }
        return downloadedFilesMap;
    }

    public boolean triggerInstallation() {
        List<FileUpdateAction> actions = checkResult.getActions();
        List<Runnable> rollbackOperations = new ArrayList<>();
        long filesToApplyOrDelete = actions.stream()
                .filter(a -> a.actionType != UpdateActionType.KEEP_LOCAL)
                .count();
        try {
            progressDialog.appendLog("Starting update execution phase...");
            if (filesToApplyOrDelete > 0) {
                progressDialog.appendLog("--- Apply Phase ---");
                for (FileUpdateAction action : actions) {
                    if (action.actionType == UpdateActionType.KEEP_LOCAL) {
                        continue;
                    }
                    FileInfo remoteFile = action.remoteFileInfo;
                    FileInfo localFileMeta = action.localFileInfo;
                    Path targetLocalPath = APP_DIR.resolve(remoteFile != null ? remoteFile.localTargetName : localFileMeta.localTargetName);

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
                            if (Files.exists(targetLocalPath)) {
                                String backupSuffix = ("zip".equals(remoteFile.type) && Files.isDirectory(targetLocalPath) ? "_dir" : "") + ".bak_" + System.currentTimeMillis();
                                Path backupPath = targetLocalPath.getParent().resolve(targetLocalPath.getFileName() + backupSuffix);
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
                                progressDialog.appendLog("Extracting " + sourcePath.getFileName() + " to " + targetLocalPath.getFileName());
                                targetLocalPath = targetLocalPath.getParent();
                                Files.createDirectories(targetLocalPath);
                                extractZip(sourcePath, targetLocalPath);
                                Files.delete(sourcePath);
                            } else {
                                progressDialog.appendLog("Moving " + sourcePath.getFileName() + " to " + targetLocalPath.getFileName());
                                Files.move(sourcePath, targetLocalPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                            progressDialog.appendLog("Applied: " + remoteFile.localTargetName);
                            break;

                        case DELETE_OLD:
                            assert localFileMeta != null;
                            currentOpDisplay = "Deleting: " + localFileMeta.localTargetName;
                            progressDialog.appendLog(currentOpDisplay);
                            Path pathToDelete = APP_DIR.resolve(localFileMeta.localTargetName);
                            if (Files.exists(pathToDelete)) {
                                if (Files.isDirectory(pathToDelete))
                                    deleteDirectoryRecursively(pathToDelete);
                                else Files.delete(pathToDelete);
                                progressDialog.appendLog("Deleted: " + localFileMeta.localTargetName);
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
            progressDialog.appendLog("Clearing old backups...");
            clearOldBackups(APP_DIR);
            saveLocalVersion(checkResult.getRemoteMetadata());
            downloadedFilesMap.clear();
            checkResult = new CheckResult();
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
            downloadedFilesMap.values().forEach(tempFile -> {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ex) {
                    log.warn("Failed to delete temporary download file: {}", tempFile, ex);
                }
            });
        }
    }

    private void clearOldBackups(Path baseDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, path -> path.getFileName().toString().contains(".bak_"))) {
            for (Path backupFile : stream) {
                try {
                    progressDialog.appendLog("Deleting old backup: " + backupFile.getFileName());
                    if (Files.isDirectory(backupFile)) {
                        deleteDirectoryRecursively(backupFile);
                    } else {
                        Files.delete(backupFile);
                    }
                } catch (IOException e) {
                    progressDialog.appendLog("ERROR: Failed to delete old backup " + backupFile.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            progressDialog.appendLog("ERROR: Could not list old backups: " + e.getMessage());
        }
    }

    private VersionMetadata loadLocalVersion() {
        if (Files.exists(LOCAL_VERSION_FILE)) {
            if (progressDialog != null) progressDialog.appendLog("Loading local version from: " + LOCAL_VERSION_FILE);
            try (InputStream is = Files.newInputStream(LOCAL_VERSION_FILE)) {
                return objectMapper.readValue(is, VersionMetadata.class);
            } catch (Exception e) {
                String errorMsg = "Failed to load local_version.json: " + e.getMessage() + ". Assuming no local version.";
                if (progressDialog != null) progressDialog.appendLog("ERROR: " + errorMsg);
                log.error(errorMsg);
                try {
                    Files.move(LOCAL_VERSION_FILE, LOCAL_VERSION_FILE.resolveSibling("local_version.json.corrupted_" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveEx) {
                    log.error("Could not rename corrupted local_version.json: {}", moveEx.getMessage());
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

        URL url = URI.create(urlString).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "JavaUpdater/1.0");
        connection.setRequestProperty("Referer", "https://chat2db.ai");

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
            return null;
        }
    }


    private Path downloadFile(String urlString, String targetFileNameInTmp, String expectedSha256, IProgressListener progressListener) throws IOException, NoSuchAlgorithmException, URISyntaxException {
        Path targetPath = TMP_DIR.resolve(targetFileNameInTmp);
        Files.createDirectories(targetPath.getParent());
        if (Files.exists(targetPath)) {
            if (progressDialog != null) {
                progressDialog.appendLog("File already exists, verifying: " + targetFileNameInTmp);
            }
            if (verifyFileChecksum(targetPath, expectedSha256)) {
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
            }
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
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            if (progressDialog != null) progressDialog.appendLog("Downloading remote file " + targetFileNameInTmp);
            URL url = URI.create(urlString).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setRequestProperty("User-Agent", "JavaUpdater/1.0");
            connection.setRequestProperty("Referer", "https://chat2db.ai");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 OutputStream out = Files.newOutputStream(targetPath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    sha256.update(buffer, 0, bytesRead);
                    if (progressListener != null) {
                        progressListener.onProgress(bytesRead);
                    }
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

        if (progressDialog != null) progressDialog.appendLog("Verifying checksum for " + targetFileNameInTmp);

        boolean checksumVerified;
        if (actualSha256 != null) {
            checksumVerified = actualSha256.equalsIgnoreCase(expectedSha256);
        } else {
            checksumVerified = verifyFileChecksum(targetPath, expectedSha256);
        }

        if (!checksumVerified) {
            Files.deleteIfExists(targetPath);
            throw new IOException("Checksum mismatch for " + targetPath.getFileName() + ". Expected: " + expectedSha256 + ", Actual: " + (actualSha256 != null ? actualSha256 : "re-calculated"));
        }

        if (progressDialog != null) {
            progressDialog.appendLog("Download & verification complete: " + targetFileNameInTmp);
        }

        return targetPath;
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

    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        byte[] buffer = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
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
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
                zipEntry = zis.getNextEntry();
            }
        }
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                    for (Path entry : entries) {
                        deleteDirectoryRecursively(entry);
                    }
                }
            }
            Files.delete(path);
        }
    }
    public void triggerInstallationWithAuxiliaryProcess() {
        progressDialog.appendLog("Preparing for update via auxiliary process...");
        try {
            UpdatePlan plan = new UpdatePlan();
            plan.setTasks(checkResult.getActions());
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

        } catch (Exception e) {
            log.error("Failed to launch auxiliary updater process", e);
            progressDialog.appendLog("FATAL ERROR: Could not start the update process. " + e.getMessage());
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
