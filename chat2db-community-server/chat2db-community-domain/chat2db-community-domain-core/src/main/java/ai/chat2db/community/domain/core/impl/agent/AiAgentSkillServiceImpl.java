package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.model.agent.skill.AiAgentSkill;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentSkillResolveRequest;
import ai.chat2db.community.domain.api.model.response.agent.AiAgentSkillResolveResponse;
import ai.chat2db.community.domain.api.service.agent.IAiAgentSkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
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
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

public class AiAgentSkillServiceImpl implements IAiAgentSkillService {
    private static final Pattern COMMAND = Pattern.compile("^/skill:([^\\s]+)(?:\\s+([\\s\\S]*))?$");
    private final Resource catalog;
    private final Path resourceRoot;
    private List<AiAgentSkill> prepared;

    public AiAgentSkillServiceImpl(Resource catalog, Path resourceRoot) {
        this.catalog = catalog;
        this.resourceRoot = resourceRoot;
    }

    @Override
    public synchronized List<AiAgentSkill> prepare() {
        if (prepared != null) return prepared;
        try (var input = catalog.getInputStream()) {
            JsonNode entries = new ObjectMapper().readTree(input).path("skills");
            if (!entries.isArray()) throw new IOException("Skill catalog must contain a skills array");
            Files.createDirectories(resourceRoot);
            Path root = resourceRoot.toRealPath();
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
                materialize(root, directory, files);
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

    private void materialize(Path root, Path directory, Map<String, byte[]> files) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = Files.createTempDirectory(root, ".preparing-");
            try {
                for (var file : files.entrySet()) {
                    Path target = staging.resolve(file.getKey());
                    Files.createDirectories(target.getParent());
                    Files.write(target, file.getValue());
                }
                try {
                    Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException concurrentPreparation) {
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
