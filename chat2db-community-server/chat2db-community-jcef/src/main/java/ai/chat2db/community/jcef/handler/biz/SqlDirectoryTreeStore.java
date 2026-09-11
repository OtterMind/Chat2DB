package ai.chat2db.community.jcef.handler.biz;

import ai.chat2db.community.jcef.utils.OSOperateUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

final class SqlDirectoryTreeStore {
    private static final int MAX_CHILDREN = 1000;
    private static final Set<String> SUPPORTED_TEXT_EXTENSIONS = Set.of(
            "sql", "txt", "md", "markdown",
            "json", "jsonl", "yaml", "yml",
            "csv", "tsv", "xml", "log",
            "env", "ini", "conf", "config",
            "properties", "toml");
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif");
    private static final Set<String> SUPPORTED_PREVIEW_EXTENSIONS = Set.of("pdf");
    private static final long MAX_PREVIEW_BYTES = 50L * 1024L * 1024L;
    private static final ConcurrentMap<String, Path> ROOTS = new ConcurrentHashMap<>();

    private SqlDirectoryTreeStore() {
    }

    static Map<String, Object> createRoot(String rootPath) throws IOException {
        Path root = Paths.get(rootPath).toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Selected path is not a directory");
        }

        String rootToken = UUID.randomUUID().toString();
        ROOTS.put(rootToken, root);

        Map<String, Object> rootNode = new HashMap<>();
        rootNode.put("key", rootToken + ":");
        rootNode.put("rootToken", rootToken);
        rootNode.put("rootPath", root.toString());
        rootNode.put("name", getDisplayName(root));
        rootNode.put("path", root.toString());
        rootNode.put("relativePath", "");
        rootNode.put("type", "directory");
        rootNode.put("disabled", false);
        rootNode.put("sqlFile", false);
        rootNode.put("textFile", false);
        rootNode.put("previewFile", false);
        rootNode.put("hasChildren", true);
        rootNode.put("children", listChildren(rootToken, ""));
        rootNode.put("loaded", true);
        return rootNode;
    }

    static List<Map<String, Object>> listChildren(String rootToken, String relativePath) throws IOException {
        Path root = getRoot(rootToken);
        Path directory = resolveInRoot(root, relativePath);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Selected path is not a directory");
        }

        List<Path> childPaths = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(SqlDirectoryTreeStore::isVisibleChild)
                    .sorted(SqlDirectoryTreeStore::comparePath)
                    .limit(MAX_CHILDREN + 1L)
                    .forEach(childPaths::add);
        }

        List<Map<String, Object>> children = new ArrayList<>();
        int childCount = Math.min(childPaths.size(), MAX_CHILDREN);
        for (int index = 0; index < childCount; index++) {
            children.add(toNode(rootToken, root, childPaths.get(index)));
        }

        if (childPaths.size() > MAX_CHILDREN) {
            Map<String, Object> overflowNode = new HashMap<>();
            overflowNode.put("key", rootToken + ":overflow:" + relativePath);
            overflowNode.put("name", "Only first " + MAX_CHILDREN + " entries are shown");
            overflowNode.put("path", "");
            overflowNode.put("relativePath", relativePath);
            overflowNode.put("type", "file");
            overflowNode.put("disabled", true);
            overflowNode.put("sqlFile", false);
            overflowNode.put("textFile", false);
            overflowNode.put("previewFile", false);
            overflowNode.put("hasChildren", false);
            overflowNode.put("loaded", true);
            children.add(overflowNode);
        }

        return children;
    }

    static Map<String, Object> createChild(String rootToken, String parentRelativePath, String rawName, String type)
            throws IOException {
        Path root = getRoot(rootToken);
        Path parent = resolveInRoot(root, parentRelativePath);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Selected path is not a directory");
        }

        boolean directory = "directory".equals(type);
        boolean file = "file".equals(type);
        if (!directory && !file) {
            throw new IllegalArgumentException("Unsupported SQL directory child type");
        }

        String name = file ? normalizeFileName(rawName, "sql") : normalizeDirectoryName(rawName);
        Path target = parent.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("File or directory already exists");
        }

        if (directory) {
            Files.createDirectory(target);
        } else {
            Files.createFile(target);
        }

        Path realTarget = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realTarget.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("createdNode", toNode(rootToken, root, realTarget));
        result.put("children", listChildren(rootToken, parentRelativePath));
        return result;
    }

    static Map<String, Object> saveFile(String rootToken, String parentRelativePath, String rawName, String content)
            throws IOException {
        Path root = getRoot(rootToken);
        Path parent = resolveInRoot(root, parentRelativePath);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Selected path is not a directory");
        }

        String name = normalizeFileName(rawName, "sql");
        Path realTarget = writeAvailableFile(root, parent, name, content == null ? "" : content);
        if (!realTarget.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("createdNode", toNode(rootToken, root, realTarget));
        result.put("children", listChildren(rootToken, parentRelativePath));
        return result;
    }

    static Map<String, Object> renameChild(String rootToken, String relativePath, String rawName)
            throws IOException {
        Path operationPath = getOperationPath(getRoot(rootToken), relativePath);
        return OSOperateUtil.withLocalFileOperationLock(
                operationPath,
                ignored -> renameChildLocked(rootToken, relativePath, rawName)
        );
    }

    private static Map<String, Object> renameChildLocked(String rootToken, String relativePath, String rawName)
            throws IOException {
        Path root = getRoot(rootToken);
        Path source = resolveInRoot(root, relativePath);
        if (source.equals(root)) {
            throw new IllegalArgumentException("Selected SQL directory root cannot be renamed");
        }
        if (Files.isSymbolicLink(source)) {
            throw new IllegalArgumentException("Symbolic links are not supported");
        }

        boolean file = Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS);
        boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        if (!file && !directory) {
            throw new IllegalArgumentException("Selected path is not available");
        }
        String sourceFileName = source.getFileName().toString();
        String fallbackExtension = file ? getFileExtension(sourceFileName) : "";
        String name = file ? normalizeExistingFileName(rawName, fallbackExtension)
                : normalizeDirectoryName(rawName);
        Path parent = source.getParent();
        Path target = parent.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !source.equals(target)) {
            throw new IllegalArgumentException("File or directory already exists");
        }

        Path movedTarget;
        try {
            movedTarget = Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            movedTarget = Files.move(source, target);
        }
        Path realTarget = movedTarget.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realTarget.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }

        String parentRelativePath = toRelativePath(root, parent);
        Map<String, Object> result = new HashMap<>();
        result.put("renamedNode", toNode(rootToken, root, realTarget));
        result.put("parentRelativePath", parentRelativePath);
        result.put("children", listChildren(rootToken, parentRelativePath));
        return result;
    }

    static Map<String, Object> deleteChild(String rootToken, String relativePath) throws IOException {
        Path operationPath = getOperationPath(getRoot(rootToken), relativePath);
        return OSOperateUtil.withLocalFileOperationLock(
                operationPath,
                ignored -> deleteChildLocked(rootToken, relativePath)
        );
    }

    private static Map<String, Object> deleteChildLocked(String rootToken, String relativePath) throws IOException {
        Path root = getRoot(rootToken);
        Path target = resolveInRoot(root, relativePath);
        if (target.equals(root)) {
            throw new IllegalArgumentException("Selected SQL directory root cannot be deleted");
        }
        if (Files.isSymbolicLink(target)) {
            throw new IllegalArgumentException("Symbolic links are not supported");
        }

        boolean file = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        if (!file && !directory) {
            throw new IllegalArgumentException("Selected path is not available");
        }
        Path parent = target.getParent();
        moveToTrash(target);

        String parentRelativePath = toRelativePath(root, parent);
        Map<String, Object> result = new HashMap<>();
        result.put("parentRelativePath", parentRelativePath);
        result.put("children", listChildren(rootToken, parentRelativePath));
        return result;
    }

    static String getDirectoryPath(String rootToken, String relativePath) throws IOException {
        Path root = getRoot(rootToken);
        Path target = resolveInRoot(root, relativePath);
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            return target.toString();
        }
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = target.getParent();
            if (parent != null && parent.startsWith(root)) {
                return parent.toString();
            }
        }
        throw new IllegalArgumentException("Selected path is not available");
    }

    static Map<String, Object> readPreview(String rootToken, String relativePath) throws IOException {
        PreviewResource resource = resolvePreviewResource(rootToken, relativePath);
        Map<String, Object> result = new HashMap<>();
        result.put("mimeType", resource.mimeType());
        result.put("size", resource.size());
        result.put("etag", resource.etag());
        result.put("url", PreviewResourceScheme.createUrl(rootToken, relativePath, resource.etag()));
        return result;
    }

    static PreviewResource resolvePreviewResource(String rootToken, String relativePath) throws IOException {
        Path root = getRoot(rootToken);
        Path target = resolveInRoot(root, relativePath);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !isSupportedPreviewFile(target.getFileName().toString())) {
            throw new IllegalArgumentException("Selected file does not support preview");
        }
        long size = Files.size(target);
        if (size > MAX_PREVIEW_BYTES) {
            throw new IllegalArgumentException("Preview file exceeds the 50 MB limit");
        }
        long lastModifiedMillis = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS).toMillis();
        String mimeType = getPreviewMimeType(getFileExtension(target.getFileName().toString()));
        String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(lastModifiedMillis) + "\"";
        return new PreviewResource(target, mimeType, size, lastModifiedMillis, etag);
    }

    private static Map<String, Object> toNode(String rootToken, Path root, Path path) throws IOException {
        Path relativePath = root.relativize(path);
        boolean symbolicLink = Files.isSymbolicLink(path);
        boolean directory = !symbolicLink && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        boolean file = !symbolicLink && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        String name = path.getFileName().toString();
        boolean sqlFile = file && isSqlFile(name);
        boolean textFile = file && isSupportedTextFile(name);
        boolean previewFile = file && isSupportedPreviewFile(name);

        Map<String, Object> node = new HashMap<>();
        node.put("key", rootToken + ":" + relativePath);
        node.put("rootToken", rootToken);
        node.put("name", name);
        node.put("path", path.toString());
        node.put("relativePath", relativePath.toString());
        node.put("type", directory ? "directory" : "file");
        node.put("disabled", false);
        node.put("sqlFile", sqlFile);
        node.put("textFile", textFile);
        node.put("previewFile", previewFile);
        node.put("fileExtension", file ? getFileExtension(name) : "");
        node.put("hasChildren", directory);
        node.put("loaded", !directory);
        return node;
    }

    private static boolean isVisibleChild(Path path) {
        if (Files.isSymbolicLink(path)) {
            return false;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isSqlFile(String fileName) {
        return "sql".equals(getFileExtension(fileName));
    }

    private static boolean isSupportedTextFile(String fileName) {
        return SUPPORTED_TEXT_EXTENSIONS.contains(getFileExtension(fileName));
    }

    private static boolean isSupportedPreviewFile(String fileName) {
        String extension = getFileExtension(fileName);
        return SUPPORTED_IMAGE_EXTENSIONS.contains(extension) || SUPPORTED_PREVIEW_EXTENSIONS.contains(extension);
    }

    private static String getPreviewMimeType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "avif" -> "image/avif";
            case "pdf" -> "application/pdf";
            default -> "image/png";
        };
    }

    private static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean hasFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 && dotIndex < fileName.length() - 1;
    }

    private static Path getRoot(String rootToken) {
        Path root = ROOTS.get(rootToken);
        if (root == null) {
            throw new IllegalArgumentException("SQL directory root is not available");
        }
        return root;
    }

    private static String normalizeDirectoryName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("File or directory name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("File or directory name cannot include path separators");
        }
        return name;
    }

    private static String normalizeFileName(String rawName, String fallbackExtension) {
        String name = normalizeDirectoryName(rawName);
        if (isSupportedTextFile(name)) {
            return name;
        }
        if (hasFileExtension(name)) {
            throw new IllegalArgumentException("Only text files are supported");
        }
        return name + "." + fallbackExtension;
    }

    private static String normalizeExistingFileName(String rawName, String fallbackExtension) {
        String name = normalizeDirectoryName(rawName);
        if (hasFileExtension(name) || fallbackExtension.isEmpty()) {
            return name;
        }
        return name + "." + fallbackExtension;
    }

    private static Path writeAvailableFile(Path root, Path parent, String fileName, String content) throws IOException {
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        for (int index = 0; index <= MAX_CHILDREN; index++) {
            String nextName = index == 0 ? fileName : baseName + "-" + index + extension;
            Path target = parent.resolve(nextName).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Path is outside of the selected SQL directory");
            }

            try {
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return target.toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (FileAlreadyExistsException exception) {
            }
        }

        throw new IOException("Unable to find an available SQL file name");
    }

    private static void moveToTrash(Path target) throws IOException {
        boolean moved = java.awt.Desktop.getDesktop().moveToTrash(target.toFile());
        if (!moved) {
            throw new IOException("Failed to move file or directory to trash");
        }
    }

    private static String toRelativePath(Path root, Path target) {
        if (target == null || target.equals(root)) {
            return "";
        }
        return root.relativize(target).toString();
    }

    private static Path getOperationPath(Path root, String relativePath) {
        String pathText = relativePath == null ? "" : relativePath;
        Path target = pathText.isEmpty() ? root : root.resolve(pathText).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }
        return target;
    }

    private static Path resolveInRoot(Path root, String relativePath) throws IOException {
        Path target = getOperationPath(root, relativePath);
        Path current = root;
        Path relativeTarget = root.relativize(target);
        for (Path segment : relativeTarget) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not supported");
            }
        }
        Path realPath = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!realPath.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside of the selected SQL directory");
        }
        return realPath;
    }

    record PreviewResource(Path path, String mimeType, long size, long lastModifiedMillis, String etag) {
    }

    private static String getDisplayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static int comparePath(Path left, Path right) {
        boolean leftDirectory = Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS);
        boolean rightDirectory = Files.isDirectory(right, LinkOption.NOFOLLOW_LINKS);
        if (leftDirectory != rightDirectory) {
            return leftDirectory ? -1 : 1;
        }
        Comparator<String> comparator = String.CASE_INSENSITIVE_ORDER;
        int result = comparator.compare(left.getFileName().toString(), right.getFileName().toString());
        if (result != 0) {
            return result;
        }
        return left.getFileName().toString().compareTo(right.getFileName().toString());
    }
}
