package ai.chat2db.community.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalUpdaterTest {

    @Test
    void rejectsPlanWithoutActions(@TempDir Path appDirectory) {
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        plan.remoteMetadata = new ExternalUpdater.VersionMetadata();
        plan.remoteMetadata.version = "5.3.1";
        plan.remoteMetadata.files = List.of();
        plan.downloadedFiles = Map.of();

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, appDirectory));

        assertEquals("Update plan is incomplete", exception.getMessage());
    }

    @Test
    void rejectsTargetPathTraversal(@TempDir Path appDirectory) {
        ExternalUpdater.UpdatePlan plan = planFor(file("app", "../outside.jar", "jar", 0, "00"));
        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, appDirectory));
    }

    @Test
    void stagesZipAndKeepsRollbackMaterialUntilTheNextRun(@TempDir Path tempDirectory) throws Exception {
        Path appDirectory = Files.createDirectory(tempDirectory.resolve("app"));
        Path existing = Files.createDirectories(appDirectory.resolve("dist"));
        Files.writeString(existing.resolve("old.txt"), "old");
        Path unrelatedBackup = Files.writeString(appDirectory.resolve("unrelated.bak_1"), "keep");
        Path archive = tempDirectory.resolve("dist.zip");
        writeZip(archive, "dist/index.html", "new");
        ExternalUpdater.FileInfo update = file("dist", "dist", "zip", Files.size(archive), sha256(archive));

        ExternalUpdater.executeInstallation(planFor(update, Map.of("dist", archive.toString())), appDirectory, tempDirectory);

        assertEquals("new", Files.readString(appDirectory.resolve("dist/index.html")));
        assertTrue(Files.exists(appDirectory.resolve(".chat2db-update-backups")));
        assertTrue(Files.exists(unrelatedBackup));
    }

    @Test
    void rejectsZipEntriesOutsideTheTargetRoot(@TempDir Path tempDirectory) throws Exception {
        Path archive = tempDirectory.resolve("dist.zip");
        writeZip(archive, "outside/index.html", "bad");
        Path destination = tempDirectory.resolve("stage");

        assertThrows(IOException.class, () -> ExternalUpdater.extractZip(archive, destination, "dist"));
        assertFalse(Files.exists(destination.resolve("outside/index.html")));
    }

    @Test
    void rejectsDownloadedFilesOutsideTheControlledDirectory(@TempDir Path tempDirectory) throws Exception {
        Path appDirectory = Files.createDirectory(tempDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(tempDirectory.resolve("controlled"));
        Path outsideFile = Files.writeString(tempDirectory.resolve("outside.jar"), "outside");
        ExternalUpdater.FileInfo update = file("app", "app.jar", "jar", Files.size(outsideFile), sha256(outsideFile));

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(
                planFor(update, Map.of("app", outsideFile.toString())), appDirectory, controlledDirectory));
    }

    @Test
    void rejectsActionsThatDoNotMatchRemoteMetadata(@TempDir Path tempDirectory) throws Exception {
        Path appDirectory = Files.createDirectory(tempDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(tempDirectory.resolve("controlled"));
        Path downloadedFile = Files.writeString(controlledDirectory.resolve("app.jar"), "new");
        ExternalUpdater.FileInfo metadataFile = file("app", "app.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));
        ExternalUpdater.FileInfo actionFile = file("app", "other.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));
        ExternalUpdater.UpdatePlan plan = planFor(metadataFile, Map.of("app", downloadedFile.toString()));
        plan.tasks.get(0).remoteFileInfo = actionFile;

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, appDirectory, controlledDirectory));
    }

    @Test
    void rejectsDeleteActionsWithADifferentTargetThanMetadata(@TempDir Path tempDirectory) throws Exception {
        Path appDirectory = Files.createDirectory(tempDirectory.resolve("app"));
        ExternalUpdater.FileInfo metadataFile = file("obsolete", "old.jar", "jar", 0, "");
        metadataFile.deleted = true;
        ExternalUpdater.FileInfo actionFile = file("obsolete", "other.jar", "jar", 0, "");
        ExternalUpdater.VersionMetadata metadata = new ExternalUpdater.VersionMetadata();
        metadata.version = "5.3.1";
        metadata.files = List.of(metadataFile);
        ExternalUpdater.FileUpdateAction action = new ExternalUpdater.FileUpdateAction();
        action.actionType = "DELETE_OLD";
        action.localFileInfo = actionFile;
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        plan.remoteMetadata = metadata;
        plan.tasks = List.of(action);
        plan.downloadedFiles = Map.of();

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, appDirectory, tempDirectory));
    }

    @Test
    void replacesAnExistingLocalVersionFile(@TempDir Path tempDirectory) throws Exception {
        Path appDirectory = Files.createDirectory(tempDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(tempDirectory.resolve("controlled"));
        Path downloadedFile = Files.writeString(controlledDirectory.resolve("app.jar"), "new");
        Files.writeString(appDirectory.resolve("local_version.json"), "{\"version\":\"old\"}");
        ExternalUpdater.FileInfo update = file("app", "app.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));

        ExternalUpdater.executeInstallation(planFor(update, Map.of("app", downloadedFile.toString())), appDirectory, controlledDirectory);

        assertTrue(Files.readString(appDirectory.resolve("local_version.json")).contains("5.3.1"));
    }

    private static ExternalUpdater.UpdatePlan planFor(ExternalUpdater.FileInfo update) {
        return planFor(update, Map.of());
    }

    private static ExternalUpdater.UpdatePlan planFor(ExternalUpdater.FileInfo update, Map<String, String> downloads) {
        ExternalUpdater.VersionMetadata metadata = new ExternalUpdater.VersionMetadata();
        metadata.version = "5.3.1";
        metadata.files = List.of(update);
        ExternalUpdater.FileUpdateAction action = new ExternalUpdater.FileUpdateAction();
        action.actionType = "UPDATE_EXISTING";
        action.remoteFileInfo = update;
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        plan.remoteMetadata = metadata;
        plan.tasks = List.of(action);
        plan.downloadedFiles = downloads;
        return plan;
    }

    private static ExternalUpdater.FileInfo file(String id, String target, String type, long size, String checksum) {
        ExternalUpdater.FileInfo info = new ExternalUpdater.FileInfo();
        info.id = id;
        info.localTargetName = target;
        info.type = type;
        info.fileSizeByte = size;
        info.sha256 = checksum;
        return info;
    }

    private static void writeZip(Path archive, String entryName, String content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
