package ai.chat2db.community.agent.impl.pi;

import ai.chat2db.community.tools.model.agent.runtime.AgentToolAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PiSessionLauncherImplTest {
    @TempDir Path directory;

    @Test
    void loadsBundledExtensionsWithoutTheDesktopThreadsContextLoader() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var desktopLoader = new java.net.URLClassLoader(new java.net.URL[0], null)) {
            thread.setContextClassLoader(desktopLoader);
            Path entry = PiSessionLauncherImpl.copyBundledExtensions(directory);
            assertEquals(directory.resolve("chat2db-tools.mjs"), entry);
            assertTrue(Files.readString(entry).contains("registerTool"));
            assertFalse(Files.readString(directory.resolve("chat2db-output.mjs")).isBlank());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void readersKeepThePreviousTicketUntilTheReplacementIsComplete() throws Exception {
        ObjectMapper json = new ObjectMapper();
        PiSessionLauncherImpl.writeToolAccess(directory, json, access("previous"));
        Path published = directory.resolve("tools.json");
        ObjectMapper interrupted = new ObjectMapper() {
            @Override public void writeValue(File file, Object value) throws IOException {
                Files.writeString(file.toPath(), "{\"ticket\":");
                assertEquals("previous", json.readTree(published.toFile()).path("ticket").asText());
                throw new IOException("Interrupted configuration write");
            }
        };
        assertThrows(IOException.class,
                () -> PiSessionLauncherImpl.writeToolAccess(directory, interrupted, access("next")));
        assertEquals("previous", json.readTree(published.toFile()).path("ticket").asText());
        PiSessionLauncherImpl.writeToolAccess(directory, json, access("next"));
        assertEquals("next", json.readTree(published.toFile()).path("ticket").asText());
        try (var files = Files.list(directory)) {
            assertEquals(List.of(published), files.toList());
        }
    }

    private AgentToolAccess access(String ticket) {
        return new AgentToolAccess("http://127.0.0.1", ticket, List.of());
    }
}
