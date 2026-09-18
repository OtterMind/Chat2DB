package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import ai.chat2db.community.tools.util.AgentTrace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

public class AiAgentSkillServiceImpl implements IAiAgentSkillService {
    private static final Pattern COMMAND = Pattern.compile("^/skill:([^\\s]+)(?:\\s+([\\s\\S]*))?$");
    private static final String STAGING_PREFIX = ".staging-";
    private static final String RETIRED_PREFIX = ".retired-";
    private final Resource catalog;
    private final Path builtinRoot;
    private final Path userRoot;
    private final Map<Path, AiAgentSkill> userSkills = new LinkedHashMap<>();
    private final Map<Path, String> errors = new HashMap<>();
    private final Map<String, List<AiAgentSkill>> selected = new HashMap<>();
    private volatile List<AiAgentSkill> prepared;
    private Path resolvedBuiltinRoot;
    private Path resolvedUserRoot;
    private boolean userUnavailable;

    public AiAgentSkillServiceImpl(Resource catalog, Path builtinRoot) {
        this(catalog, builtinRoot, null);
    }

    public AiAgentSkillServiceImpl(Resource catalog, Path builtinRoot, Path userRoot) {
        this.catalog = catalog;
        this.builtinRoot = builtinRoot.toAbsolutePath().normalize();
        this.userRoot = userRoot == null ? null : userRoot.toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<AiAgentSkill> prepare() {
        List<AiAgentSkill> builtins = builtins();
        if (userRoot == null) return builtins;
        Path source = userDirectory();
        if (source == null) return builtins;
        List<Path> directories;
        try (var paths = Files.list(source)) {
            directories = paths.filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).sorted().toList();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot list user skills", error);
        }
        userSkills.keySet().retainAll(directories);
        errors.keySet().retainAll(directories);
        Set<String> names = new java.util.HashSet<>(builtins.stream().map(AiAgentSkill::name).toList());
        for (Path directory : directories) {
            try {
                var sourceSkill = new UserAgentSkillLoader().read(directory);
                if (names.contains(sourceSkill.name())) throw new IOException("Duplicate or reserved skill name: " + sourceSkill.name());
                String digest = digest(sourceSkill.files(), sourceSkill.executables());
                names.add(sourceSkill.name());
                // The user's own directory is the running resource: no copy is made and edits apply to the next run.
                userSkills.put(directory, new AiAgentSkill(sourceSkill.name(), directory.resolve("SKILL.md").toString(), digest));
                errors.remove(directory);
            } catch (IOException | IllegalArgumentException error) {
                String message = java.util.Objects.toString(error.getMessage(), "Invalid skill source");
                if (!message.equals(errors.put(directory, message))) {
                    AgentTrace.record("skills.source.invalid", null, null, Map.of("path", directory.toString(), "reason", message));
                }
                // An unusable source is not offered; the host reports it instead of serving a stale copy.
                userSkills.remove(directory);
            }
        }
        List<AiAgentSkill> result = new ArrayList<>(builtins);
        result.addAll(userSkills.values());
        return List.copyOf(result);
    }

    @Override public synchronized List<AiAgentSkill> select(String sessionId) {
        List<AiAgentSkill> skills = prepare();
        selected.put(sessionId, skills);
        return skills;
    }

    @Override public synchronized List<AiAgentSkill> selected(String sessionId) {
        return selected.getOrDefault(sessionId, builtins());
    }

    @Override public synchronized void release(String sessionId) { selected.remove(sessionId); }

    @Override public synchronized Path userDirectory() {
        if (userRoot == null || userUnavailable) return null;
        if (resolvedUserRoot == null) {
            try {
                if (Files.isSymbolicLink(userRoot)) throw new IOException("User skill directory cannot be a symbolic link");
                Files.createDirectories(userRoot);
                resolvedUserRoot = userRoot.toRealPath();
            } catch (IOException error) {
                // An unusable user root removes user skills only; it must not fail unrelated file tools.
                userUnavailable = true;
                AgentTrace.record("skills.directory.unavailable", null, null,
                        Map.of("path", userRoot.toString(), "reason", Objects.toString(error.getMessage(), "unusable")));
                return null;
            }
        }
        return resolvedUserRoot;
    }

    @Override public synchronized Path resourceDirectory() {
        if (resolvedBuiltinRoot == null) {
            try {
                if (Files.isSymbolicLink(builtinRoot)) throw new IOException("Skill resources cannot be a symbolic link");
                Files.createDirectories(builtinRoot);
                resolvedBuiltinRoot = builtinRoot.toRealPath();
            } catch (IOException error) {
                AgentTrace.record("skills.resources.unavailable", null, null,
                        Map.of("path", builtinRoot.toString(), "reason", Objects.toString(error.getMessage(), "unusable")));
                return null;
            }
        }
        return resolvedBuiltinRoot;
    }

    private List<AiAgentSkill> builtins() {
        if (prepared != null) return prepared;
        try (var input = catalog.getInputStream()) {
            JsonNode entries = new ObjectMapper().readTree(input).path("skills");
            if (!entries.isArray()) throw new IOException("Skill catalog must contain a skills array");
            Path root = resourceDirectory();
            if (root == null) throw new IOException("Cannot open skill resource directory");
            discardInterrupted(root);
            List<AiAgentSkill> skills = new ArrayList<>();
            for (JsonNode entry : entries) {
                String name = entry.path("name").asText();
                if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64
                        || skills.stream().anyMatch(skill -> skill.name().equals(name))) {
                    throw new IOException("Invalid or duplicate skill name: " + name);
                }
                Map<String, byte[]> files = readFiles(name, entry.path("files"));
                Path directory = root.resolve(name);
                install(root, directory, name, files);
                skills.add(new AiAgentSkill(name, directory.resolve("SKILL.md").toString(), digest(files, Set.of())));
            }
            prepared = List.copyOf(skills);
            return prepared;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare built-in Agent skills", error);
        }
    }

    private Map<String, byte[]> readFiles(String name, JsonNode paths) throws IOException {
        if (!paths.isArray()) throw new IOException("Missing files for skill: " + name);
        Map<String, byte[]> files = new TreeMap<>();
        for (JsonNode node : paths) {
            String file = node.asText();
            if (!file.matches("[A-Za-z0-9_-]+(?:[./][A-Za-z0-9_-]+)*") || file.contains("..")) {
                throw new IOException("Invalid skill resource path: " + file);
            }
            try (var input = catalog.createRelative(name + "/" + file).getInputStream()) {
                if (files.putIfAbsent(file, input.readAllBytes()) != null) {
                    throw new IOException("Duplicate skill resource: " + file);
                }
            }
        }
        if (!files.containsKey("SKILL.md")) throw new IOException("Missing SKILL.md for: " + name);
        return files;
    }

    private String digest(Map<String, byte[]> files, Set<String> executables) {
        Map<String, byte[]> content = files;
        if (!executables.isEmpty()) {
            content = new TreeMap<>(files);
            content.put("\0executable-files", String.join("\n", executables.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            content.forEach((name, bytes) -> {
                digest.update((name + "\0" + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable for Agent skills", error);
        }
    }

    /** Installs one packaged skill, tolerating another host that publishes the same content concurrently. */
    private void install(Path root, Path directory, String name, Map<String, byte[]> files) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (matches(directory, files)) return;
            replace(root, directory, files);
            if (matches(directory, files)) return;
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new IOException("Skill resources differ from the packaged version: " + name);
    }

    /** Replaces one installed skill as a whole, so a reader never sees a half-written directory. */
    private void replace(Path root, Path directory, Map<String, byte[]> files) throws IOException {
        Path staging = Files.createTempDirectory(root, STAGING_PREFIX);
        Path retired = null;
        try {
            for (var file : files.entrySet()) {
                Path target = staging.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                retired = root.resolve(RETIRED_PREFIX + UUID.randomUUID());
                try {
                    Files.move(directory, retired, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException concurrentPublication) {
                    // Another process replaced or removed the same skill; the packaged content below still wins.
                    if (matches(directory, files)) return;
                    retired = null;
                }
                if (retired != null) {
                    try {
                        // Moving keeps the source timestamps, which would make this fresh rollback copy look abandoned.
                        Files.setLastModifiedTime(retired, FileTime.from(Instant.now()));
                    } catch (IOException ignored) {
                        // A stale timestamp only risks cleanup; the publication itself continues.
                    }
                }
            }
            try {
                Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException failure) {
                if (matches(directory, files)) return;
                if (retired != null && !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.move(retired, directory, StandardCopyOption.ATOMIC_MOVE);
                        retired = null;
                    } catch (IOException restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
                throw failure;
            }
        } finally {
            delete(staging);
            delete(retired);
        }
    }

    private boolean matches(Path directory, Map<String, byte[]> files) {
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)
                    || !directory.toRealPath().equals(directory)) return false;
            Map<String, byte[]> actual = new TreeMap<>();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.toList()) {
                    if (path.equals(directory)) continue;
                    if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) return false;
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
                    actual.put(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
            if (!actual.keySet().equals(files.keySet())) return false;
            return files.entrySet().stream().allMatch(file -> Arrays.equals(file.getValue(), actual.get(file.getKey())));
        } catch (IOException | UncheckedIOException unreadable) {
            // A concurrent host may be swapping this directory right now; the caller republishes and verifies again.
            return false;
        }
    }

    private void discardInterrupted(Path root) {
        try (var entries = Files.list(root)) {
            for (Path path : entries.filter(this::interrupted).toList()) delete(path);
        } catch (IOException ignored) {
            // A leftover directory is retried on the next start.
        }
    }

    private boolean interrupted(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith(STAGING_PREFIX) && !name.startsWith(RETIRED_PREFIX)) return false;
        try {
            // An active publication keeps writing, so only long-abandoned directories are discarded.
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
                    .isBefore(Instant.now().minus(Duration.ofHours(1)));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void delete(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        } catch (IOException | UncheckedIOException ignored) {
            // Best effort: the next start discards interrupted directories again.
        }
    }

    @Override
    public AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest aiAgentSkillResolveRequest) {
        String message = aiAgentSkillResolveRequest.message();
        if (message == null) return new AiAgentSkillResolveResponse("", null);
        var match = COMMAND.matcher(message.stripLeading());
        if (!match.matches()) return new AiAgentSkillResolveResponse(message, null);
        String name = match.group(1);
        if (prepare().stream().noneMatch(skill -> skill.name().equals(name))) {
            throw new IllegalArgumentException("Unknown skill: " + name);
        }
        return new AiAgentSkillResolveResponse(match.group(2) == null ? "" : match.group(2).strip(), name);
    }
}
