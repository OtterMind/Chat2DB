package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.tools.util.ManagedTaskInputFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TaskInputCleanupTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void temporaryUploadedInputAndMarkerAreDeletedExactlyOnce() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("task-inputs"));
        String token = "cleanup-token";
        Path sourceFile = managedFile(root, "task-import-uploaded.tmp", token);
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(sourceFile.toString())
                .temporarySourceFile(true)
                .temporarySourceToken(token)
                .build();
        TaskInputCleanup cleanup = new TaskInputCleanup(root);
        AtomicInteger completed = new AtomicInteger();
        RunningTask runningTask = new RunningTask(42L, cleanup.forSpec(spec, completed::incrementAndGet));

        runningTask.cleanupInput();
        runningTask.cleanupInput();

        assertFalse(Files.exists(sourceFile));
        assertFalse(Files.exists(ManagedTaskInputFiles.markerPath(sourceFile, token)));
        assertEquals(1, completed.get());
    }

    @Test
    void desktopPathIsNeverRegisteredForCleanup() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("task-inputs"));
        Path sourceFile = Files.writeString(temporaryDirectory.resolve("desktop.sql"), "select 1;");
        ImportTaskSpec spec = ImportTaskSpec.builder()
                .sourceFile(sourceFile.toString())
                .temporarySourceFile(false)
                .build();

        assertNull(new TaskInputCleanup(root).forSpec(spec, () -> {}));
        assertTrue(Files.exists(sourceFile));
    }

    @Test
    void corruptedEventCannotDeleteOutsidePathOrManagedDirectory() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("task-inputs"));
        Path outside = managedFile(temporaryDirectory, "task-import-outside.tmp", "outside-token");
        Path directory = Files.createDirectory(root.resolve("task-import-directory.tmp"));
        Files.createFile(ManagedTaskInputFiles.markerPath(directory, "directory-token"));
        TaskInputCleanup cleanup = new TaskInputCleanup(root);

        assertFalse(cleanup.delete(new TaskInputCleanup.InputReference(outside.toString(), "outside-token")));
        assertFalse(cleanup.delete(new TaskInputCleanup.InputReference(directory.toString(), "directory-token")));
        assertTrue(Files.exists(outside));
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    void mismatchedCleanupIdentityCannotDeleteAnotherManagedInput() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("task-inputs"));
        Path source = managedFile(root, "task-import-other.tmp", "other-token");

        assertFalse(new TaskInputCleanup(root).delete(
                new TaskInputCleanup.InputReference(source.toString(), "forged-token")));

        assertTrue(Files.exists(source));
        assertTrue(Files.exists(ManagedTaskInputFiles.markerPath(source, "other-token")));
    }

    @Test
    void symlinkInsideManagedRootIsNeverFollowedOrDeletedAsInput() throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve("task-inputs"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.csv"), "outside");
        Path link = root.resolve("task-import-link.tmp");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        Files.createFile(ManagedTaskInputFiles.markerPath(link, "link-token"));

        assertFalse(new TaskInputCleanup(root).delete(
                new TaskInputCleanup.InputReference(link.toString(), "link-token")));
        assertTrue(Files.exists(outside));
        assertTrue(Files.isSymbolicLink(link));
    }

    private Path managedFile(Path directory, String name, String token) throws IOException {
        Path source = Files.writeString(directory.resolve(name), "data");
        Files.createFile(ManagedTaskInputFiles.markerPath(source, token));
        return source;
    }
}
