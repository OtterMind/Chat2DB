package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.service.agent.IAgentWorkspaceStorage;
import ai.chat2db.community.tools.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentWorkspaceServiceImplTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesCanonicalDirectoryAndRestoresPerSessionDefaults() throws Exception {
        MemorySettings storage = new MemorySettings();
        AiAgentWorkspaceServiceImpl service = new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory.resolve("sessions"), () -> null);
        Path selected = Files.createDirectory(temporaryDirectory.resolve("工作 space"));
        assertEquals(selected.toRealPath().toString(), service.update(selected.toString()).workingDirectory());
        assertEquals(selected.toRealPath().toString(), service.resolveWorkingDirectory("one"));
        assertEquals(selected.toRealPath().toString(),
                new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory.resolve("sessions"), () -> null).get().workingDirectory());
        service.update("");
        String restored = service.resolveWorkingDirectory("one");
        assertEquals(temporaryDirectory.resolve("sessions/one").toRealPath().toString(),
                restored);
        assertNotEquals(service.resolveWorkingDirectory("one"), service.resolveWorkingDirectory("two"));
    }

    @Test
    void rejectsInvalidDirectoriesWithoutReplacingSavedSelection() throws Exception {
        MemorySettings storage = new MemorySettings();
        AiAgentWorkspaceServiceImpl service = new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory.resolve("sessions"), () -> null);
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
        AiAgentWorkspaceServiceImpl service = new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory.resolve("sessions"), selected::get);
        assertNull(service.selectDirectory());
        Path folder = Files.createDirectory(temporaryDirectory.resolve("数据 space"));
        selected.set(folder.toString());
        assertEquals(folder.toRealPath().toString(), service.selectDirectory());
        assertEquals("", storage.directory);
    }

    @Test
    void toolsStartDisabledAndRememberOnlyExplicitChoices() {
        MemorySettings storage = new MemorySettings();
        AiAgentWorkspaceServiceImpl service = new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory.resolve("sessions"), () -> null);
        var tools = ai.chat2db.community.tools.util.agent.AgentNativeTools.currentPlatform();
        assertTrue(tools.stream().noneMatch(service::isToolEnabled));
        service.setToolEnabled("read", true);
        assertTrue(new AiAgentWorkspaceServiceImpl(storage, temporaryDirectory, () -> null).isToolEnabled("read"));
        assertFalse(service.isToolEnabled("write"));
        service.setToolEnabled("read", false);
        assertFalse(service.isToolEnabled("read"));
        assertThrows(IllegalArgumentException.class, () -> service.setToolEnabled("unknown", true));
        assertEquals("", storage.directory);
    }

    static final class MemorySettings implements IAgentWorkspaceStorage {
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
