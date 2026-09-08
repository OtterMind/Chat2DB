package ai.chat2db.community.storage;

import ai.chat2db.community.tools.exception.storage.StorageException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StorageFileUtilsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesAndReplacesFilesAtomically() throws IOException {
        StorageFileUtils storageFileUtils = new StorageFileUtils();
        Path target = temporaryDirectory.resolve("storage/value.json");

        storageFileUtils.writeAtomically(target, "first");
        storageFileUtils.writeAtomically(target, "second");

        assertEquals("second", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void failedReplacementKeepsTheExistingFile() throws IOException {
        Path target = temporaryDirectory.resolve("storage/value.json");
        StorageFileUtils healthy = new StorageFileUtils();
        healthy.writeAtomically(target, "original");
        StorageFileUtils failing = new StorageFileUtils() {
            @Override
            protected void replaceStorageFile(Path temporary, Path destination) throws IOException {
                throw new IOException("simulated replacement failure");
            }
        };

        assertThrows(StorageException.class, () -> failing.writeAtomically(target, "replacement"));

        assertEquals("original", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsPathsOutsideTheRootAndSymbolicLinks() throws IOException {
        StorageFileUtils storageFileUtils = new StorageFileUtils();
        Path root = Files.createDirectory(temporaryDirectory.resolve("root"));
        Path inside = Files.createDirectory(root.resolve("inside"));

        storageFileUtils.verifyInsideRoot(root, inside);
        assertThrows(StorageException.class,
                () -> storageFileUtils.verifyInsideRoot(root, temporaryDirectory.resolve("outside")));

        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, temporaryDirectory);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
            return;
        }
        assertThrows(StorageException.class, () -> storageFileUtils.verifyInsideRoot(root, link));
    }
}
