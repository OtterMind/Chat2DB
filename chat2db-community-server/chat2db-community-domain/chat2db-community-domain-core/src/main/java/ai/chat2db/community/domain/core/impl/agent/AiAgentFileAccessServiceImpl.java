package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.IAiAgentFileAccessService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentOutputService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.domain.api.service.agent.IAiAgentWorkspaceService;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.tools.agent.tool.IAgentToolResult;
import ai.chat2db.community.tools.model.agent.tool.AgentOutputReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

public class AiAgentFileAccessServiceImpl implements IAiAgentFileAccessService {
    private final List<IAiAgentWorkspaceService> workspaces;
    private final IAiAgentSkillService skills;
    private final IAiAgentOutputService outputs;
    private final ObjectMapper json = new ObjectMapper();

    public AiAgentFileAccessServiceImpl(List<IAiAgentWorkspaceService> workspaces, IAiAgentSkillService skills,
            IAiAgentOutputService outputs) {
        this.workspaces = List.copyOf(workspaces);
        this.skills = skills;
        this.outputs = outputs;
    }

    @Override
    public IAgentToolResult<?> execute(AgentToolExecutionContext context, String toolName, Map<String, Object> arguments) {
        String sessionId = context.sessionId();
        Long userId = context.userId();
        if (!List.of("read", "grep", "ls", "find").contains(toolName)) throw new IllegalArgumentException("Unknown file tool");
        String path = string(arguments, "path", "read".equals(toolName) ? null : ".");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("File path is required");
        String cwd = Path.of(path).isAbsolute() || workspaces.isEmpty() ? null : workspaces.get(0).resolveWorkingDirectory(sessionId);
        Path target = normalizeAliases(skills.resolveLegacyPath(resolve(path, cwd)), cwd);
        String cursor = string(arguments, "cursor", null);
        Integer limit = integer(arguments, "limit");
        boolean skill = skillRoots(sessionId).stream().anyMatch(target::startsWith);
        Path resources = skills.resourceDirectory();
        if (!skill && resources != null && target.startsWith(resources)) {
            throw new SecurityException("Skill resource path is not loaded. Resolve references from the exact loaded entry: "
                    + skills.selected(sessionId).stream().map(item -> item.entryPath()).toList());
        }
        if ("ls".equals(toolName) || "find".equals(toolName)) {
            if (cwd == null && !workspaces.isEmpty()) cwd = workspaces.get(0).resolveWorkingDirectory(sessionId);
            authorizeNative(sessionId, toolName, cwd, arguments);
            return list(context, existing(normalizeAliases(target, cwd)), toolName, arguments);
        }
        if (target.startsWith(outputs.managedRoot()) && !skill) {
            // Storage resolves only published files belonging to this conversation.
            return new FileResult(true, "read".equals(toolName)
                    ? outputs.read(sessionId, userId, target.toString(), cursor, integer(arguments, "offset"), limit)
                    : outputs.search(sessionId, userId, target.toString(), pattern(arguments), bool(arguments, "literal"),
                            bool(arguments, "ignoreCase"), cursor, limit), null);
        }
        if (!skill) {
            if (cwd == null && !workspaces.isEmpty()) cwd = workspaces.get(0).resolveWorkingDirectory(sessionId);
            authorizeNative(sessionId, toolName, cwd, arguments);
        }
        Path file = existing(normalizeAliases(target, cwd));
        if ("read".equals(toolName)) return new FileResult(true, outputs.readFile(file, cursor, integer(arguments, "offset"), limit), null);
        if (Files.isDirectory(file)) return new FileResult(true, searchDirectory(sessionId, file, arguments, skill), null);
        return new FileResult(true, outputs.searchFile(file, pattern(arguments), bool(arguments, "literal"), bool(arguments, "ignoreCase"), cursor, limit), null);
    }

    private IAgentToolResult<?> list(AgentToolExecutionContext context, Path directory, String toolName, Map<String, Object> arguments) {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Path must be a directory");
        Integer requestedLimit = integer(arguments, "limit");
        if (requestedLimit != null && requestedLimit <= 0) throw new IllegalArgumentException("limit must be positive");
        long limit = requestedLimit == null ? Long.MAX_VALUE : requestedLimit;
        DirectoryOutput output = new DirectoryOutput(context, limit);
        try {
            if ("ls".equals(toolName)) {
                try (var paths = Files.newDirectoryStream(directory)) {
                    for (Path file : paths) {
                        if (protectedPath(context.sessionId(), file)) continue;
                        if (!output.append(file, Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))) break;
                    }
                }
            } else {
                String glob = pattern(arguments);
                var matcher = directory.getFileSystem().getPathMatcher("glob:" + glob);
                var rootMatcher = glob.startsWith("**/") ? directory.getFileSystem().getPathMatcher("glob:" + glob.substring(3)) : matcher;
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    private FileVisitResult accept(Path path, BasicFileAttributes attributes) throws IOException {
                        if (matcher.matches(directory.relativize(path)) || rootMatcher.matches(path.getFileName())) {
                            return output.append(path, attributes) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                        }
                        return context.active().getAsBoolean() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                    }
                    @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                        if (protectedPath(context.sessionId(), path)) return FileVisitResult.SKIP_SUBTREE;
                        return directory.equals(path) ? FileVisitResult.CONTINUE : accept(path, attributes);
                    }
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                        return accept(path, attributes);
                    }
                });
            }
        } catch (IOException | RuntimeException failure) {
            output.warning = "Directory enumeration stopped before completion: " + failure.getClass().getSimpleName();
            output.complete = false;
        }
        if (!context.active().getAsBoolean()) { output.complete = false; output.warning = "Directory enumeration was cancelled."; }
        return output.finish();
    }

    private final class DirectoryOutput {
        final AgentToolExecutionContext context;
        final long limit;
        List<Map<String, Object>> inline = new ArrayList<>();
        final List<Map<String, Object>> preview = new ArrayList<>();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        String upload;
        String warning;
        long count;
        long size;
        int previewBytes;
        boolean complete = true;
        boolean hasMore;
        DirectoryOutput(AgentToolExecutionContext context, long limit) { this.context = context; this.limit = limit; }

        boolean append(Path path, BasicFileAttributes attributes) throws IOException {
            if (!context.active().getAsBoolean()) return false;
            if (count >= limit) { hasMore = true; return false; }
            Map<String, Object> entry = Map.of("path", path.toString(), "type",
                    attributes.isSymbolicLink() ? "symlink" : attributes.isDirectory() ? "directory" : "file");
            byte[] line = json.writeValueAsBytes(entry);
            if (size + line.length + 1 > 256L * 1024 * 1024) {
                complete = false; hasMore = true; warning = "Directory output reached the file size limit."; return false;
            }
            if (previewBytes + line.length < 7 * 1024) { preview.add(entry); previewBytes += line.length; }
            if (inline != null) inline.add(entry);
            pending.write(line); pending.write('\n'); size += line.length + 1; count++;
            if (upload == null && size > 30 * 1024) {
                upload = outputs.begin(context, "jsonl").uploadId();
                inline = null;
            }
            if (upload != null && pending.size() >= 48 * 1024) flush();
            return true;
        }
        void flush() {
            if (pending.size() == 0) return;
            byte[] bytes = pending.toByteArray();
            try {
                for (int offset = 0; offset < bytes.length; offset += 48 * 1024) {
                    outputs.append(context, upload, Base64.getEncoder().encodeToString(
                            java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 48 * 1024))));
                }
            } finally { pending.reset(); } // Never replay an ambiguously written chunk after a disk error.
        }
        IAgentToolResult<?> finish() {
            AgentOutputReference reference = null;
            if (upload != null) {
                try { flush(); reference = outputs.finish(context, upload, complete, warning); }
                catch (RuntimeException failure) { reference = AgentOutputReference.unavailable("Directory output could not be saved."); }
            } else if (!complete) reference = AgentOutputReference.unavailable(warning);
            return new FileResult(context.active().getAsBoolean(), Map.of("entries", reference != null || inline == null ? preview : inline,
                    "count", count, "hasMore", hasMore || !complete), reference);
        }
    }

    @Override
    public void authorizeNative(String sessionId, String toolName, String cwd, Map<String, Object> arguments) {
        authorizedDirectory(sessionId, toolName, cwd, arguments);
    }

    @Override public String userSkillDirectory() {
        Path directory = skills.userDirectory();
        return directory == null ? null : directory.toString();
    }

    @Override
    public String authorizedDirectory(String sessionId, String toolName, String cwd, Map<String, Object> arguments) {
        boolean shell = "bash".equals(toolName) || "powershell".equals(toolName);
        Path target = shell ? null : normalizeAliases(resolve(string(arguments, "path", "."), cwd), cwd);
        Path userSkills = skills.userDirectory();
        boolean userSkill = target != null && userSkills != null && target.startsWith(userSkills);
        if (!userSkill && (workspaces.isEmpty() || !workspaces.get(0).isToolEnabled(toolName))) {
            throw new SecurityException("Access to user files is disabled for this tool");
        }
        if (shell) return cwd;
        Path root;
        try { root = userSkill ? userSkills : Path.of(cwd).toRealPath(); }
        catch (IOException error) { throw new IllegalArgumentException("Working directory does not exist", error); }
        if (!target.startsWith(root) || protectedPath(sessionId, target)) {
            throw new SecurityException("File path is outside the permitted user directory");
        }
        // Check the nearest existing ancestor too, so a new write cannot escape via a symlink.
        Path ancestor = target;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null || !existing(ancestor).startsWith(root)) {
            throw new SecurityException("File path is outside the permitted user directory");
        }
        return root.toString();
    }

    private static PathMatcher pathMatcher(Path directory, String glob) {
        try {
            return directory.getFileSystem().getPathMatcher("glob:" + glob);
        } catch (PatternSyntaxException error) {
            throw new IllegalArgumentException("Invalid glob pattern: " + glob, error);
        }
    }

    private Object searchDirectory(String sessionId, Path directory, Map<String, Object> arguments, boolean skill) {
        try {
            String glob = string(arguments, "glob", null);
            PathMatcher matcher = glob == null ? null : pathMatcher(directory, glob);
            List<Path> collected = new ArrayList<>();
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                    return !skill && protectedPath(sessionId, path) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && (matcher == null || matcher.matches(directory.relativize(path))
                            || matcher.matches(path.getFileName()))) collected.add(path);
                    return collected.size() > 2000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
            List<Path> files = collected.stream().sorted(Comparator.comparing(Path::toString)).toList();
            boolean capped = files.size() > 2000;
            if (capped) files = files.subList(0, 2000);
            SearchCursor cursor = decodeCursor(string(arguments, "cursor", null));
            int limit = Math.max(1, Math.min(100, integer(arguments, "limit") == null ? 100 : integer(arguments, "limit")));
            List<Map<String, Object>> matches = new ArrayList<>();
            int bytes = 0;
            int pages = 0;
            for (Path file : files) {
                String relative = directory.relativize(file).toString();
                if (cursor.file() != null && relative.compareTo(cursor.file()) < 0) continue;
                String next = relative.equals(cursor.file()) ? cursor.cursor() : null;
                do {
                    var page = outputs.searchFile(existing(file), pattern(arguments), bool(arguments, "literal"),
                            bool(arguments, "ignoreCase"), next, 1);
                    pages++;
                    for (var match : page.matches()) {
                        Map<String, Object> entry = Map.of("path", file.toString(), "line", match.line(),
                                "content", match.content(), "byteOffset", match.byteOffset());
                        int length = json.writeValueAsBytes(entry).length;
                        if (!matches.isEmpty() && bytes + length > 14 * 1024) {
                            return new DirectorySearch(matches, encodeCursor(relative, next), true, null);
                        }
                        matches.add(entry);
                        bytes += length;
                    }
                    next = page.nextCursor();
                    if (matches.size() >= limit || bytes >= 14 * 1024 || pages >= 128) {
                        // An exhausted file is skipped by a lexical successor marker on continuation.
                        return new DirectorySearch(matches, encodeCursor(next == null ? relative + '\0' : relative, next), true,
                                capped ? "Directory file limit reached; narrow the path or glob to search further." : page.warning());
                    }
                } while (next != null);
            }
            return new DirectorySearch(matches, null, false,
                    capped ? "Directory file limit reached; narrow the path or glob to search further." : null);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot search directory", error);
        }
    }

    private boolean protectedPath(String sessionId, Path path) {
        // User-selected parents never grant access to private run/ticket data or system output writes, and a loaded
        // bundled skill stays read-only. A loaded user skill remains editable: its own directory is the running source.
        Path managed = outputs.managedRoot().getParent();
        Path ownWorkspace = managed.resolve("workspaces").resolve(sessionId);
        Path resources = skills.resourceDirectory();
        return (path.startsWith(managed) && !path.startsWith(ownWorkspace))
                || (resources != null && path.startsWith(resources));
    }

    private List<Path> skillRoots(String sessionId) {
        return skills.selected(sessionId).stream().map(skill -> Path.of(skill.entryPath()).getParent().toAbsolutePath().normalize()).toList();
    }

    private Path normalizeAliases(Path target, String cwd) {
        // Normalize aliases of a scope root (for example /var -> /private/var on macOS),
        // while keeping the path below that root intact so existing() still rejects inner symlinks.
        List<Path> roots = new ArrayList<>();
        if (skills.resourceDirectory() != null) roots.add(skills.resourceDirectory());
        if (skills.userDirectory() != null) roots.add(skills.userDirectory());
        roots.add(outputs.managedRoot().getParent());
        if (cwd != null) roots.add(Path.of(cwd));
        for (Path root : roots) {
            Path canonical;
            try { canonical = root.toRealPath(); } catch (IOException missingRoot) { continue; }
            if (target.startsWith(canonical)) return target;
            for (Path ancestor = target; ancestor != null; ancestor = ancestor.getParent()) {
                try {
                    if (ancestor.toRealPath().equals(canonical)) return canonical.resolve(ancestor.relativize(target));
                } catch (IOException missingAncestor) { /* A new file may not exist yet. */ }
            }
        }
        return target;
    }

    private static Path resolve(String value, String cwd) {
        Path path = Path.of(value);
        for (Path segment : path) if ("..".equals(segment.toString())) throw new SecurityException("Parent path traversal is not allowed");
        if (!path.isAbsolute()) {
            if (cwd == null) throw new IllegalArgumentException("An absolute file path is required");
            path = Path.of(cwd).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path existing(Path path) {
        try {
            if (!path.toRealPath().equals(path)) throw new SecurityException("Symbolic links are not permitted for tool file access");
            return path;
        } catch (IOException error) { throw new IllegalArgumentException("File or directory does not exist", error); }
    }

    private SearchCursor decodeCursor(String cursor) {
        if (cursor == null) return new SearchCursor(null, null);
        try {
            if (cursor.length() > 8192) throw new IllegalArgumentException("Invalid directory search cursor");
            return json.readValue(Base64.getUrlDecoder().decode(cursor), SearchCursor.class);
        } catch (IOException | IllegalArgumentException error) { throw new IllegalArgumentException("Invalid directory search cursor", error); }
    }

    private String encodeCursor(String file, String cursor) throws IOException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(new SearchCursor(file, cursor)));
    }

    private static String pattern(Map<String, Object> args) {
        String pattern = string(args, "pattern", null);
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("Search pattern is required");
        return pattern;
    }
    private static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be a string");
        return text;
    }
    private static boolean bool(Map<String, Object> args, String key) { return Boolean.TRUE.equals(args.get(key)); }
    private static Integer integer(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return number.intValue();
    }
    private record SearchCursor(String file, String cursor) { }
    public record DirectorySearch(List<Map<String, Object>> matches, String nextCursor, boolean hasMore, String warning) { }
    public record FileResult(boolean ok, Object data, AgentOutputReference output) implements IAgentToolResult<Object> { }
}
