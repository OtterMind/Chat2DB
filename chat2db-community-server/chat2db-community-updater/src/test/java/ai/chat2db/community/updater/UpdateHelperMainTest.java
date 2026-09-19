package ai.chat2db.community.updater;

import ai.chat2db.community.updater.v2.enums.ReleaseStatusEnum;
import ai.chat2db.community.updater.v2.enums.UpdateArchitectureEnum;
import ai.chat2db.community.updater.v2.enums.UpdateChannelEnum;
import ai.chat2db.community.updater.v2.model.UpdateHelperPlan;
import ai.chat2db.community.updater.v2.installation.UpdateLayout;
import ai.chat2db.community.updater.v2.model.UpdateManifest;
import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePhaseEnum;
import ai.chat2db.community.updater.v2.enums.UpdatePlatformEnum;
import ai.chat2db.community.updater.v2.enums.UpdateScopeEnum;
import ai.chat2db.community.updater.v2.model.UpdateTransaction;
import ai.chat2db.community.updater.v2.audit.UpdateAuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateHelperMainTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void awaitSpawnedApplicationsExitBeforeDeletingTheWorkingDirectory() throws Exception {
        Path pidFile = temporaryDirectory.resolve("success-install/normal.pid");
        if (Files.isRegularFile(pidFile)) {
            ProcessHandle process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElse(null);
            if (process != null) {
                process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        // A relaunched application uses the install target as its working directory, and Windows
        // cannot delete a directory that a running process still uses.
        for (ProcessHandle descendant : ProcessHandle.current().descendants().toList()) {
            descendant.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void commitsHealthyFullPackageAndDiscardsRollbackCopy() throws Exception {
        Fixture fixture = fixture(false, false);

        int exitCode = UpdateHelperMain.run(fixture.planFile());

        if (exitCode != 0) {
            throw new AssertionError(Files.readString(fixture.layout().auditLogFile("tx-1")));
        }
        assertEquals("new", Files.readString(fixture.layout().installTarget().resolve("version.txt")));
        assertEquals("migrated-schema", Files.readString(fixture.storage().resolve("chat2db.db")));
        waitForFile(fixture.layout().installTarget().resolve("normal-restarted.txt"));
        assertEquals("normal",
            Files.readString(fixture.layout().installTarget().resolve("normal-restarted.txt")));
        assertFalse(Files.exists(fixture.layout().healthFile("tx-1")));
        assertFalse(Files.exists(fixture.layout().updateWorkspace().resolve("health.json")));
        assertFalse(Files.exists(fixture.layout().previousPackage()),
            "the committed transaction must release the rollback copy");
        String audit = Files.readString(fixture.layout().auditLogFile("tx-1"));
        for (UpdatePhaseEnum phase : List.of(
                UpdatePhaseEnum.READY_TO_SWITCH,
                UpdatePhaseEnum.SWITCHING,
                UpdatePhaseEnum.STARTING_CANDIDATE,
                UpdatePhaseEnum.POSTCHECKING,
                UpdatePhaseEnum.RESTARTING_NORMAL,
                UpdatePhaseEnum.COMMITTED)) {
            assertTrue(audit.contains("stage=" + phase));
        }
        assertTrue(audit.contains("stage=RESTARTING_NORMAL event=HEALTHY"));
        assertTrue(audit.contains("phase=COMMITTED"));
        assertTrue(audit.contains("outcome=SUCCESS"));
    }

    @Test
    void recordsFailureWhenCandidateCannotStart() throws Exception {
        Fixture fixture = fixture(true, false);

        int exitCode = UpdateHelperMain.run(fixture.planFile());

        assertEquals(1, exitCode);
        assertEquals("old", Files.readString(fixture.layout().installTarget().resolve("version.txt")),
            "a candidate that never becomes healthy must be rolled back");
        waitForFile(fixture.layout().installTarget().resolve("rollback-restarted.txt"));
        assertEquals("migrated-schema", Files.readString(fixture.storage().resolve("chat2db.db")));
        assertFalse(Files.exists(fixture.layout().previousPackage()),
            "the rollback consumes the backup of the previous package");
        String audit = Files.readString(fixture.layout().auditLogFile("tx-1"));
        assertTrue(audit.contains("stage=SWITCHING"));
        assertTrue(audit.contains("stage=STARTING_CANDIDATE"));
        assertTrue(audit.contains("phase=FAILED"));
        assertTrue(audit.contains("exited before reporting TRIAL_HEALTHY"));
        assertTrue(audit.contains("stage=ROLLING_BACK event=RESTORED"));
        assertTrue(audit.contains("outcome=FAILED"));
    }

    @Test
    void failsBeforeCommitWhenNormalApplicationExitsWithoutHealth() throws Exception {
        Fixture fixture = fixture(false, true);

        int exitCode = UpdateHelperMain.run(fixture.planFile());

        assertEquals(1, exitCode);
        assertEquals("old", Files.readString(fixture.layout().installTarget().resolve("version.txt")),
            "a relaunched application that never reports health must be rolled back");
        String audit = Files.readString(fixture.layout().auditLogFile("tx-1"));
        assertTrue(audit.contains("stage=RESTARTING_NORMAL"));
        assertTrue(audit.contains("phase=FAILED"));
        assertTrue(audit.contains("stage=ROLLING_BACK event=RESTORED"));
        assertFalse(audit.contains("phase=COMMITTED"));
        assertFalse(audit.contains("outcome=SUCCESS"));
    }

    @Test
    void resolvesWindowsLauncherInsideNativeInstallDirectory() throws Exception {
        Path installRoot = temporaryDirectory.resolve("windows-install");
        Path launcher = installRoot.resolve("Chat2DB Community.exe");
        Files.createDirectories(installRoot);
        Files.writeString(launcher, "launcher");
        UpdateLayout layout = new UpdateLayout(installRoot);
        UpdateHelperPlan plan = new UpdateHelperPlan(
            transaction("tx-windows"), installRoot.toString(), "COMMUNITY",
            layout.cacheRoot().toString(), layout.supportRoot().toString(),
            Long.MAX_VALUE,
            UpdatePackageTypeEnum.WINDOWS_EXE, "Chat2DB Community.exe", List.of(), List.of(), 5
        );

        assertEquals(List.of(launcher.toString()),
            UpdateHelperMain.candidateLaunchCommand(plan, layout));
        assertEquals(installRoot.resolve("app"), UpdateHelperMain.appDirectory(plan));
    }

    @Test
    void resolvesMacosMetadataInsideApplicationContents() {
        Path installRoot = temporaryDirectory.resolve("Other App.app");
        UpdateLayout layout = new UpdateLayout(installRoot, "OTHER");
        UpdateHelperPlan plan = new UpdateHelperPlan(
            transaction("tx-macos"), installRoot.toString(), "OTHER",
            layout.cacheRoot().toString(), layout.supportRoot().toString(),
            Long.MAX_VALUE,
            UpdatePackageTypeEnum.MACOS_APP_ARCHIVE,
            "Contents/MacOS/Other App", List.of(), List.of(), 5
        );

        assertEquals(installRoot.resolve("Contents/app"), UpdateHelperMain.appDirectory(plan));
    }

    private Fixture fixture(boolean failTrial, boolean failNormal) throws Exception {
        String fixtureName = failTrial ? "trial-failure" : failNormal ? "normal-failure" : "success";
        Path installRoot = temporaryDirectory.resolve(fixtureName + "-install");
        UpdateLayout layout = testLayout(installRoot, fixtureName);
        Files.createDirectories(layout.installTarget());
        Files.writeString(layout.installTarget().resolve("version.txt"), "old");
        writeVersionMetadata(layout.appDirectory(), "5.3.3", 100);
        Path stagedPackage = layout.stagingDirectory().resolve("package");
        Files.createDirectories(stagedPackage);
        Files.writeString(stagedPackage.resolve("version.txt"), "new");
        writeVersionMetadata(stagedPackage.resolve("Contents/app"), "5.3.4", 101);
        Path storage = temporaryDirectory.resolve(fixtureName + "-storage");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("chat2db.db"), "original-schema");

        UpdateTransaction transaction = UpdateTransaction.create("tx-1", "5.3.3", manifest(), 1L)
            .transition(UpdatePhaseEnum.DOWNLOADING, 2L)
            .transition(UpdatePhaseEnum.VERIFIED, 3L)
            .transition(UpdatePhaseEnum.PRECHECKING, 4L)
            .transition(UpdatePhaseEnum.PRECHECKED, 5L)
            .transition(UpdatePhaseEnum.QUIESCING, 6L);
        UpdateAuditLog.open(layout, transaction.transactionId(), "TEST").state(transaction);
        Files.createDirectories(layout.updateWorkspace());
        Files.writeString(layout.updateWorkspace().resolve("health.json"),
            "{\"transactionId\":\"stale-tx\",\"version\":\"5.3.3\","
                + "\"status\":\"HEALTHY\",\"timestampEpochMillis\":1}");

        Path javaExecutable = Path.of(
            System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"
        );
        Path testClasses = Path.of(FakeCandidateMain.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        List<String> launchCommand = List.of(
            javaExecutable.toString(),
            "-cp",
            testClasses.toString(),
            FakeCandidateMain.class.getName(),
            installRoot.toString(),
            storage.toString(),
            Boolean.toString(failTrial),
            layout.cacheRoot().toString(),
            Boolean.toString(failNormal)
        );
        UpdateHelperPlan plan = new UpdateHelperPlan(
            transaction, installRoot.toString(), "COMMUNITY",
            layout.cacheRoot().toString(), layout.supportRoot().toString(),
            Long.MAX_VALUE,
            UpdatePackageTypeEnum.MACOS_APP_ARCHIVE, "bin/chat2db", List.of(),
            launchCommand, 5
        );
        Path planFile = temporaryDirectory.resolve(fixtureName + "-plan.json");
        new ObjectMapper().writeValue(planFile.toFile(), plan);
        return new Fixture(layout, storage, planFile);
    }

    private UpdateLayout testLayout(Path installRoot, String fixtureName) {
        return new UpdateLayout(
            installRoot,
            installRoot.resolve("Contents/app"),
            temporaryDirectory.resolve(fixtureName + "-cache"),
            temporaryDirectory.resolve(fixtureName + "-support")
        );
    }

    private static void writeVersionMetadata(Path appDirectory, String version, long releaseEpoch)
            throws Exception {
        Files.createDirectories(appDirectory);
        Files.writeString(appDirectory.resolve("version.json"),
            "{\"version\":\"" + version + "\",\"releaseEpoch\":" + releaseEpoch
                + ",\"buildSha\":\"test\"}");
    }

    private static UpdateManifest manifest() {
        return new UpdateManifest(
            2, 101, ReleaseStatusEnum.ACTIVE, "COMMUNITY", UpdateChannelEnum.STABLE, "5.3.4", "5.3.401", "sha",
            UpdatePlatformEnum.MACOS, UpdateArchitectureEnum.ARM64, UpdateScopeEnum.FULL_PACKAGE,
            UpdatePackageTypeEnum.MACOS_APP_ARCHIVE, "https://example.com/package.tar.gz", 1,
            "a".repeat(64), "Contents/MacOS/Chat2DB Community", 3, 3,
            "https://example.com/notes", "key", "signature"
        );
    }

    private static UpdateTransaction transaction(String transactionId) {
        return new UpdateTransaction(
            transactionId,
            "5.3.3",
            "5.3.4",
            101L,
            "a".repeat(64),
            UpdatePhaseEnum.QUIESCING,
            1L,
            2L,
            null
        );
    }

    private static void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }

    private record Fixture(UpdateLayout layout, Path storage, Path planFile) {
    }
}
