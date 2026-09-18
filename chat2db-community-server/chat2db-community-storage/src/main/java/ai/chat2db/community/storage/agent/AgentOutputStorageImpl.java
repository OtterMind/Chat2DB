package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.output.*;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.service.agent.AgentSessionStorage;
import ai.chat2db.community.domain.api.service.agent.IAgentOutputStorage;
import ai.chat2db.community.storage.StorageFileUtils;
import ai.chat2db.community.tools.exception.storage.StorageException;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Owns only V2 tool outputs. Published files remain until their conversation is deleted. */
@Component
public class AgentOutputStorageImpl implements IAgentOutputStorage {
    private static final int MAX_APPEND_BYTES = 256 * 1024;
    private final AgentV2StoragePaths paths;
    private final StorageFileUtils files;
    private final AgentStorageOwnership ownership;
    private final long fileLimit;
    private final long sessionLimit;
    private final long totalLimit;
    private final Map<String, Upload> uploads = new HashMap<>();
    private boolean initialized;
    private long totalBytes;
    private final Map<String, Long> sessionBytes = new HashMap<>();

    @Autowired
    public AgentOutputStorageImpl(AgentV2StoragePaths paths, StorageFileUtils files, AgentSessionStorage sessions,
            @Value("${chat2db.agent.v2.outputs.max-file-bytes:268435456}") long fileLimit,
            @Value("${chat2db.agent.v2.outputs.max-session-bytes:1073741824}") long sessionLimit,
            @Value("${chat2db.agent.v2.outputs.max-total-bytes:5368709120}") long totalLimit) {
        this.paths = paths;
        this.files = files;
        this.ownership = new AgentStorageOwnership(sessions);
        if (fileLimit < 1 || sessionLimit < 1 || totalLimit < 1) {
            throw new IllegalArgumentException("Output storage limits must be positive");
        }
        this.fileLimit = fileLimit;
        this.sessionLimit = sessionLimit;
        this.totalLimit = totalLimit;
    }

    @Override
    public AgentOutputReference save(AgentToolExecutionContext context, String format, OutputWriter writer,
            boolean complete, String warning) {
        AgentOutputUpload upload;
        try {
            upload = begin(context, format);
        } catch (RuntimeException exception) {
            return AgentOutputReference.unavailable(combineWarnings(warning, "Complete output could not be saved: " + message(exception)));
        }
        synchronized (this) {
            if (!uploads.containsKey(upload.uploadId())) return reference(context.sessionId(), context.userId(), upload.uploadId());
        }
        try {
            writer.write(new OutputStream() {
                @Override public void write(int value) { append(context, upload.uploadId(), new byte[]{(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int length) {
                    for (int start = offset; start < offset + length; start += MAX_APPEND_BYTES) {
                        append(context, upload.uploadId(), Arrays.copyOfRange(bytes, start,
                                Math.min(offset + length, start + MAX_APPEND_BYTES)));
                    }
                }
            });
        } catch (IOException | RuntimeException exception) {
            warning = combineWarnings(warning, "Output capture was interrupted: " + message(exception));
        }
        return finish(context, upload.uploadId(), complete && warning == null, warning);
    }

    @Override
    public synchronized AgentOutputUpload begin(AgentToolExecutionContext context, String format) {
        ownership.require(context.sessionId(), context.userId());
        initialize();
        String extension = extension(format);
        String id = artifactId(context);
        Upload active = uploads.get(id);
        if (active != null) {
            requireUpload(context, id);
            return new AgentOutputUpload(id);
        }
        Path directory = paths.toolResultsDirectory(context.sessionId(), context.runId());
        createDirectory(paths.toolResultsDirectory(context.sessionId()));
        createDirectory(directory);
        Path target = directory.resolve(id + "." + extension);
        Path metadata = directory.resolve(id + ".meta.json");
        files.verifyInsideRoot(paths.root(), target);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            reference(context.sessionId(), context.userId(), target.toString());
            return new AgentOutputUpload(id);
        }
        try {
            Path temporary = directory.resolve(id + ".part");
            files.rejectSymbolicLink(temporary);
            Files.createFile(temporary);
            privateFile(temporary);
            totalBytes = usedBytes(paths.sessionsDirectory());
            sessionBytes.put(context.sessionId(), usedBytes(paths.toolResultsDirectory(context.sessionId())));
            uploads.put(id, new Upload(context.sessionId(), context.runId(), context.toolCallId(), context.userId(),
                    format, temporary, target, metadata));
            return new AgentOutputUpload(id);
        } catch (IOException exception) {
            throw new StorageException("Failed to begin tool output", exception);
        }
    }

    @Override
    public synchronized void append(AgentToolExecutionContext context, String uploadId, byte[] bytes) {
        if (bytes == null || bytes.length > MAX_APPEND_BYTES) {
            throw new IllegalArgumentException("Output chunk must be at most 256 KiB");
        }
        Upload upload = requireUpload(context, uploadId);
        if (upload == null) {
            reference(context.sessionId(), context.userId(), uploadId);
            return;
        }
        if (upload.warning != null) return;
        ownership.require(context.sessionId(), context.userId());
        files.verifyInsideRoot(paths.root(), upload.temporary);
        long available = Math.max(0, Math.min(fileLimit - upload.bytes, Math.min(
                sessionLimit - sessionBytes.getOrDefault(upload.sessionId, 0L), totalLimit - totalBytes)));
        int length = (int) Math.min(bytes.length, available);
        try {
            if (length > 0) {
                Files.write(upload.temporary, Arrays.copyOf(bytes, length), StandardOpenOption.APPEND,
                        LinkOption.NOFOLLOW_LINKS);
                upload.bytes += length;
                totalBytes += length;
                sessionBytes.merge(upload.sessionId, (long) length, Long::sum);
            }
            if (length < bytes.length) upload.warning = "Output storage quota reached; only partial output was saved";
        } catch (IOException exception) {
            upload.warning = "Output file could not be fully written: " + message(exception);
            try {
                long accepted = Files.size(upload.temporary) - upload.bytes;
                upload.bytes += accepted;
                totalBytes += accepted;
                sessionBytes.merge(upload.sessionId, accepted, Long::sum);
            } catch (IOException ignored) {
                // The original write warning remains authoritative if the device is no longer readable.
            }
        }
    }

    @Override
    public synchronized AgentOutputReference finish(AgentToolExecutionContext context, String uploadId,
            boolean complete, String warning) {
        Upload upload = requireUpload(context, uploadId);
        if (upload == null) {
            return reference(context.sessionId(), context.userId(), paths.toolResultsDirectory(context.sessionId(),
                    context.runId()).resolve(uploadId + ".meta.json").toString());
        }
        try {
            ownership.require(context.sessionId(), context.userId());
            files.verifyInsideRoot(paths.root(), upload.temporary);
            String finalWarning = combineWarnings(warning, upload.warning);
            if (upload.bytes == 0 && finalWarning != null) {
                Files.deleteIfExists(upload.temporary);
                return AgentOutputReference.unavailable(finalWarning);
            }
            if (!complete || finalWarning != null) {
                trimIncompleteUtf8(upload.temporary);
                long removed = upload.bytes - Files.size(upload.temporary);
                totalBytes -= removed;
                sessionBytes.merge(upload.sessionId, -removed, Long::sum);
                upload.bytes = Files.size(upload.temporary);
            }
            moveAtomically(upload.temporary, upload.target);
            AgentOutputReference reference = new AgentOutputReference("file", uploadId, upload.target.toString(),
                    upload.format, upload.bytes, complete && finalWarning == null, true, finalWarning);
            Metadata metadata = new Metadata(context.sessionId(), context.runId(), context.toolCallId(),
                    context.userId(), reference);
            files.writeAtomically(upload.metadata, JSON.toJSONString(metadata));
            return reference;
        } catch (IOException | RuntimeException exception) {
            // Drop the temporary part file, so the same tool call can be retried without restarting the process.
            try {
                Files.deleteIfExists(upload.temporary);
            } catch (IOException ignored) {
                // A leftover part file is discarded when the storage is initialized again.
            }
            return AgentOutputReference.unavailable(combineWarnings(warning,
                    "Complete output could not be published: " + message(exception)));
        } finally {
            uploads.remove(uploadId);
        }
    }

    @Override
    public AgentOutputReference reference(String sessionId, Long userId, String pathOrArtifactId) {
        return resolve(sessionId, userId, pathOrArtifactId).reference();
    }

    @Override
    public InputStream open(String sessionId, Long userId, String pathOrArtifactId) throws IOException {
        Metadata metadata = resolve(sessionId, userId, pathOrArtifactId);
        return Files.newInputStream(Path.of(metadata.reference().path()), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public AgentOutputRead read(String sessionId, Long userId, String pathOrArtifactId, String cursor,
            Integer offset, Integer limit) {
        Metadata metadata = resolve(sessionId, userId, pathOrArtifactId);
        var page = readFile(Path.of(metadata.reference().path()), cursor, offset, limit);
        return new AgentOutputRead(page.content(), page.nextCursor(), page.hasMore(), page.startLine(), page.endLine(),
                page.partialLine(), metadata.reference().complete(), sourceWarning(metadata.reference(), null));
    }

    @Override
    public AgentOutputSearch search(String sessionId, Long userId, String pathOrArtifactId, String pattern,
            boolean literal, boolean ignoreCase, String cursor, Integer limit) {
        Metadata metadata = resolve(sessionId, userId, pathOrArtifactId);
        var page = searchFile(Path.of(metadata.reference().path()), pattern, literal, ignoreCase, cursor, limit);
        return new AgentOutputSearch(page.matches(), page.nextCursor(), page.hasMore(),
                sourceWarning(metadata.reference(), page.warning()));
    }

    private static String sourceWarning(AgentOutputReference reference, String searchWarning) {
        String warning = reference.warning();
        if (!reference.complete() && (warning == null || warning.isBlank())) {
            warning = "This file contains partial output; the original tool result was not captured completely.";
        }
        if (searchWarning != null) warning = warning == null ? searchWarning : warning + " " + searchWarning;
        if (warning == null) return null;
        // Reserve the reader's existing envelope budget even when a source warning contains Unicode or controls.
        StringBuilder bounded = new StringBuilder();
        int remaining = 240;
        for (int offset = 0; offset < warning.length();) {
            int point = warning.codePointAt(offset);
            String character = new String(Character.toChars(point));
            int cost = point > 0xffff ? 12 : point < 0x20 ? 6 : point == '"' || point == '\\' ? 2 : character.getBytes(StandardCharsets.UTF_8).length;
            if (cost > remaining) break;
            bounded.append(character); remaining -= cost; offset += Character.charCount(point);
        }
        return bounded.toString();
    }

    @Override
    public AgentOutputRead readFile(Path path, String cursor, Integer offset, Integer limit) {
        return AgentOutputFileReader.read(path, cursor, offset, limit);
    }

    @Override
    public AgentOutputSearch searchFile(Path path, String pattern, boolean literal, boolean ignoreCase,
            String cursor, Integer limit) {
        return AgentOutputFileReader.search(path, pattern, literal, ignoreCase, cursor, limit);
    }

    @Override public Path managedRoot() { return paths.sessionsDirectory(); }

    private Metadata resolve(String sessionId, Long userId, String pathOrArtifactId) {
        ownership.require(sessionId, userId);
        Path root = paths.toolResultsDirectory(sessionId);
        Path metadata;
        if (pathOrArtifactId != null && pathOrArtifactId.matches("out_[a-f0-9]{32}")) {
            files.verifyInsideRoot(paths.root(), root);
            try (Stream<Path> entries = Files.walk(root, 2)) {
                metadata = entries.filter(path -> path.getFileName().toString().equals(pathOrArtifactId + ".meta.json"))
                        .findFirst().orElseThrow(() -> new StorageException("Output file does not exist"));
            } catch (IOException exception) {
                throw new StorageException("Output file does not exist", exception);
            }
        } else {
            if (pathOrArtifactId == null) throw new IllegalArgumentException("Output path is required");
            Path path = Path.of(pathOrArtifactId);
            if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root)
                    || path.getNameCount() != root.getNameCount() + 2) {
                throw new StorageException("Output path is outside the current conversation");
            }
            String name = path.getFileName().toString();
            String id = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (!id.matches("out_[a-f0-9]{32}")) throw new StorageException("Invalid output file");
            metadata = path.resolveSibling(id + ".meta.json");
            files.verifyInsideRoot(paths.root(), path);
        }
        files.verifyInsideRoot(paths.root(), metadata);
        try {
            if (Files.size(metadata) > 8192) throw new StorageException("Invalid output metadata");
            Metadata value = JSON.parseObject(Files.readString(metadata), Metadata.class);
            if (value == null || !sessionId.equals(value.sessionId()) || !userId.equals(value.userId())) {
                throw new StorageException("Output file is not owned by this conversation");
            }
            AgentOutputReference saved = value.reference();
            Path target = metadata.resolveSibling(saved.artifactId() + "." + extension(saved.format()));
            files.verifyInsideRoot(paths.root(), target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new StorageException("Output file does not exist");
            // Reconstruct the path so moving the application data directory does not invalidate metadata.
            AgentOutputReference current = new AgentOutputReference(saved.mode(), saved.artifactId(), target.toString(),
                    saved.format(), Files.size(target), saved.complete(), saved.previewTruncated(), saved.warning());
            return new Metadata(value.sessionId(), value.runId(), value.toolCallId(), value.userId(), current);
        } catch (IOException exception) {
            throw new StorageException("Output file does not exist", exception);
        }
    }

    private Upload requireUpload(AgentToolExecutionContext context, String id) {
        if (!artifactId(context).equals(id)) throw new StorageException("Upload does not belong to this tool invocation");
        Upload upload = uploads.get(id);
        if (upload != null && (!context.sessionId().equals(upload.sessionId) || !context.runId().equals(upload.runId)
                || !context.toolCallId().equals(upload.toolCallId) || !context.userId().equals(upload.userId))) {
            throw new StorageException("Upload does not belong to this tool invocation");
        }
        return upload;
    }

    private void createDirectory(Path directory) {
        files.verifyInsideRoot(paths.root(), directory);
        files.createPrivateDirectory(directory);
    }

    @PostConstruct
    synchronized void initialize() {
        if (initialized) return;
        Path sessions = paths.sessionsDirectory();
        if (Files.exists(sessions, LinkOption.NOFOLLOW_LINKS)) {
            files.verifyInsideRoot(paths.root(), sessions);
            try (Stream<Path> entries = Files.walk(sessions, 4)) {
                for (Path path : entries.filter(path -> path.getParent() != null && path.getParent().getParent() != null
                        && "tool-results".equals(path.getParent().getParent().getFileName().toString())).toList()) {
                    files.verifyInsideRoot(paths.root(), path);
                    String name = path.getFileName().toString();
                    boolean orphaned = name.matches("out_[a-f0-9]{32}\\.(json|jsonl|txt)")
                            && !Files.exists(path.resolveSibling(name.substring(0, name.indexOf('.')) + ".meta.json"),
                            LinkOption.NOFOLLOW_LINKS);
                    if (name.endsWith(".part") || name.endsWith(".tmp") || orphaned) Files.deleteIfExists(path);
                }
            } catch (IOException exception) {
                throw new StorageException("Failed to recover pending tool outputs", exception);
            }
        }
        initialized = true;
    }

    private long usedBytes(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        try (Stream<Path> entries = Files.walk(root)) {
            long total = 0;
            for (Path path : entries.filter(value -> value.getParent() != null && value.getParent().getParent() != null
                    && "tool-results".equals(value.getParent().getParent().getFileName().toString())).toList()) {
                files.verifyInsideRoot(paths.root(), path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.getFileName().toString().endsWith(".meta.json")) {
                    total += Files.size(path);
                }
            }
            return total;
        }
    }

    private static String artifactId(AgentToolExecutionContext context) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((context.sessionId() + "\n" + context.runId()
                    + "\n" + context.toolCallId()).getBytes(StandardCharsets.UTF_8));
            return "out_" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String extension(String format) {
        return switch (format) {
            case "text" -> "txt";
            case "json", "jsonl" -> format;
            default -> throw new IllegalArgumentException("Unsupported output format");
        };
    }

    private static void privateFile(Path file) throws IOException {
        try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Inherit the directory ACL on non-POSIX systems. */ }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }

    private static void trimIncompleteUtf8(Path path) throws IOException {
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0, size - 4);
            channel.position(start);
            var buffer = java.nio.ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
            byte[] bytes = buffer.array();
            int index = bytes.length - 1;
            while (index >= 0 && (bytes[index] & 0xc0) == 0x80) index--;
            if (index < 0) return;
            int lead = bytes[index] & 0xff;
            int expected = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
            if (bytes.length - index < expected) channel.truncate(start + index);
        }
    }

    private static String message(Exception exception) {
        String value = exception.getMessage();
        return value == null ? exception.getClass().getSimpleName() : value.substring(0, Math.min(300, value.length()));
    }

    private static String combineWarnings(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank() || first.equals(second)) return first;
        return first + "; " + second;
    }

    private record Metadata(String sessionId, String runId, String toolCallId, Long userId, AgentOutputReference reference) { }

    private static final class Upload {
        final String sessionId, runId, toolCallId, format;
        final Long userId;
        final Path temporary, target, metadata;
        long bytes;
        String warning;
        Upload(String sessionId, String runId, String toolCallId, Long userId, String format,
                Path temporary, Path target, Path metadata) {
            this.sessionId = sessionId; this.runId = runId; this.toolCallId = toolCallId; this.userId = userId;
            this.format = format; this.temporary = temporary; this.target = target; this.metadata = metadata;
        }
    }
}
