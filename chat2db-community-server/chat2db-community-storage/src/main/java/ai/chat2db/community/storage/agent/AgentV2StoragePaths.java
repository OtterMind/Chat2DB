package ai.chat2db.community.storage.agent;

import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AgentV2StoragePaths {

    static final String DIRECTORY_NAME = "ai-chat-history-v2";
    private static final Pattern VALID_PATH_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private final Path root;

    @Autowired
    public AgentV2StoragePaths() {
        this(resolveRoot(Path.of(ConfigUtils.getEnvBasePath())));
    }

    AgentV2StoragePaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    static Path resolveRoot(Path environmentBasePath) {
        return Objects.requireNonNull(environmentBasePath, "environmentBasePath")
                .resolve("storage")
                .resolve(DIRECTORY_NAME);
    }

    public Path root() {
        return root;
    }

    public Path schemaFile() {
        return root.resolve("schema.json");
    }

    public Path sessionsDirectory() {
        return root.resolve("sessions");
    }

    public Path sessionDirectory(String sessionId) {
        validatePathId(sessionId, "sessionId");
        return sessionsDirectory().resolve(sessionId);
    }

    public Path sessionFile(String sessionId) {
        return sessionDirectory(sessionId).resolve("session.json");
    }

    public Path resourceDirectory(String sessionId, String resourceName) {
        validateResourceName(resourceName);
        return sessionDirectory(sessionId).resolve(resourceName);
    }

    public Path resourceFile(String sessionId, String resourceName, String resourceId) {
        validatePathId(resourceId, "resourceId");
        return resourceDirectory(sessionId, resourceName).resolve(resourceId + ".json");
    }

    public Path eventFile(String sessionId, long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be greater than zero");
        }
        return resourceDirectory(sessionId, "events").resolve(String.format("%020d.json", sequence));
    }

    private void validatePathId(String value, String name) {
        if (value == null || !VALID_PATH_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    private void validateResourceName(String resourceName) {
        if (!Set.of("runs", "events", "approvals", "artifacts").contains(resourceName)) {
            throw new IllegalArgumentException("Invalid agent resource name: " + resourceName);
        }
    }
}
