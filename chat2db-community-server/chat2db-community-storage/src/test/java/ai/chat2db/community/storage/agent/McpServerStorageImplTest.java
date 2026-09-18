package ai.chat2db.community.storage.agent;

import ai.chat2db.community.domain.api.enums.agent.McpToolPolicy;
import ai.chat2db.community.domain.api.enums.agent.McpTransport;
import ai.chat2db.community.domain.api.model.agent.mcp.McpServerConfig;
import ai.chat2db.community.domain.api.model.agent.mcp.McpToolDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerStorageImplTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsServersAndKeepsTheFileReadableForTheUser() throws Exception {
        Path file = directory.resolve("mcp.json");
        McpServerStorageImpl storage = new McpServerStorageImpl(file);

        assertTrue(storage.load().isEmpty());
        storage.save(List.of(new McpServerConfig("filesystem", McpTransport.STDIO, "npx",
                List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp/docs"), null,
                List.of(), List.of("API_KEY"), Map.of("API_KEY", "secret-value"), true, McpToolPolicy.ASK,
                List.of("read_file"), "hash", List.of(new McpToolDescriptor("read_file", "Read a file",
                        Map.of("type", "object"), true, false)), "2026-09-18T10:00:00")));

        String stored = Files.readString(file);
        assertTrue(stored.contains("\"servers\""));
        assertTrue(stored.contains("secret-value"), "the file stays plain text so the user can edit it");
        assertEquals(file.toString(), storage.path());

        McpServerConfig reloaded = storage.load().get(0);
        assertEquals("filesystem", reloaded.name());
        assertEquals(McpTransport.STDIO, reloaded.transport());
        assertEquals(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp/docs"), reloaded.args());
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /tmp/docs", reloaded.commandLine());
        assertEquals(List.of("read_file"), reloaded.allowedTools());
        assertEquals(1, reloaded.tools().size());
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        assertFalse(Files.list(directory).anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }

    @Test
    void reportsAnUnreadableFileInsteadOfLosingIt() throws Exception {
        Path file = directory.resolve("mcp.json");
        Files.writeString(file, "{ not json");

        McpServerStorageImpl storage = new McpServerStorageImpl(file);

        IllegalStateException error = assertThrows(IllegalStateException.class, storage::load);
        assertTrue(error.getMessage().contains("mcp.json"));
        assertTrue(Files.exists(file), "an unreadable file is never overwritten by a read");
    }
}
