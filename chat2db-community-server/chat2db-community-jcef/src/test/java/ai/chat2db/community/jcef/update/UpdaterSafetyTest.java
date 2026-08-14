package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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
    void rejectsApplicationAndTemporaryPathsWithSymbolicLinkRootsOrAncestors(@TempDir Path tempDirectory) throws Exception {
        Path appRootTarget = Files.createDirectory(tempDirectory.resolve("app-root-target"));
        Path appRootLink = tempDirectory.resolve("app-root-link");
        Files.createSymbolicLink(appRootLink, appRootTarget);
        Updater appRootUpdater = newUpdater(appRootTarget, tempDirectory.resolve("downloads"));
        setPathField(appRootUpdater, "APP_DIR", appRootLink);
        assertThrows(IOException.class, () -> appRootUpdater.resolveAppRelativePath("app.jar"));

        Path appParentTarget = Files.createDirectory(tempDirectory.resolve("app-parent-target"));
        Files.createDirectory(appParentTarget.resolve("app"));
        Path appParentLink = tempDirectory.resolve("app-parent-link");
        Files.createSymbolicLink(appParentLink, appParentTarget);
        Updater appParentUpdater = newUpdater(appParentTarget.resolve("app"), tempDirectory.resolve("downloads"));
        setPathField(appParentUpdater, "APP_DIR", appParentLink.resolve("app"));
        assertThrows(IOException.class, () -> appParentUpdater.resolveAppRelativePath("app.jar"));

        Path temporaryRootTarget = Files.createDirectory(tempDirectory.resolve("temporary-root-target"));
        Path temporaryRootLink = tempDirectory.resolve("temporary-root-link");
        Files.createSymbolicLink(temporaryRootLink, temporaryRootTarget);
        Updater temporaryRootUpdater = newUpdater(tempDirectory.resolve("app"), temporaryRootTarget);
        setPathField(temporaryRootUpdater, "TMP_DIR", temporaryRootLink);
        assertThrows(IOException.class, () -> temporaryRootUpdater.resolveTemporaryFile("update.jar"));

        Path temporaryParentTarget = Files.createDirectory(tempDirectory.resolve("temporary-parent-target"));
        Files.createDirectory(temporaryParentTarget.resolve("downloads"));
        Path temporaryParentLink = tempDirectory.resolve("temporary-parent-link");
        Files.createSymbolicLink(temporaryParentLink, temporaryParentTarget);
        Updater temporaryParentUpdater = newUpdater(tempDirectory.resolve("app"), temporaryParentTarget.resolve("downloads"));
        setPathField(temporaryParentUpdater, "TMP_DIR", temporaryParentLink.resolve("downloads"));
        assertThrows(IOException.class, () -> temporaryParentUpdater.resolveTemporaryFile("update.jar"));
    }

    @Test
    void rejectsDeletedMetadataWithATargetDifferentFromTheLocalMetadata(@TempDir Path temporaryDirectory) {
        VersionMetadata localMetadata = new VersionMetadata();
        FileInfo localFile = new FileInfo();
        localFile.id = "obsolete";
        localFile.localTargetName = "old.jar";
        localMetadata.files = List.of(localFile);
        VersionMetadata remoteMetadata = new VersionMetadata();
        FileInfo deletedFile = new FileInfo();
        deletedFile.id = "obsolete";
        deletedFile.localTargetName = "new.jar";
        deletedFile.deleted = true;
        remoteMetadata.files = List.of(deletedFile);

        IOException exception = assertThrows(IOException.class, () -> newUpdater(temporaryDirectory, temporaryDirectory.resolve("downloads"))
                .determineUpdateActions(localMetadata, remoteMetadata));

        assertTrue(exception.getMessage().contains("does not match local metadata"));
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

    private static Updater newUpdater(Path appDirectory, Path temporaryDirectory) {
        return new Updater(new FakeUpdateSource().manifest("{}"), appDirectory,
                appDirectory.resolve("local_version.json"), temporaryDirectory, System::nanoTime);
    }

    private static void setPathField(Updater updater, String fieldName, Path value) throws Exception {
        Field field = Updater.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(updater, value);
    }
}
