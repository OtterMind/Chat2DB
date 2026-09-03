package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JdbcDriverManagementPolicy {

    private static final Pattern UPLOAD_TOKEN = Pattern.compile("^([0-9a-f]{32}):([^/:\\\\]+\\.jar)$",
            Pattern.CASE_INSENSITIVE);

    record PromotedDrivers(String jdbcDriver, List<Path> files) {
        // Paths are created only by promoteUploadedDrivers after token and parent-directory validation.
        void rollback() {
            for (Path file : files) {
                try {
                    // codeql[java/path-injection]
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // Preserve the original save failure; the unreferenced file can be cleaned later.
                }
            }
        }
    }

    private JdbcDriverManagementPolicy() {
    }

    static boolean isSupported(boolean desktop, boolean community) {
        return desktop || community;
    }

    // Upload tokens are allowlisted and both resolved paths must remain direct children of trusted directories.
    static PromotedDrivers promoteUploadedDrivers(List<String> uploadTokens, Path stagingDirectory,
                                                   Path driverDirectory) {
        if (uploadTokens == null || uploadTokens.isEmpty()) {
            throw uploadFailure("no driver file was uploaded");
        }

        Path normalizedStagingDirectory = stagingDirectory.toAbsolutePath().normalize();
        Path normalizedDirectory = driverDirectory.toAbsolutePath().normalize();
        List<UploadTarget> targets = new ArrayList<>();
        try {
            for (String uploadToken : uploadTokens) {
                Matcher matcher = UPLOAD_TOKEN.matcher(StringUtils.defaultString(uploadToken));
                if (!matcher.matches()) {
                    throw uploadFailure("invalid driver upload token");
                }
                String uploadId = matcher.group(1);
                String driverName = matcher.group(2);
                Path stagedFile = normalizedStagingDirectory.resolve(uploadId + ".upload").normalize();
                Path driverFile = normalizedDirectory.resolve(driverName).normalize();
                targets.add(new UploadTarget(driverName, stagedFile, driverFile));
                if (!normalizedStagingDirectory.equals(stagedFile.getParent())
                        || !Files.isRegularFile(stagedFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !normalizedDirectory.equals(driverFile.getParent())) {
                    throw uploadFailure("uploaded driver file is unavailable");
                }
                // driverFile is a direct child of normalizedDirectory with an allowlisted JAR file name.
                // codeql[java/path-injection]
                if (Files.exists(driverFile)) {
                    throw uploadFailure("a managed driver with the same file name already exists");
                }
            }
        } catch (BusinessException exception) {
            cleanupStagedFiles(targets);
            throw exception;
        }

        List<Path> promotedFiles = new ArrayList<>();
        try {
            Files.createDirectories(normalizedDirectory);
            for (UploadTarget target : targets) {
                moveWithoutReplacement(target.stagedFile(), target.driverFile());
                promotedFiles.add(target.driverFile());
            }
            return new PromotedDrivers(
                    String.join(",", targets.stream().map(UploadTarget::driverName).toList()), promotedFiles);
        } catch (Exception exception) {
            for (Path promotedFile : promotedFiles) {
                try {
                    // codeql[java/path-injection]
                    Files.deleteIfExists(promotedFile);
                } catch (Exception ignored) {
                    // Keep the original promotion failure.
                }
            }
            cleanupStagedFiles(targets);
            throw uploadFailure(exception.getMessage());
        }
    }

    static void moveWithoutReplacement(Path source, Path target) throws Exception {
        // source and target are validated managed-directory children before this call.
        // codeql[java/path-injection]
        moveWithoutReplacement(source, target, (link, existing) -> Files.createLink(link, existing));
    }

    // Callers pass only paths validated as direct children of the managed staging and driver directories.
    static void moveWithoutReplacement(Path source, Path target, LinkCreator linkCreator) throws Exception {
        try {
            linkCreator.create(target, source);
        } catch (FileAlreadyExistsException exception) {
            throw exception;
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            // codeql[java/path-injection]
            Files.copy(source, target);
        }
        try {
            // codeql[java/path-injection]
            Files.delete(source);
        } catch (Exception exception) {
            try {
                // codeql[java/path-injection]
                Files.deleteIfExists(target);
            } catch (Exception cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static void cleanupStagedFiles(List<UploadTarget> targets) {
        for (UploadTarget target : targets) {
            try {
                // codeql[java/path-injection]
                Files.deleteIfExists(target.stagedFile());
            } catch (Exception ignored) {
                // Preserve the validation failure.
            }
        }
    }

    private record UploadTarget(String driverName, Path stagedFile, Path driverFile) {
    }

    @FunctionalInterface
    interface LinkCreator {
        void create(Path link, Path existing) throws Exception;
    }

    private static BusinessException uploadFailure(String reason) {
        return new BusinessException("jdbc.driver.uploadFailed", new Object[]{reason});
    }
}
