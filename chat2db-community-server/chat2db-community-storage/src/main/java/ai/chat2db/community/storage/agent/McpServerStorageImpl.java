package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.service.agent.IMcpServerStorage;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Stores MCP servers in one plain-text file inside the agent data directory, next to the skill
 * directory it belongs to. The file is meant to be readable and editable by hand, so it holds no
 * generated identifiers and is written with owner-only permissions where the platform supports them.
 */
@Component
public class McpServerStorageImpl implements IMcpServerStorage {

    private static final String FILE_NAME = "servers.json";
    private static final int SCHEMA_VERSION = 1;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public McpServerStorageImpl() {
        this(Paths.get(ConfigUtils.getEnvBasePath(), "storage", "agent-v2", "mcp", FILE_NAME));
    }

    McpServerStorageImpl(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    @Override
    public String path() {
        return file.toString();
    }

    @Override
    public synchronized List<McpServerConfig> load() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            Map<String, Object> document = json.readValue(Files.readString(file),
                    new TypeReference<Map<String, Object>>() { });
            Object servers = document.get("servers");
            if (servers == null) return List.of();
            return json.convertValue(servers, new TypeReference<List<McpServerConfig>>() { });
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("MCP configuration is not readable: " + file, error);
        }
    }

    @Override
    public synchronized void save(List<McpServerConfig> servers) {
        Path directory = file.getParent();
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "mcp-", ".json.tmp");
            try {
                Files.writeString(temporary, json.writeValueAsString(
                        Map.of("version", SCHEMA_VERSION, "servers", servers)));
                restrictToOwner(temporary);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictToOwner(file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to write MCP configuration: " + file, error);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows and other non-POSIX platforms keep their default permissions.
        }
    }
}
