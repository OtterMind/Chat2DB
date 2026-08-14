package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.enums.update.UpdateActionType;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Performs the non-Windows, in-process file transaction for a verified update plan. */
final class InProcessInstallationExecutor implements InstallationExecutor {

    @FunctionalInterface
    interface TargetResolver {
        Path resolve(String relativePath) throws IOException;
    }

    @FunctionalInterface
    interface MetadataWriter {
        void save(VersionMetadata metadata) throws IOException;
    }

    @FunctionalInterface
    interface BackupRestorer {
        void restore(Path backup, Path target) throws IOException;
    }

    private static final String BACKUP_DIRECTORY_NAME = ".chat2db-update-backups";
    private static final String BACKUP_OWNER_PID_FILE = ".owner-pid";
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_ZIP_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path appDirectory;
    private final TargetResolver targetResolver;
    private final MetadataWriter metadataWriter;
    private final Consumer<String> progressLog;
    private final UpdateTransaction transaction;
    private final BackupRestorer backupRestorer;

    InProcessInstallationExecutor(Path appDirectory, TargetResolver targetResolver, MetadataWriter metadataWriter,
                                 Consumer<String> progressLog, Consumer<Exception> errorLog) {
        this(appDirectory, targetResolver, metadataWriter, progressLog, errorLog, null);
    }

    InProcessInstallationExecutor(Path appDirectory, TargetResolver targetResolver, MetadataWriter metadataWriter,
                                 Consumer<String> progressLog, Consumer<Exception> errorLog,
                                 BackupRestorer backupRestorer) {
        this.appDirectory = Objects.requireNonNull(appDirectory, "appDirectory is required").toAbsolutePath().normalize();
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver is required");
        this.metadataWriter = Objects.requireNonNull(metadataWriter, "metadataWriter is required");
        this.progressLog = Objects.requireNonNull(progressLog, "progressLog is required");
        this.transaction = new UpdateTransaction(progressLog, errorLog);
        this.backupRestorer = backupRestorer != null ? backupRestorer : this::restoreBackup;
    }

    @Override
    public boolean install(List<FileUpdateAction> actions, Map<String, Path> downloadedFiles, VersionMetadata remoteMetadata) {
        UpdateTransaction.RollbackRegistry rollbackRegistry = transaction.begin();
        Path backupSession = null;
        boolean completed = false;
        boolean rollbackCompleted = true;
        try {
            backupSession = createBackupSession();
            long mutableActions = actions.stream().filter(action -> action.actionType != UpdateActionType.KEEP_LOCAL).count();
            progressLog.accept("Starting update execution phase...");
            if (mutableActions == 0) {
                progressLog.accept("--- No files to apply or delete ---");
            } else {
                progressLog.accept("--- Apply Phase ---");
                for (FileUpdateAction action : actions) {
                    if (action.actionType != UpdateActionType.KEEP_LOCAL) {
                        applyAction(action, downloadedFiles, backupSession, rollbackRegistry);
                    }
                }
                progressLog.accept("--- Apply Phase Complete ---");
            }
            metadataWriter.save(remoteMetadata);
            completed = true;
            return true;
        } catch (Exception exception) {
            rollbackCompleted = transaction.fail(rollbackRegistry, exception);
            return false;
        } finally {
            if (!completed && backupSession != null && rollbackCompleted) {
                try {
                    deleteDirectoryRecursively(backupSession);
                } catch (IOException exception) {
                    progressLog.accept("ERROR during incomplete update backup cleanup: " + exception.getMessage());
                }
            } else if (!completed && backupSession != null) {
                progressLog.accept("ERROR: Rollback was incomplete; preserving update backup session for recovery: "
                        + backupSession);
            }
        }
    }

    private void applyAction(FileUpdateAction action, Map<String, Path> downloadedFiles, Path backupSession,
                             UpdateTransaction.RollbackRegistry rollbacks) throws IOException {
        switch (action.actionType) {
            case DOWNLOAD_NEW, UPDATE_EXISTING -> applyDownloadedFile(action.remoteFileInfo, downloadedFiles, backupSession, rollbacks);
            case DELETE_OLD -> stageDeletion(action.localFileInfo, backupSession, rollbacks);
            case KEEP_LOCAL -> { }
        }
    }

    private void applyDownloadedFile(FileInfo remoteFile, Map<String, Path> downloadedFiles, Path backupSession,
                                     UpdateTransaction.RollbackRegistry rollbacks) throws IOException {
        if (remoteFile == null) {
            throw new IOException("Update plan has no remote file for an install action");
        }
        Path target = targetResolver.resolve(remoteFile.localTargetName);
        Files.createDirectories(target.getParent());
        progressLog.accept("Installing: " + remoteFile.localTargetName);
        Path source = downloadedFiles.get(remoteFile.id);
        if (source == null) {
            throw new IOException("Downloaded file not found in map for ID: " + remoteFile.id);
        }
        Path staging = null;
        Path stagedContent = null;
        if ("zip".equals(remoteFile.type)) {
            staging = Files.createTempDirectory(target.getParent(), ".update-stage-");
            Path stagingForRollback = staging;
            rollbacks.add(() -> cleanupDirectory(stagingForRollback));
            extractZip(source, staging, target.getFileName().toString());
            stagedContent = staging.resolve(target.getFileName());
            if (!Files.isDirectory(stagedContent)) {
                throw new IOException("ZIP archive does not contain the expected directory: " + target.getFileName());
            }
        }
        backupCurrentTarget(target, backupSession, rollbacks);
        if ("zip".equals(remoteFile.type)) {
            progressLog.accept("Installing staged ZIP " + source.getFileName() + " to " + target.getFileName());
            moveIntoPlace(stagedContent, target);
            Files.delete(source);
            deleteDirectoryRecursively(staging);
        } else {
            progressLog.accept("Moving " + source.getFileName() + " to " + target.getFileName());
            moveIntoPlace(source, target);
        }
        progressLog.accept("Applied: " + remoteFile.localTargetName);
    }

    private void stageDeletion(FileInfo localFile, Path backupSession, UpdateTransaction.RollbackRegistry rollbacks)
            throws IOException {
        if (localFile == null) {
            throw new IOException("Update plan has no local file for a delete action");
        }
        Path target = targetResolver.resolve(localFile.localTargetName);
        progressLog.accept("Deleting: " + localFile.localTargetName);
        if (!Files.exists(target)) {
            progressLog.accept("Skipped delete (already gone): " + localFile.localTargetName);
            return;
        }
        Path backup = resolveBackupPath(backupSession, target);
        Files.createDirectories(backup.getParent());
        progressLog.accept("Backing up deleted file " + target.getFileName() + " to " + backup.getFileName());
        Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            rollbacks.add(() -> backupRestorer.restore(backup, target));
        progressLog.accept("Staged deletion: " + localFile.localTargetName);
    }

    private void backupCurrentTarget(Path target, Path backupSession, UpdateTransaction.RollbackRegistry rollbacks)
            throws IOException {
        if (!Files.exists(target)) {
            rollbacks.add(() -> deleteTarget(target));
            return;
        }
        Path backup = resolveBackupPath(backupSession, target);
        Files.createDirectories(backup.getParent());
        progressLog.accept("Backing up " + target.getFileName() + " to " + backup.getFileName());
        Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        rollbacks.add(() -> backupRestorer.restore(backup, target));
    }

    private Path createBackupSession() throws IOException {
        Path backupRoot = targetResolver.resolve(BACKUP_DIRECTORY_NAME);
        Path session = Files.createDirectories(backupRoot.resolve(UUID.randomUUID().toString()));
        Files.writeString(session.resolve(BACKUP_OWNER_PID_FILE), Long.toString(ProcessHandle.current().pid()),
                StandardOpenOption.CREATE_NEW);
        return session;
    }

    private Path resolveBackupPath(Path session, Path target) throws IOException {
        Path relativeTarget = appDirectory.relativize(target.toAbsolutePath().normalize());
        Path backup = session.resolve(relativeTarget).normalize();
        if (!backup.startsWith(session)) {
            throw new IOException("Update backup path escapes the backup session");
        }
        return backup;
    }

    private void restoreBackup(Path backup, Path target) throws IOException {
        progressLog.accept("Rollback: Restoring " + backup.getFileName() + " to " + target.getFileName());
        deleteTarget(target);
        moveIntoPlace(backup, target);
    }

    private void deleteTarget(Path target) throws IOException {
        if (Files.exists(target)) {
            if (Files.isDirectory(target)) {
                deleteDirectoryRecursively(target);
            } else {
                Files.delete(target);
            }
        }
    }

    private void cleanupDirectory(Path directory) throws IOException {
        deleteDirectoryRecursively(directory);
    }

    static void extractZip(Path zipFile, Path destination, String expectedTopLevelDirectory) throws IOException {
        Files.createDirectories(destination);
        byte[] buffer = new byte[8192];
        int entryCount = 0;
        long extractedBytes = 0;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("ZIP archive has too many entries");
                }
                String entryName = entry.getName();
                int separator = entryName.indexOf('/');
                String root = separator >= 0 ? entryName.substring(0, separator) : entryName;
                if (!expectedTopLevelDirectory.equals(root)) {
                    throw new IOException("ZIP entry is outside of the expected top-level directory: " + entryName);
                }
                Path output = destination.resolve(entryName).normalize();
                if (!output.startsWith(destination.normalize())) {
                    throw new IOException("Zip entry is outside of the target dir: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(output))) {
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) > 0) {
                            extractedBytes += bytesRead;
                            if (extractedBytes > MAX_ZIP_UNCOMPRESSED_BYTES) {
                                throw new IOException("ZIP archive exceeds the uncompressed size limit");
                            }
                            fileOutput.write(buffer, 0, bytesRead);
                        }
                    }
                }
                input.closeEntry();
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
}
