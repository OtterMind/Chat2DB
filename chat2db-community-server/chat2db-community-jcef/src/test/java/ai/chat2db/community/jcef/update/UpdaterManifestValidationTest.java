package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterManifestValidationTest {

    @Test
    void rejectsDuplicateFileIds(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        VersionMetadata metadata = metadataWithFiles(List.of(
                file("chat2db-community.jar", "jar", 100),
                file("chat2db-community.jar", "jar", 100)
        ));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("duplicate file id"));
    }

    @Test
    void rejectsDuplicateLocalTargetNames(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo first = file("first.jar", "jar", 100);
        FileInfo second = file("second.jar", "jar", 100);
        second.localTargetName = first.localTargetName;

        IOException exception = assertThrows(IOException.class,
                () -> updater.validateRemoteMetadata(metadataWithFiles(List.of(first, second))));

        assertTrue(exception.getMessage().contains("duplicate localTargetName"));
    }

    @Test
    void rejectsDuplicateServerFileNames(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo first = file("first.jar", "jar", 100);
        FileInfo second = file("second.jar", "jar", 100);
        second.serverFileName = first.serverFileName;

        IOException exception = assertThrows(IOException.class,
                () -> updater.validateRemoteMetadata(metadataWithFiles(List.of(first, second))));

        assertTrue(exception.getMessage().contains("duplicate serverFileName"));
    }

    @Test
    void rejectsWrongAssetNameInPayloadUrl(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.url = "https://github.com/OtterMind/Chat2DB/releases/download/v5.3.2/wrong-name.jar";
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("asset name"));
    }

    @Test
    void rejectsPayloadUrlWithWrongVersion(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.url = "https://github.com/OtterMind/Chat2DB/releases/download/v5.3.1/chat2db-community.jar";
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("release path"));
    }

    @Test
    void rejectsInvalidSha256(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.sha256 = "not-hex";
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("invalid SHA-256"));
    }

    @Test
    void rejectsUppercaseSha256(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.sha256 = "A".repeat(64);

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadataWithFiles(Collections.singletonList(file))));

        assertTrue(exception.getMessage().contains("invalid SHA-256"));
    }

    @Test
    void rejectsMixedCaseSha256(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.sha256 = "a".repeat(63) + "A";

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadataWithFiles(Collections.singletonList(file))));

        assertTrue(exception.getMessage().contains("invalid SHA-256"));
    }

    @Test
    void rejectsNegativeFileSize(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", -1);
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("negative file size"));
    }

    @Test
    void rejectsUnsupportedFileType(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "exe", 100);
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("unsupported file type"));
    }

    @Test
    void rejectsLaunchCommand(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        VersionMetadata metadata = metadataWithFiles(Collections.emptyList());
        metadata.launchCommand = Collections.singletonList("evil");

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("launchCommand"));
    }

    @Test
    void rejectsHttpPayloadUrl(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.url = "http://github.com/OtterMind/Chat2DB/releases/download/v5.3.2/chat2db-community.jar";
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("HTTPS"));
    }

    @Test
    void rejectsCrossRepositoryPayloadUrl(@TempDir Path tempDirectory) throws Exception {
        Updater updater = newUpdater(tempDirectory);
        FileInfo file = file("chat2db-community.jar", "jar", 100);
        file.url = "https://github.com/OtherRepo/Chat2DB/releases/download/v5.3.2/chat2db-community.jar";
        VersionMetadata metadata = metadataWithFiles(Collections.singletonList(file));

        IOException exception = assertThrows(IOException.class, () -> updater.validateRemoteMetadata(metadata));
        assertTrue(exception.getMessage().contains("release path"));
    }

    private static Updater newUpdater(Path directory) {
        return new Updater(new FakeUpdateSource().manifest("{}"), directory,
                directory.resolve("local_version.json"), directory.resolve("downloads"), System::nanoTime);
    }

    private static VersionMetadata metadataWithFiles(List<FileInfo> files) {
        VersionMetadata metadata = new VersionMetadata();
        metadata.version = "5.3.2";
        metadata.files = new ArrayList<>(files);
        return metadata;
    }

    private static FileInfo file(String name, String type, long size) {
        FileInfo file = new FileInfo();
        file.id = name;
        file.serverFileName = name;
        file.localTargetName = name;
        file.url = "https://github.com/OtterMind/Chat2DB/releases/download/v5.3.2/" + name;
        file.sha256 = "0".repeat(64);
        file.type = type;
        file.fileSizeByte = size;
        file.deleted = false;
        return file;
    }
}
