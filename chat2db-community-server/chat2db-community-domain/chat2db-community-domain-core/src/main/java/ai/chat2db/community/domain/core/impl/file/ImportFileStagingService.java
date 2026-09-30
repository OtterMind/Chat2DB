package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owns server-side import uploads between the HTTP request and asynchronous task execution.
 */
@Component
public class ImportFileStagingService implements IImportFileStagingService {
    private static final String STAGING_DIRECTORY_NAME = "import-preview";
    private static final String CLAIM_MARKER_SUFFIX = ".claimed";
    private static final Pattern FILE_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            TaskFileFormat.CSV.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.XLS.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.XLSX.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.JSON.name().toLowerCase(Locale.ROOT),
            TaskFileFormat.SQL.name().toLowerCase(Locale.ROOT));
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration CLAIMED_MAX_AGE = Duration.ofDays(7);
    private static final String MAX_SIZE_PROPERTY = "chat2db.task.import.staging.max-bytes";
    private static final long DEFAULT_MAX_SIZE_BYTES = 1024L * 1024L * 1024L;
    private static final String MAX_FILES_PROPERTY = "chat2db.task.import.staging.max-files";
    private static final int DEFAULT_MAX_FILES = 1000;
    private static final String MAX_TOTAL_SIZE_PROPERTY = "chat2db.task.import.staging.max-total-bytes";
    private static final long DEFAULT_MAX_TOTAL_SIZE_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final Object STAGING_QUOTA_LOCK = new Object();

    @Override
    public String stage(File file, String originalFileName) {
        validateSource(file, originalFileName);
        synchronized (STAGING_QUOTA_LOCK) {
            cleanupExpiredFilesLocked();
            String id = UUID.randomUUID().toString();
            String extension = extension(originalFileName);
            Path target = null;
            try {
                Path source = file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
                long sourceSize = Files.size(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(source)
                        || sourceSize > maxSizeBytes()) {
                    throw new IOException("file is not readable");
                }
                Path stagingDirectory = stagingDirectory();
                Files.createDirectories(stagingDirectory);
                StagingUsage usage = stagingUsage(stagingDirectory);
                ensureQuotaAvailable(usage, sourceSize);
                target = stagingFile(id, extension);
                if (!target.getParent().equals(stagingDirectory)) {
                    throw new IOException("invalid staging target");
                }
                Files.copy(source, target);
                long stagedSize = Files.size(target);
                if (stagedSize > maxSizeBytes()
                        || usage.totalBytes() > maxTotalSizeBytes() - stagedSize) {
                    deleteQuietly(target);
                    throw quotaExceeded();
                }
            } catch (IOException e) {
                deleteQuietly(target);
                throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
            }
            return id;
        }
    }

    @Override
    public File resolve(String fileId) {
        if (!isFileId(fileId)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        cleanupExpiredFiles();
        try {
            Path file = stagedFile(fileId);
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new BusinessException("import.preview.fileUnreadable");
            }
            return file.toFile();
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void claimForTask(String fileId) {
        synchronized (STAGING_QUOTA_LOCK) {
            resolve(fileId);
            try {
                Files.createFile(claimMarker(fileId));
            } catch (FileAlreadyExistsException alreadyClaimed) {
                throw new BusinessException("import.preview.fileClaimed");
            } catch (IOException claimFailure) {
                throw new BusinessException("import.preview.fileUnreadable",
                        new Object[]{claimFailure.getMessage()}, claimFailure);
            }
        }
    }

    @Override
    public boolean releaseUnclaimed(String fileId) {
        if (!isFileId(fileId)) {
            return false;
        }
        synchronized (STAGING_QUOTA_LOCK) {
            if (!Files.notExists(claimMarker(fileId), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return deleteStagedFiles(fileId);
        }
    }

    @Override
    public void release(String fileId) {
        if (!isFileId(fileId)) {
            return;
        }
        synchronized (STAGING_QUOTA_LOCK) {
            deleteStagedFiles(fileId);
        }
    }

    private static boolean deleteStagedFiles(String fileId) {
        boolean deleted = false;
        for (String extension : ALLOWED_EXTENSIONS) {
            try {
                Path path = stagingFile(fileId, extension);
                boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
                deleteQuietly(path);
                deleted |= existed && !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            } catch (BusinessException ignored) {
                // Cleanup does not change the task outcome once execution has completed.
            }
        }
        deleteQuietly(claimMarker(fileId));
        return deleted;
    }

    private static void validateSource(File file, String originalFileName) {
        if (file == null || !file.isFile() || !file.canRead() || file.length() > maxSizeBytes()
                || !ALLOWED_EXTENSIONS.contains(extension(originalFileName))) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
    }

    private static long maxSizeBytes() {
        long configured = Long.getLong(MAX_SIZE_PROPERTY, DEFAULT_MAX_SIZE_BYTES);
        return configured > 0L ? configured : DEFAULT_MAX_SIZE_BYTES;
    }

    private static int maxFiles() {
        int configured = Integer.getInteger(MAX_FILES_PROPERTY, DEFAULT_MAX_FILES);
        return configured > 0 ? configured : DEFAULT_MAX_FILES;
    }

    private static long maxTotalSizeBytes() {
        long configured = Long.getLong(MAX_TOTAL_SIZE_PROPERTY, DEFAULT_MAX_TOTAL_SIZE_BYTES);
        return configured > 0L ? configured : DEFAULT_MAX_TOTAL_SIZE_BYTES;
    }

    private static void ensureQuotaAvailable(StagingUsage usage, long sourceSize) {
        if (usage.fileCount() >= maxFiles()
                || sourceSize > maxTotalSizeBytes()
                || usage.totalBytes() > maxTotalSizeBytes() - sourceSize) {
            throw quotaExceeded();
        }
    }

    private static BusinessException quotaExceeded() {
        return new BusinessException("import.preview.stagingQuotaExceeded");
    }

    private static StagingUsage stagingUsage(Path directory) throws IOException {
        long fileCount = 0L;
        long totalBytes = 0L;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(ImportFileStagingService::isStagedImportFile).toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                fileCount++;
                long size = Files.size(path);
                totalBytes = size > Long.MAX_VALUE - totalBytes ? Long.MAX_VALUE : totalBytes + size;
            }
        }
        return new StagingUsage(fileCount, totalBytes);
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Path stagingDirectory() {
        return Path.of(ConfigUtils.getBasePath(), STAGING_DIRECTORY_NAME).normalize().toAbsolutePath();
    }

    private static Path stagingFile(String id, String extension) {
        if (!isFileId(id) || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        Path directory = stagingDirectory();
        Path file = directory.resolve(id + "." + extension).normalize();
        if (!file.getParent().equals(directory)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        return file;
    }

    private static Path stagedFile(String id) throws IOException {
        for (String extension : ALLOWED_EXTENSIONS) {
            Path file = stagingFile(id, extension);
            if (Files.exists(file)) {
                return file;
            }
        }
        throw new BusinessException("import.preview.fileUnreadable");
    }

    private static Path claimMarker(String id) {
        if (!isFileId(id)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        Path directory = stagingDirectory();
        Path marker = directory.resolve(id + CLAIM_MARKER_SUFFIX).normalize();
        if (!marker.getParent().equals(directory)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        return marker;
    }

    private void cleanupExpiredFiles() {
        synchronized (STAGING_QUOTA_LOCK) {
            cleanupExpiredFilesLocked();
        }
    }

    private void cleanupExpiredFilesLocked() {
        try {
            if (!Files.isDirectory(stagingDirectory())) {
                return;
            }
            Instant deadline = Instant.now().minus(MAX_AGE);
            try (var files = Files.list(stagingDirectory())) {
                files.filter(ImportFileStagingService::isStagedImportFile)
                        .filter(path -> isExpired(path, deadline)).filter(this::canDelete)
                        .forEach(path -> {
                            deleteQuietly(path);
                            deleteQuietly(claimMarker(stagedFileId(path)));
                        });
            }
            Instant claimedDeadline = Instant.now().minus(CLAIMED_MAX_AGE);
            try (var files = Files.list(stagingDirectory())) {
                files.filter(ImportFileStagingService::isClaimMarker)
                        .filter(path -> isExpired(path, claimedDeadline))
                        .filter(path -> !hasStagedImportFile(claimMarkerFileId(path)))
                        .forEach(ImportFileStagingService::deleteQuietly);
            }
        } catch (IOException ignored) {
            // Stale staging files are best-effort cleanup; a valid current file must remain usable.
        }
    }

    private boolean canDelete(Path path) {
        Path marker = claimMarker(stagedFileId(path));
        if (Files.notExists(marker, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        // A claim marker only proves that some task touched the file, not that the task is still
        // alive. When the owning task record is gone nothing will ever call release() for it, so
        // this age fallback is the only way the staging quota can recover from an orphan.
        return isExpired(marker, Instant.now().minus(CLAIMED_MAX_AGE));
    }

    private static boolean isFileId(String fileId) {
        return fileId != null && FILE_ID_PATTERN.matcher(fileId).matches();
    }

    private static boolean isExpired(Path path, Instant deadline) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(deadline);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort only.
        }
    }

    private static boolean isStagedImportFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot == 36 && isFileId(name.substring(0, dot))
                && ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isClaimMarker(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(CLAIM_MARKER_SUFFIX)
                && isFileId(name.substring(0, name.length() - CLAIM_MARKER_SUFFIX.length()));
    }

    private static String claimMarkerFileId(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - CLAIM_MARKER_SUFFIX.length());
    }

    private static boolean hasStagedImportFile(String fileId) {
        for (String extension : ALLOWED_EXTENSIONS) {
            if (Files.exists(stagingFile(fileId, extension), LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    private static String stagedFileId(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private record StagingUsage(long fileCount, long totalBytes) {
    }
}
