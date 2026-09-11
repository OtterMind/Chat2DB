package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.service.file.IImportFileStagingService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Owns server-side import uploads between the HTTP request and asynchronous task execution.
 */
@Component
public class ImportFileStagingService implements IImportFileStagingService {
    private static final String STAGING_DIRECTORY_NAME = "import-preview";
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
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private final Map<String, Instant> claimedFiles = new ConcurrentHashMap<>();

    @Override
    public String stage(File file, String originalFileName) {
        validateSource(file, originalFileName);
        cleanupExpiredFiles();
        String id = UUID.randomUUID().toString();
        String extension = extension(originalFileName);
        try {
            Path source = file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(source)) {
                throw new IOException("file is not readable");
            }
            Path stagingDirectory = stagingDirectory();
            Files.createDirectories(stagingDirectory);
            Path target = stagingTarget(id, extension);
            if (!target.getParent().equals(stagingDirectory)) {
                throw new IOException("invalid staging target");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
        return id;
    }

    @Override
    public File resolve(String fileId) {
        if (!isFileId(fileId)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        cleanupExpiredFiles();
        try {
            Path file = stagedFile(fileId).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!file.getParent().equals(stagingDirectory())
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) {
                throw new BusinessException("import.preview.fileUnreadable");
            }
            return file.toFile();
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void claimForTask(String fileId) {
        resolve(fileId);
        claimedFiles.put(fileId, Instant.now());
    }

    @Override
    public void release(String fileId) {
        if (!isFileId(fileId)) {
            return;
        }
        claimedFiles.remove(fileId);
        for (String extension : ALLOWED_EXTENSIONS) {
            try {
                deleteQuietly(stagingTarget(fileId, extension));
            } catch (BusinessException ignored) {
                // Cleanup does not change the task outcome once execution has completed.
            }
        }
    }

    private static void validateSource(File file, String originalFileName) {
        if (file == null || !file.isFile() || !file.canRead() || file.length() > MAX_SIZE_BYTES
                || !ALLOWED_EXTENSIONS.contains(extension(originalFileName))) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
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

    private static Path stagingTarget(String id, String extension) {
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
        if (!isFileId(id)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        Path directory = stagingDirectory();
        if (!Files.isDirectory(directory)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        try (var files = Files.list(directory)) {
            return files.filter(ImportFileStagingService::isStagedImportFile)
                    .filter(path -> id.equals(stagedFileId(path)))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("import.preview.fileUnreadable"));
        }
    }

    private void cleanupExpiredFiles() {
        try {
            if (!Files.isDirectory(stagingDirectory())) {
                return;
            }
            Instant deadline = Instant.now().minus(MAX_AGE);
            try (var files = Files.list(stagingDirectory())) {
                files.filter(ImportFileStagingService::isStagedImportFile)
                        .filter(path -> isExpired(path, deadline)).filter(this::canDelete)
                        .forEach(ImportFileStagingService::deleteQuietly);
            }
        } catch (IOException ignored) {
            // Stale staging files are best-effort cleanup; a valid current file must remain usable.
        }
    }

    private boolean canDelete(Path path) {
        String id = stagedFileId(path);
        Instant claimedAt = claimedFiles.get(id);
        return claimedAt == null || claimedAt.isBefore(Instant.now().minus(CLAIMED_MAX_AGE));
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

    private static String stagedFileId(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
