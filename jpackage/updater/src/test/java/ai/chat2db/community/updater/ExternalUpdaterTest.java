package ai.chat2db.community.updater;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ExternalUpdaterTest {

    @Test
    void acknowledgesOnlyAfterThePlanPassesValidation(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path planPath = controlledDirectory.resolve("plan.json");
        Path statusPath = controlledDirectory.resolve("status.txt");
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        plan.remoteMetadata = new ExternalUpdater.VersionMetadata();
        plan.remoteMetadata.version = "5.3.2";
        plan.remoteMetadata.files = List.of();
        plan.tasks = List.of();
        plan.downloadedFiles = Map.of();
        new ObjectMapper().writeValue(planPath.toFile(), plan);

        int exitCode = ExternalUpdater.run(new String[]{planPath.toString(), appDirectory.toString(),
                "chat2db-community://restart", statusPath.toString(), "operation-1", controlledDirectory.toString(),
                Long.toString(Long.MAX_VALUE)});

        assertEquals(0, exitCode);
        assertEquals("operation-1|ACCEPTED", Files.readString(statusPath));
    }

    @Test
    void restartsTheApplicationWhenInstallationFailsAfterMainProcessExit(@TempDir Path tempDirectory) throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "The executable launcher fixture is POSIX-specific");
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path planPath = controlledDirectory.resolve("plan.json");
        Path statusPath = controlledDirectory.resolve("status.txt");
        Path marker = workingDirectory.resolve("restarted.txt");
        Path launcher = appDirectory.resolve("chat2db-community.exe");
        Files.writeString(launcher, "#!/bin/sh\nprintf restarted > '" + marker + "'\n");
        assertTrue(launcher.toFile().setExecutable(true));

        ExternalUpdater.FileInfo update = file("app", "app.jar", "jar", 1, "0".repeat(64));
        ExternalUpdater.UpdatePlan plan = planFor(update,
                Map.of("app", controlledDirectory.resolve("missing.jar").toString()));
        new ObjectMapper().writeValue(planPath.toFile(), plan);
        Process exitedProcess = new ProcessBuilder("sh", "-c", "exit 0").start();
        exitedProcess.waitFor();

        int exitCode = ExternalUpdater.run(new String[]{planPath.toString(), appDirectory.toString(),
                "not a valid restart uri", statusPath.toString(), "operation-recovery", controlledDirectory.toString(),
                Long.toString(exitedProcess.pid())});

        assertEquals(1, exitCode);
        for (int attempt = 0; attempt < 40 && !Files.exists(marker); attempt++) {
            Thread.sleep(25L);
        }
        assertEquals("restarted", Files.readString(marker));
    }

    @Test
    void reportsFailureWithoutAcceptanceWhenPlanValidationFails(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path planPath = controlledDirectory.resolve("plan.json");
        Path statusPath = controlledDirectory.resolve("status.txt");
        Files.writeString(planPath, "{\"remoteMetadata\":{\"version\":\"5.3.2\",\"files\":[]},\"downloadedFiles\":{}}");

        int exitCode = ExternalUpdater.run(new String[]{planPath.toString(), appDirectory.toString(),
                "chat2db-community://restart", statusPath.toString(), "operation-2", controlledDirectory.toString(),
                Long.toString(Long.MAX_VALUE)});

        assertEquals(1, exitCode);
        assertEquals("operation-2|FAILED", Files.readString(statusPath));
    }

    @Test
    void rejectsPlanWithoutActions(@TempDir Path appDirectory) {
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        plan.remoteMetadata = new ExternalUpdater.VersionMetadata();
        plan.remoteMetadata.version = "5.3.1";
        plan.remoteMetadata.files = List.of();
        plan.downloadedFiles = Map.of();

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, real(appDirectory)));

        assertEquals("Update plan is incomplete", exception.getMessage());
    }

    @Test
    void rejectsTargetPathTraversal(@TempDir Path appDirectory) {
        ExternalUpdater.UpdatePlan plan = planFor(file("app", "../outside.jar", "jar", 0, "00"));
        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, real(appDirectory)));
    }

    @Test
    void rejectsUppercaseSha256Metadata(@TempDir Path appDirectory) {
        ExternalUpdater.UpdatePlan plan = planFor(file("app", "app.jar", "jar", 0, "A".repeat(64)));

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, real(appDirectory)));

        assertEquals("Update metadata is invalid for app", exception.getMessage());
    }

    @Test
    void stagesZipAndKeepsRollbackMaterialUntilTheNextRun(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path existing = Files.createDirectories(appDirectory.resolve("dist"));
        Files.writeString(existing.resolve("old.txt"), "old");
        Path unrelatedBackup = Files.writeString(appDirectory.resolve("unrelated.bak_1"), "keep");
        Path archive = workingDirectory.resolve("dist.zip");
        writeZip(archive, "dist/index.html", "new");
        ExternalUpdater.FileInfo update = file("dist", "dist", "zip", Files.size(archive), sha256(archive));

        ExternalUpdater.executeInstallation(planFor(update, Map.of("dist", archive.toString())), appDirectory, workingDirectory);

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
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path outsideFile = Files.writeString(workingDirectory.resolve("outside.jar"), "outside");
        ExternalUpdater.FileInfo update = file("app", "app.jar", "jar", Files.size(outsideFile), sha256(outsideFile));

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(
                planFor(update, Map.of("app", outsideFile.toString())), appDirectory, controlledDirectory));
    }

    @Test
    void rejectsActionsThatDoNotMatchRemoteMetadata(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path downloadedFile = Files.writeString(controlledDirectory.resolve("app.jar"), "new");
        ExternalUpdater.FileInfo metadataFile = file("app", "app.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));
        ExternalUpdater.FileInfo actionFile = file("app", "other.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));
        ExternalUpdater.UpdatePlan plan = planFor(metadataFile, Map.of("app", downloadedFile.toString()));
        plan.tasks.get(0).remoteFileInfo = actionFile;

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, appDirectory, controlledDirectory));
    }

    @Test
    void rejectsDeleteActionsWithADifferentTargetThanMetadata(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
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

        assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(plan, appDirectory, workingDirectory));
    }

    @Test
    void rejectsDeleteActionWithForgedRemoteFileMetadata(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        ExternalUpdater.FileInfo deletedMetadata = file("obsolete", "obsolete.jar", "jar", 0, "");
        deletedMetadata.deleted = true;
        ExternalUpdater.FileInfo localActionFile = file("obsolete", "obsolete.jar", "jar", 0, "");
        ExternalUpdater.FileInfo forgedRemoteFile = file("unrelated", "important.jar", "jar", 0, "0".repeat(64));
        ExternalUpdater.FileUpdateAction action = new ExternalUpdater.FileUpdateAction();
        action.actionType = "DELETE_OLD";
        action.localFileInfo = localActionFile;
        action.remoteFileInfo = forgedRemoteFile;
        ExternalUpdater.UpdatePlan plan = new ExternalUpdater.UpdatePlan();
        ExternalUpdater.VersionMetadata metadata = new ExternalUpdater.VersionMetadata();
        metadata.version = "5.3.1";
        metadata.files = List.of(deletedMetadata);
        plan.remoteMetadata = metadata;
        plan.tasks = List.of(action);
        plan.downloadedFiles = Map.of();

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, appDirectory, workingDirectory));

        assertTrue(exception.getMessage().contains("must not contain remote file metadata"));
    }

    @Test
    void replacesAnExistingLocalVersionFile(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        Path controlledDirectory = Files.createDirectory(workingDirectory.resolve("controlled"));
        Path downloadedFile = Files.writeString(controlledDirectory.resolve("app.jar"), "new");
        Files.writeString(appDirectory.resolve("local_version.json"), "{\"version\":\"old\"}");
        ExternalUpdater.FileInfo update = file("app", "app.jar", "jar", Files.size(downloadedFile), sha256(downloadedFile));

        ExternalUpdater.executeInstallation(planFor(update, Map.of("app", downloadedFile.toString())), appDirectory, controlledDirectory);

        assertTrue(Files.readString(appDirectory.resolve("local_version.json")).contains("5.3.1"));
    }

    @Test
    void rejectsApplicationDirectoryWithASymbolicLinkAncestor(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path targetParent = Files.createDirectory(workingDirectory.resolve("app-parent"));
        Files.createDirectory(targetParent.resolve("app"));
        Path linkedParent = workingDirectory.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, targetParent);

        IOException exception = assertThrows(IOException.class, () -> ExternalUpdater.executeInstallation(
                planFor(file("app", "app.jar", "jar", 0, "0".repeat(64))), linkedParent.resolve("app"), workingDirectory));

        assertTrue(exception.getMessage().contains("Application directory contains a symbolic link"));
    }

    @Test
    void rejectsPlanThatOmitsAManagedRemoteFile(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        ExternalUpdater.FileInfo managed = file("app", "app.jar", "jar", 0, "0".repeat(64));
        ExternalUpdater.UpdatePlan plan = planFor(managed, Map.of());
        plan.tasks = List.of();

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, appDirectory, workingDirectory));

        assertTrue(exception.getMessage().contains("exactly one action"));
    }

    @Test
    void rejectsKeepLocalActionWithoutAMatchingRemoteFile(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        ExternalUpdater.FileInfo managed = file("app", "app.jar", "jar", 0, "0".repeat(64));
        ExternalUpdater.UpdatePlan plan = planFor(managed, Map.of());
        ExternalUpdater.FileUpdateAction keepLocal = new ExternalUpdater.FileUpdateAction();
        keepLocal.actionType = "KEEP_LOCAL";
        keepLocal.localFileInfo = managed;
        plan.tasks = List.of(keepLocal);

        IOException exception = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(plan, appDirectory, workingDirectory));

        assertTrue(exception.getMessage().contains("Keep-local action has no remote file"));
    }

    @Test
    void rejectsDuplicateRemoteTargetsAndActionIds(@TempDir Path tempDirectory) throws Exception {
        Path workingDirectory = real(tempDirectory);
        Path appDirectory = Files.createDirectory(workingDirectory.resolve("app"));
        ExternalUpdater.FileInfo first = file("first", "shared.jar", "jar", 0, "0".repeat(64));
        ExternalUpdater.FileInfo second = file("second", "shared.jar", "jar", 0, "0".repeat(64));
        ExternalUpdater.UpdatePlan duplicateTargetPlan = planFor(first, Map.of());
        duplicateTargetPlan.remoteMetadata.files = List.of(first, second);
        duplicateTargetPlan.tasks = List.of();

        IOException duplicateTarget = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(duplicateTargetPlan, appDirectory, workingDirectory));
        assertTrue(duplicateTarget.getMessage().contains("duplicate local target path"));

        ExternalUpdater.UpdatePlan duplicateActionPlan = planFor(first,
                Map.of("first", workingDirectory.resolve("first.jar").toString()));
        ExternalUpdater.FileUpdateAction duplicate = new ExternalUpdater.FileUpdateAction();
        duplicate.actionType = "UPDATE_EXISTING";
        duplicate.remoteFileInfo = first;
        duplicateActionPlan.tasks = new ArrayList<>(duplicateActionPlan.tasks);
        duplicateActionPlan.tasks.add(duplicate);

        IOException duplicateAction = assertThrows(IOException.class,
                () -> ExternalUpdater.executeInstallation(duplicateActionPlan, appDirectory, workingDirectory));
        assertTrue(duplicateAction.getMessage().contains("duplicate action file id"));
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

    private static Path real(Path path) throws IOException {
        return path.toRealPath();
    }
}
