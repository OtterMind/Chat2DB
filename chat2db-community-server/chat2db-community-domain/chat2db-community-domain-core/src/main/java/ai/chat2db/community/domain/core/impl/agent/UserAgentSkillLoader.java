package ai.chat2db.community.domain.core.impl.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads one standard skill without executing it or following filesystem links. */
final class UserAgentSkillLoader {
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SKILL_BYTES = 32L * 1024 * 1024;
    private static final int MAX_FILES = 512;
    private static final Pattern FRONTMATTER = Pattern.compile("\\A(?:\\uFEFF)?---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|$)", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("\\[[^]\\r\\n]*]\\(([^)\\r\\n]+)\\)");

    record Source(String name, Map<String, byte[]> files, Set<String> executables) { }

    Source read(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory)) {
            throw new IOException("Skill source must be a directory without symbolic links");
        }
        Map<String, byte[]> files = new TreeMap<>();
        Map<Path, BasicFileAttributes> observed = new TreeMap<>();
        Set<String> portableNames = new HashSet<>();
        Set<String> executables = new HashSet<>();
        long total = 0;
        try (var entries = Files.walk(directory)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (observed.size() >= 1024) throw new IOException("Skill directory contains too many entries");
                var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                observed.put(file, attributes);
                if (attributes.isSymbolicLink() || !file.toRealPath().equals(file)) {
                    throw new IOException("Skill sources cannot contain symbolic links");
                }
                if (file.equals(directory)) continue;
                String relative = directory.relativize(file).toString().replace('\\', '/');
                if (!portableNames.add(java.text.Normalizer.normalize(relative, java.text.Normalizer.Form.NFC)
                        .toLowerCase(java.util.Locale.ROOT))) throw new IOException("Conflicting skill file names");
                for (Path part : directory.relativize(file)) {
                    String name = part.toString();
                    if (name.matches(".*[<>:\"|?*\\\\].*") || name.endsWith(".") || name.endsWith(" ")
                            || name.codePoints().anyMatch(c -> c < 32)
                            || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                        throw new IOException("Skill file name is not portable: " + relative);
                    }
                }
                if (attributes.isDirectory()) continue;
                if (!attributes.isRegularFile() || attributes.size() > MAX_FILE_BYTES || files.size() >= MAX_FILES) {
                    throw new IOException("Skill file type, size or count exceeds the supported limit");
                }
                byte[] content;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    content = input.readNBytes((int) MAX_FILE_BYTES + 1);
                }
                total += content.length;
                if (content.length > MAX_FILE_BYTES || total > MAX_SKILL_BYTES) throw new IOException("Skill size exceeds 32 MiB");
                files.put(relative, content);
                if (Files.getFileStore(file).supportsFileAttributeView("posix") && Files.isExecutable(file)) executables.add(relative);
            }
        }
        for (var entry : observed.entrySet()) {
            var current = Files.readAttributes(entry.getKey(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var previous = entry.getValue();
            if (!java.util.Objects.equals(current.fileKey(), previous.fileKey())
                    || current.size() != previous.size() || !current.lastModifiedTime().equals(previous.lastModifiedTime())
                    || current.isSymbolicLink()) throw new IOException("Skill source changed while it was being read; retry after editing");
        }
        byte[] entry = files.get("SKILL.md");
        if (entry == null) throw new IOException("Skill is missing SKILL.md");
        String text = utf8(entry);
        var frontmatter = FRONTMATTER.matcher(text);
        if (!frontmatter.find()) throw new IOException("SKILL.md must start with YAML frontmatter");
        Map<?, ?> metadata;
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(10);
            options.setCodePointLimit(64 * 1024);
            Object parsed = new Yaml(new SafeConstructor(options)).load(frontmatter.group(1));
            if (!(parsed instanceof Map<?, ?> values)) throw new IOException("Skill metadata must be a mapping");
            metadata = values;
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IOException("Invalid skill YAML metadata");
        }
        Object name = metadata.get("name");
        if (!(name instanceof String skillName) || skillName.length() > 64
                || !skillName.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IOException("Invalid skill name");
        if (!(metadata.get("description") instanceof String description) || description.isBlank() || description.length() > 1024) {
            throw new IOException("Skill description must contain 1 to 1024 characters");
        }
        if (metadata.containsKey("disable-model-invocation") && !(metadata.get("disable-model-invocation") instanceof Boolean)) {
            throw new IOException("disable-model-invocation must be a boolean");
        }
        for (var file : files.entrySet()) {
            if (!file.getKey().endsWith(".md")) continue;
            var links = LINK.matcher(utf8(file.getValue()));
            while (links.find()) {
                String target = links.group(1).strip();
                if (target.startsWith("<") && target.endsWith(">")) target = target.substring(1, target.length() - 1);
                if (target.isEmpty() || target.startsWith("#") || target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
                // Check unambiguous local links; command examples and generated destinations are not declarations.
                if (target.contains("\"") || target.contains("${") || target.contains("<")) continue;
                target = target.split("#", 2)[0];
                target = java.net.URLDecoder.decode(target.replace("+", "%2B"), StandardCharsets.UTF_8);
                Path reference = Path.of(file.getKey()).resolveSibling(target).normalize();
                if (reference.isAbsolute() || reference.startsWith("..")) throw new IOException("Skill reference escapes its directory");
                if (!files.containsKey(reference.toString().replace('\\', '/'))
                        && !Files.isDirectory(directory.resolve(reference), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Missing skill reference: " + reference);
                }
            }
        }
        return new Source(skillName, files, Set.copyOf(executables));
    }

    private String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
