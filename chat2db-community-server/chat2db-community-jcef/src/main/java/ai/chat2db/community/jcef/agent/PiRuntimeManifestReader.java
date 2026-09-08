package ai.chat2db.community.jcef.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class PiRuntimeManifestReader {

    private final ObjectMapper objectMapper;

    public PiRuntimeManifestReader() {
        this(new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    PiRuntimeManifestReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PiRuntimeManifest read(Path platformDirectory) throws IOException {
        PiRuntimeManifest manifest = objectMapper.readValue(
                platformDirectory.resolve("runtime-manifest.json").toFile(), PiRuntimeManifest.class);
        requireText(manifest.version(), "version");
        requireText(manifest.operatingSystem(), "operatingSystem");
        requireText(manifest.architecture(), "architecture");
        requireText(manifest.protocolVersion(), "protocolVersion");
        requireText(manifest.source(), "source");
        if (manifest.files() == null || manifest.files().isEmpty()) {
            throw new IOException("Pi runtime manifest has no files");
        }
        for (Map.Entry<String, String> file : manifest.files().entrySet()) {
            Path relative = Path.of(requireText(file.getKey(), "file path")).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                throw new IOException("Pi runtime manifest contains an unsafe file path");
            }
            if (!requireText(file.getValue(), "file hash").matches("[0-9a-fA-F]{64}")) {
                throw new IOException("Pi runtime manifest contains an invalid SHA-256 hash");
            }
        }
        return manifest;
    }

    private String requireText(String value, String name) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Pi runtime manifest " + name + " is missing");
        }
        return value;
    }
}
