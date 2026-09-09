package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentShellSettingsStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashSettingsServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesCanonicalDirectoryAndRestoresPerSessionDefaults() throws Exception {
        MemorySettings storage = new MemorySettings();
        BashSettingsService service = new BashSettingsService(storage, temporaryDirectory.resolve("sessions"));
        Path selected = Files.createDirectory(temporaryDirectory.resolve("工作 space"));
        assertEquals(selected.toRealPath().toString(), service.update(selected.toString()).workingDirectory());
        assertEquals(selected.toRealPath().toString(), service.resolveWorkingDirectory("one"));
        assertEquals(selected.toRealPath().toString(),
                new BashSettingsService(storage, temporaryDirectory.resolve("sessions")).get().workingDirectory());
        service.update("");
        String restored = service.resolveWorkingDirectory("one");
        assertEquals(temporaryDirectory.resolve("sessions/one").toRealPath().toString(),
                restored);
        assertNotEquals(service.resolveWorkingDirectory("one"), service.resolveWorkingDirectory("two"));
    }

    @Test
    void rejectsInvalidDirectoriesWithoutReplacingSavedSelection() throws Exception {
        MemorySettings storage = new MemorySettings();
        BashSettingsService service = new BashSettingsService(storage, temporaryDirectory.resolve("sessions"));
        service.update(temporaryDirectory.toString());
        String saved = storage.directory;
        assertEquals("agent.bash.directory.absolute",
                assertThrows(BusinessException.class, () -> service.update("relative/path")).getCode());
        assertThrows(BusinessException.class, () -> service.update(temporaryDirectory.resolve("missing").toString()));
        Path file = Files.writeString(temporaryDirectory.resolve("file.txt"), "test");
        assertThrows(BusinessException.class, () -> service.update(file.toString()));
        assertEquals(saved, storage.directory);
    }

    static final class MemorySettings implements AgentShellSettingsStorage {
        String directory = "";
        @Override public String getWorkingDirectory() { return directory; }
        @Override public void setWorkingDirectory(String value) { directory = value; }
    }
}
