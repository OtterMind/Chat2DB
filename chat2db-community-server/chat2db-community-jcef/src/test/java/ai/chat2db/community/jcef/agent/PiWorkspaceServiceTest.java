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
    void nativeFolderSelectionAndCancellationDoNotSaveTheDirectory() throws Exception {
        MemorySettings storage = new MemorySettings();
        var selected = new java.util.concurrent.atomic.AtomicReference<String>();
        PiWorkspaceService service = new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions"), selected::get);
        assertNull(service.selectDirectory());
        Path folder = Files.createDirectory(temporaryDirectory.resolve("数据 space"));
        selected.set(folder.toString());
        assertEquals(folder.toRealPath().toString(), service.selectDirectory());
        assertEquals("", storage.directory);
    }

    @Test
    void toolsStartDisabledAndRememberOnlyExplicitChoices() {
        MemorySettings storage = new MemorySettings();
        PiWorkspaceService service = new PiWorkspaceService(storage, temporaryDirectory.resolve("sessions"));
        var tools = ai.chat2db.community.domain.api.model.agent.AgentNativeTools.currentPlatform();
        assertTrue(tools.stream().noneMatch(service::isToolEnabled));
        service.setToolEnabled("read", true);
        assertTrue(new PiWorkspaceService(storage, temporaryDirectory).isToolEnabled("read"));
        assertFalse(service.isToolEnabled("write"));
        service.setToolEnabled("read", false);
        assertFalse(service.isToolEnabled("read"));
        assertThrows(IllegalArgumentException.class, () -> service.setToolEnabled("unknown", true));
        assertEquals("", storage.directory);
    }

    static final class MemorySettings implements AgentWorkspaceStorage {
        String directory = "";
        java.util.Set<String> enabledTools = new java.util.HashSet<>();
        @Override public String getWorkingDirectory() { return directory; }
        @Override public void setWorkingDirectory(String value) { directory = value; }
        @Override public boolean isToolEnabled(String toolName) { return enabledTools.contains(toolName); }
        @Override public void setToolEnabled(String toolName, boolean enabled) {
            if (enabled) enabledTools.add(toolName); else enabledTools.remove(toolName);
        }
    }
}
