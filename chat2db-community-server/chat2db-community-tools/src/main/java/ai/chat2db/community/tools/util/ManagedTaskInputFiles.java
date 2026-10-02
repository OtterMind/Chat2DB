package ai.chat2db.community.tools.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
public final class ManagedTaskInputFiles {

    public static final String FILE_PREFIX = "task-import-";
    public static final String FILE_SUFFIX = ".tmp";
    public static final String CLEANUP_MARKER_PREFIX = ".cleanup.";

    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("task-import-[A-Za-z0-9_-]+\\.tmp");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private ManagedTaskInputFiles() {
    }

    public static Path markerPath(Path sourceFile, String cleanupToken) {
        return sourceFile.resolveSibling(sourceFile.getFileName() + CLEANUP_MARKER_PREFIX + cleanupToken);
    }

    public static boolean cleanup(Path trustedRoot, String sourceFile, String cleanupToken) {
        Path source = trustedSource(trustedRoot, sourceFile);
        if (source == null || cleanupToken == null || !TOKEN_PATTERN.matcher(cleanupToken).matches()) {
            return false;
        }
        Path marker = markerPath(source, cleanupToken);
        try {
            boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
            boolean markerExists = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            if (!sourceExists && !markerExists) {
                return true;
            }
            if (!markerExists || Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            BasicFileAttributes sourceIdentity = null;
            if (sourceExists) {
                if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                sourceIdentity = Files.readAttributes(source, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            }
            BasicFileAttributes markerIdentity = Files.readAttributes(marker, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if ((sourceIdentity != null && !sameIdentity(sourceIdentity, Files.readAttributes(source,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))
                    || !sameIdentity(markerIdentity, Files.readAttributes(marker, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
            Files.deleteIfExists(source);
            Files.deleteIfExists(marker);
            return true;
        } catch (IOException | SecurityException e) {
            log.warn("Failed to clean managed task input: {}", source, e);
            return false;
        }
    }

    public static int cleanupOrphans(Path trustedRoot) {
        Path root = normalizedRoot(trustedRoot);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("Refusing to scan untrusted task input root: {}", root);
            return 0;
        }
        int cleaned = 0;
        try (DirectoryStream<Path> markers = Files.newDirectoryStream(root,
                FILE_PREFIX + "*" + FILE_SUFFIX + CLEANUP_MARKER_PREFIX + "*")) {
            for (Path marker : markers) {
                if (Files.isSymbolicLink(marker) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String markerName = marker.getFileName().toString();
                int tokenIndex = markerName.lastIndexOf(CLEANUP_MARKER_PREFIX);
                if (tokenIndex <= 0) {
                    continue;
                }
                String sourceName = markerName.substring(0, tokenIndex);
                String cleanupToken = markerName.substring(tokenIndex + CLEANUP_MARKER_PREFIX.length());
                Path source = marker.resolveSibling(sourceName);
                if (cleanup(root, source.toString(), cleanupToken)) {
                    cleaned++;
                }
            }
        } catch (IOException | SecurityException e) {
            log.warn("Failed to reconcile managed task inputs in {}", root, e);
        }
        return cleaned;
    }

    private static Path trustedSource(Path trustedRoot, String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return null;
        }
        try {
            Path root = normalizedRoot(trustedRoot);
            Path source = Path.of(sourceFile).toAbsolutePath().normalize();
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))) {
                log.warn("Refusing untrusted managed task input root: {}", root);
                return null;
            }
            if (!Objects.equals(source.getParent(), root)
                    || !FILE_NAME_PATTERN.matcher(source.getFileName().toString()).matches()) {
                log.warn("Refusing to clean task input outside the managed root: {}", source);
                return null;
            }
            return source;
        } catch (RuntimeException e) {
            log.warn("Refusing invalid managed task input path: {}", sourceFile, e);
            return null;
        }
    }

    private static Path normalizedRoot(Path trustedRoot) {
        return trustedRoot.toAbsolutePath().normalize();
    }

    private static boolean sameIdentity(BasicFileAttributes expected, BasicFileAttributes actual) {
        if (expected.fileKey() != null || actual.fileKey() != null) {
            return Objects.equals(expected.fileKey(), actual.fileKey());
        }
        return expected.size() == actual.size()
                && Objects.equals(expected.creationTime(), actual.creationTime())
                && Objects.equals(expected.lastModifiedTime(), actual.lastModifiedTime());
    }
}
