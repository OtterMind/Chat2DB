package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Set;
import java.util.LinkedHashMap;
import ai.chat2db.community.tools.util.AgentTrace;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

public class AiAgentSkillServiceImpl implements IAiAgentSkillService {
    private static final Pattern COMMAND = Pattern.compile("^/skill:([^\\s]+)(?:\\s+([\\s\\S]*))?$");
    private final Resource catalog;
    private final Path resourceRoot;
    private final Path userRoot;
    private final Path legacyRoot;
    private final Map<Path, AiAgentSkill> userSkills = new LinkedHashMap<>();
    private final Map<Path, String> errors = new HashMap<>();
    private final Map<String, List<AiAgentSkill>> selected = new HashMap<>();
    private List<AiAgentSkill> prepared;

    public AiAgentSkillServiceImpl(Resource catalog, Path resourceRoot) {
        this(catalog, resourceRoot, null, null);
    }

    public AiAgentSkillServiceImpl(Resource catalog, Path resourceRoot, Path userRoot, Path legacyRoot) {
        this.catalog = catalog;
        this.resourceRoot = resourceRoot.toAbsolutePath().normalize();
        this.userRoot = userRoot == null ? null : userRoot.toAbsolutePath().normalize();
        this.legacyRoot = legacyRoot == null ? null : legacyRoot.toAbsolutePath().normalize();
    }

    @Override
    public synchronized List<AiAgentSkill> prepare() {
        List<AiAgentSkill> builtins = builtins();
        if (userRoot == null) return builtins;
        Path source = userDirectory();
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
                String digest = digest(sourceSkill.files());
                if (!sourceSkill.executables().isEmpty()) {
                    var hashFiles = new TreeMap<>(sourceSkill.files());
                    hashFiles.put("\0executable-files", String.join("\n", sourceSkill.executables().stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
                    digest = digest(hashFiles);
                }
                Path root = resourceDirectory();
                Path version = root.resolve(digest);
                Files.createDirectories(version);
                if (!version.toRealPath().equals(version)) throw new IOException("Invalid skill version directory");
                Path snapshot = version.resolve(sourceSkill.name());
                materialize(root, snapshot, sourceSkill.files(), sourceSkill.executables());
                names.add(sourceSkill.name());
                userSkills.put(directory, new AiAgentSkill(sourceSkill.name(), snapshot.resolve("SKILL.md").toString(), digest));
                errors.remove(directory);
            } catch (IOException | IllegalArgumentException error) {
                String message = java.util.Objects.toString(error.getMessage(), "Invalid skill source");
                if (!message.equals(errors.put(directory, message))) {
                    AgentTrace.record("skills.source.invalid", null, null, Map.of("path", directory.toString(), "reason", message));
                }
                // A partially edited source must not replace the last working snapshot.
                AiAgentSkill previous = userSkills.get(directory);
                if (previous != null && !names.add(previous.name())) userSkills.remove(directory);
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

    @Override public Path userDirectory() {
        if (userRoot == null) return null;
        try {
            if (Files.isSymbolicLink(userRoot)) throw new IOException("User skill directory cannot be a symbolic link");
            Files.createDirectories(userRoot);
            return userRoot.toRealPath();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot open user skill directory", error);
        }
    }

    @Override public Path resourceDirectory() {
        try {
            if (Files.isSymbolicLink(resourceRoot)) throw new IOException("Skill resources cannot be a symbolic link");
            Files.createDirectories(resourceRoot);
            return resourceRoot.toRealPath();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot open skill resource directory", error);
        }
    }

    @Override public Path resolveLegacyPath(Path path) {
        return legacyRoot != null && path.startsWith(legacyRoot)
                ? resourceDirectory().resolve(legacyRoot.relativize(path)) : path;
    }

    private List<AiAgentSkill> builtins() {
        if (prepared != null) return prepared;
        try (var input = catalog.getInputStream()) {
            JsonNode entries = new ObjectMapper().readTree(input).path("skills");
            if (!entries.isArray()) throw new IOException("Skill catalog must contain a skills array");
            Path root = resourceDirectory();
            List<AiAgentSkill> skills = new ArrayList<>();
            for (JsonNode entry : entries) {
                String name = entry.path("name").asText();
                if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || name.length() > 64
                        || skills.stream().anyMatch(skill -> skill.name().equals(name))) {
                    throw new IOException("Invalid or duplicate skill name: " + name);
                }
                Map<String, byte[]> files = readFiles(name, entry.path("files"));
                String digest = digest(files);
                Path version = root.resolve(digest);
                Files.createDirectories(version);
                if (!version.toRealPath().equals(version)) throw new IOException("Invalid skill version directory");
                Path directory = version.resolve(name);
                materialize(root, directory, files, Set.of());
                skills.add(new AiAgentSkill(name, directory.resolve("SKILL.md").toString(), digest));
            }
            prepared = List.copyOf(skills);
            return prepared;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot prepare built-in Agent skills", error);
        }
    }

    @Override
    public AiAgentSkillResolveResponse resolve(AiAgentSkillResolveRequest aiAgentSkillResolveRequest) {
        String message = aiAgentSkillResolveRequest.message();
        var match = COMMAND.matcher(message.stripLeading());
        if (!match.matches()) return new AiAgentSkillResolveResponse(message, null);
        String name = match.group(1);
        if (prepare().stream().noneMatch(skill -> skill.name().equals(name))) {
            throw new IllegalArgumentException("Unknown skill: " + name);
        }
        return new AiAgentSkillResolveResponse(match.group(2) == null ? "" : match.group(2).strip(), name);
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

    private String digest(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.forEach((name, content) -> {
                digest.update((name + "\0" + content.length + "\0").getBytes(StandardCharsets.UTF_8));
                digest.update(content);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable for Agent skills", error);
        }
    }

    private void materialize(Path root, Path directory, Map<String, byte[]> files, Set<String> executables) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = Files.createTempDirectory(root, ".preparing-");
            try {
                for (var file : files.entrySet()) {
                    Path target = staging.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, file.getValue());
                    if (executables.contains(file.getKey()) && Files.getFileStore(target).supportsFileAttributeView("posix")) {
                        var permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(target));
                        permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                        Files.setPosixFilePermissions(target, permissions);
                    }
                }
                try {
                    Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException concurrentPreparation) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw concurrentPreparation;
                    // Another process published this content version; verify it below.
                }
            } finally {
                if (Files.exists(staging)) {
                    try (var paths = Files.walk(staging)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
        if (Files.isSymbolicLink(directory)) throw new IOException("Skill directory is a symbolic link");
        for (var file : files.entrySet()) {
            Path path = directory.resolve(file.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !path.toRealPath().equals(path)
                    || !Arrays.equals(Files.readAllBytes(path), file.getValue())) {
                throw new IOException("Skill resource differs from the packaged version: " + file.getKey());
            }
        }
    }
}
