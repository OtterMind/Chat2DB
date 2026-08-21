package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves updater-owned paths while keeping every existing path segment inside a trusted root.
 */
final class UpdatePathPolicy {

    private static final String PARTIAL_DOWNLOAD_SUFFIX = ".part";

    private final Path appDirectory;
    private final Path temporaryDirectory;

    UpdatePathPolicy(Path appDirectory, Path temporaryDirectory) {
        this.appDirectory = Objects.requireNonNull(appDirectory, "appDirectory is required");
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory is required");
    }

    Path resolveApplicationRelativePath(String relativePath) throws IOException {
        if (isBlank(relativePath)) {
            throw new IOException("Update target path is blank");
        }
        Path normalizedAppDirectory = appDirectory.toAbsolutePath().normalize();
        Path resolved = normalizedAppDirectory.resolve(relativePath).normalize();
        if (resolved.equals(normalizedAppDirectory) || !resolved.startsWith(normalizedAppDirectory)) {
            throw new IOException("Update target path escapes the application directory: " + relativePath);
        }
        rejectSymbolicLinkPathSegments(resolved, "Update target path", relativePath);
        return resolved;
    }

    Path resolveTemporaryFile(String fileName) throws IOException {
        if (isBlank(fileName) || fileName.contains("/") || fileName.indexOf('\\') >= 0
                || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IOException("Update temporary file name is invalid: " + fileName);
        }
        Path normalizedTemporaryDirectory = temporaryDirectory.toAbsolutePath().normalize();
        Path resolved = normalizedTemporaryDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(normalizedTemporaryDirectory)) {
            throw new IOException("Update temporary file path escapes the download directory: " + fileName);
        }
        rejectSymbolicLinkPathSegments(resolved, "Update temporary file path", fileName);
        return resolved;
    }

    Path resolvePartialDownloadFile(Path targetPath) throws IOException {
        Path partialPath = targetPath.resolveSibling(targetPath.getFileName() + PARTIAL_DOWNLOAD_SUFFIX);
        Path normalizedTemporaryDirectory = temporaryDirectory.toAbsolutePath().normalize();
        if (!partialPath.startsWith(normalizedTemporaryDirectory)) {
            throw new IOException("Update partial download file is unsafe: " + partialPath.getFileName());
        }
        rejectSymbolicLinkPathSegments(partialPath, "Update partial download file path",
                partialPath.getFileName().toString());
        return partialPath;
    }

    private static void rejectSymbolicLinkPathSegments(Path path, String description, String value) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        Path root = absolutePath.getRoot();
        if (root == null) {
            throw new IOException(description + " is not absolute: " + value);
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException(description + " contains a symbolic link: " + value);
        }
        Path current = root;
        for (Path segment : root.relativize(absolutePath)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) && !isMacOsSystemAlias(current)) {
                throw new IOException(description + " contains a symbolic link: " + value);
            }
        }
    }

    /** macOS exposes temporary directories beneath the system-owned /var -> /private/var alias. */
    private static boolean isMacOsSystemAlias(Path path) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
                && path.equals(Path.of("/var"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
