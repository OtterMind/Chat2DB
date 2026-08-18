package ai.chat2db.community.runtime.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskWorkspaceManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAttemptIsolatedWorkspaceAndCleansOnlyThatWorkspace() throws Exception {
        Path root = temporaryDirectory.resolve("runtime").toAbsolutePath();
        TaskWorkspaceManager manager = new TaskWorkspaceManager(root);
        Path workspace = manager.create("run/unsafe", 2);
        Files.writeString(workspace.resolve("result.md"), "result");
        Path sibling = manager.create("another-run", 1);

        manager.cleanup(workspace);

        assertFalse(Files.exists(workspace));
        assertTrue(Files.isDirectory(sibling));
        assertThrows(SecurityException.class, () -> manager.cleanup(root));
    }

    @Test
    void rejectsRelativeRootAndWorkspaceReuse() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskWorkspaceManager(Path.of("relative")));
        TaskWorkspaceManager manager = new TaskWorkspaceManager(
                temporaryDirectory.resolve("runtime").toAbsolutePath());
        manager.create("run-1", 1);
        assertThrows(IllegalStateException.class, () -> manager.create("run-1", 1));
    }
}
