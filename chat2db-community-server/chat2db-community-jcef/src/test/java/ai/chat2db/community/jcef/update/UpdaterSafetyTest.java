package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterSafetyTest {

    @Test
    void rejectsApplicationPathTraversal() {
        assertThrows(IOException.class, () -> Updater.getInstance().resolveAppRelativePath("../outside"));
    }

    @Test
    void rejectsApplicationPathsContainingSymbolicLinks(@TempDir Path tempDirectory) throws Exception {
        Updater updater = Updater.getInstance();
        Field appDirectoryField = Updater.class.getDeclaredField("APP_DIR");
        appDirectoryField.setAccessible(true);
        Path originalAppDirectory = (Path) appDirectoryField.get(updater);
        Path outsideDirectory = Files.createDirectory(tempDirectory.resolve("outside"));
        Files.createSymbolicLink(tempDirectory.resolve("dist"), outsideDirectory);
        try {
            appDirectoryField.set(updater, tempDirectory);
            assertThrows(IOException.class, () -> updater.resolveAppRelativePath("dist/index.html"));
        } finally {
            appDirectoryField.set(updater, originalAppDirectory);
        }
    }

    @Test
    void rejectsMetadataWithoutManagedFiles() {
        VersionMetadata metadata = new VersionMetadata();
        metadata.version = "5.3.1";
        metadata.files = Collections.emptyList();

        assertThrows(IOException.class, () -> Updater.getInstance().validateRemoteMetadata(metadata));
    }

    @Test
    void rejectsDeletedMetadataWithoutItsOriginalTargetPath() {
        VersionMetadata metadata = new VersionMetadata();
        metadata.version = "5.3.1";
        FileInfo deletedFile = new FileInfo();
        deletedFile.id = "obsolete";
        deletedFile.deleted = true;
        metadata.files = Collections.singletonList(deletedFile);

        assertThrows(IOException.class, () -> Updater.getInstance().validateRemoteMetadata(metadata));
    }

    @Test
    void extractsOnlyTheExpectedZipRoot(@TempDir Path tempDirectory) throws Exception {
        Path archive = tempDirectory.resolve("dist.zip");
        writeZip(archive, "dist/index.html", "updated");
        Path destination = tempDirectory.resolve("stage");

        Updater.extractZip(archive, destination, "dist");

        assertEquals("updated", Files.readString(destination.resolve("dist/index.html")));
    }

    @Test
    void rejectsZipWithUnexpectedTopLevelEntry(@TempDir Path tempDirectory) throws Exception {
        Path archive = tempDirectory.resolve("dist.zip");
        writeZip(archive, "unexpected/file.txt", "not allowed");
        Path destination = tempDirectory.resolve("stage");

        assertThrows(IOException.class, () -> Updater.extractZip(archive, destination, "dist"));
        assertFalse(Files.exists(destination.resolve("unexpected/file.txt")));
    }

    private static void writeZip(Path archive, String entryName, String content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertTrue(Files.exists(archive));
    }
}
