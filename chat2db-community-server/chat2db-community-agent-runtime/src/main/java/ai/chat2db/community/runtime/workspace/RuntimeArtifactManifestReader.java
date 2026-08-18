package ai.chat2db.community.runtime.workspace;

import ai.chat2db.community.domain.api.model.agent.AgentRuntimeArtifactManifest;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.InputStream;
import java.util.List;

public final class RuntimeArtifactManifestReader {

    public static final String FILE_NAME = ".chat2db-artifacts.json";
    private static final long MAX_MANIFEST_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_ARTIFACTS = 5;

    private final ObjectMapper mapper = new ObjectMapper();

    public List<AgentRuntimeArtifactManifest> read(Path workspace) {
        Path file = workspace.resolve(FILE_NAME).normalize();
        if (!file.getParent().equals(workspace.normalize()) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.size() > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("Runtime artifact manifest file is unsafe or too large");
            }
            byte[] json;
            try (InputStream input = Files.newInputStream(
                    file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                json = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
            }
            if (json.length > MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("Runtime artifact manifest file is too large");
            }
            JavaType type = mapper.getTypeFactory().constructCollectionType(
                    List.class, AgentRuntimeArtifactManifest.class);
            List<AgentRuntimeArtifactManifest> manifests = mapper.readValue(json, type);
            if (manifests == null || manifests.size() > MAX_ARTIFACTS || manifests.stream().anyMatch(item -> item == null)) {
                throw new IllegalArgumentException("Runtime artifact manifest must contain at most five artifacts");
            }
            return List.copyOf(manifests);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Runtime artifact manifest JSON is invalid", exception);
        }
    }
}
