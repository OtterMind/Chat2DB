package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentWorkspaceStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PiWorkspaceServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesCanonicalDirectoryAndRestoresPerSessionDefaults() throws Exception {
        MemorySettings storage = new MemorySettings();
        PiWorkspaceService service = new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions"));
        Path selected = Files.createDirectory(temporaryDirectory.resolve("工作 space"));
        assertEquals(selected.toRealPath().toString(), service.update(selected.toString()).workingDirectory());
        assertEquals(selected.toRealPath().toString(), service.resolveWorkingDirectory("one"));
        assertEquals(selected.toRealPath().toString(),
                new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions")).get().workingDirectory());
        service.update("");
        String restored = service.resolveWorkingDirectory("one");
        assertEquals(temporaryDirectory.resolve("sessions/one").toRealPath().toString(),
                restored);
        assertNotEquals(service.resolveWorkingDirectory("one"), service.resolveWorkingDirectory("two"));
    }

    @Test
    void rejectsInvalidDirectoriesWithoutReplacingSavedSelection() throws Exception {
        MemorySettings storage = new MemorySettings();
        PiWorkspaceService service = new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions"));
        service.update(temporaryDirectory.toString());
        String saved = storage.directory;
        assertEquals("agent.bash.directory.absolute",
                assertThrows(BusinessException.class, () -> service.update("relative/path")).getCode());
        assertThrows(BusinessException.class, () -> service.update(temporaryDirectory.resolve("missing").toString()));
        Path file = Files.writeString(temporaryDirectory.resolve("file.txt"), "test");
        assertThrows(BusinessException.class, () -> service.update(file.toString()));
        assertEquals(saved, storage.directory);
    }

    @Test
    void directoryBrowserReturnsOnlyDirectoriesAndDoesNotSaveSelection() throws Exception {
        MemorySettings storage = new MemorySettings();
        PiWorkspaceService service = new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions"));
        Path folder = Files.createDirectory(temporaryDirectory.resolve("数据 space"));
        Files.writeString(temporaryDirectory.resolve("file.csv"), "id\n1");
        var listing = service.listDirectories(temporaryDirectory.toString());
        assertEquals(temporaryDirectory.toRealPath().toString(), listing.path());
        assertEquals(1, listing.directories().size());
        assertEquals(folder.toRealPath().toString(), listing.directories().get(0).path());
        assertEquals("", storage.directory);
    }

    static final class MemorySettings implements AgentWorkspaceStorage {
        String directory = "";
        @Override public String getWorkingDirectory() { return directory; }
        @Override public void setWorkingDirectory(String value) { directory = value; }
    }
}
