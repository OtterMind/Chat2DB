package ai.chat2db.community.updater.v2.installation;

import ai.chat2db.community.updater.v2.enums.UpdatePackageTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullPackageSwitcherTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesCurrentPackageAndLeavesNoOldCopy() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-1");
        Path stagedPackage = layout.stagedPackage(UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        switcher.switchToCandidate("tx-1", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);

        assertEquals("new", Files.readString(layout.installTarget().resolve("version.txt")));
        assertEquals("new", Files.readString(stagedPackage.resolve("version.txt")));
        assertTrue(switcher.hasBackup(UpdatePackageTypeEnum.MACOS_APP_ARCHIVE),
            "the previous package must stay available until the candidate is healthy");

        switcher.commit("tx-1");

        assertEquals("new", Files.readString(layout.installTarget().resolve("version.txt")));
        assertFalse(Files.exists(layout.previousPackage()),
            "a committed transaction must release the previous package backup");
    }

    @Test
    void keepsThePreviousPackageOutsideTheInstallTarget() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-backup");
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        Path backup = switcher.switchToCandidate("tx-backup", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);

        assertEquals(layout.previousPackage(), backup);
        assertEquals("old", Files.readString(backup.resolve("version.txt")));
        assertEquals("old-java", Files.readString(backup.resolve("old-runtime/bin/java")));
        assertFalse(Files.exists(layout.installTarget().resolve("old-runtime/bin/java")),
            "the install target must contain only the candidate after the switch");
    }

    @Test
    void rollsBackToThePreviousPackage() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-rollback");
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);
        switcher.switchToCandidate("tx-rollback", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);

        assertTrue(switcher.rollback("tx-rollback", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));

        assertEquals("old", Files.readString(layout.installTarget().resolve("version.txt")));
        assertEquals("old-java", Files.readString(layout.installTarget().resolve("old-runtime/bin/java")));
        assertFalse(Files.exists(layout.previousPackage()), "the backup is consumed by the rollback");
    }

    @Test
    void refusesToDropALeftoverBackupWhenTheInstalledPackageIsUnreadable() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-leftover");
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);
        Files.createDirectories(layout.previousPackage());
        Files.writeString(layout.previousPackage().resolve("version.txt"), "last-good");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> switcher.switchToCandidate("tx-leftover", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));

        assertTrue(failure.getMessage().contains("refusing to switch"), failure.getMessage());
        assertEquals("last-good", Files.readString(layout.previousPackage().resolve("version.txt")));
        assertTrue(Files.exists(layout.installTarget().resolve("version.txt")));
    }

    @Test
    void dropsALeftoverBackupWhenTheInstalledPackageIsStillUsable() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-stale");
        Files.createDirectories(layout.previousPackage());
        Files.writeString(layout.previousPackage().resolve("version.txt"), "stale");
        Files.createDirectories(layout.appDirectory());
        Files.writeString(layout.appDirectory().resolve("version.json"),
            "{\"version\":\"5.3.3\",\"releaseEpoch\":100,\"buildSha\":\"test\"}");
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        switcher.switchToCandidate("tx-stale", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);

        assertEquals("old", Files.readString(layout.previousPackage().resolve("version.txt")),
            "the freshly moved backup replaces the stale one");
    }

    @Test
    void rollbackWithoutBackupIsANoOp() throws Exception {
        UpdateLayout layout = prepareArchiveLayout("tx-none");
        Files.createDirectories(layout.installTarget());
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        assertFalse(switcher.rollback("tx-none", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));
        assertFalse(switcher.hasBackup(UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));
    }

    @Test
    void swapsSingleFilePackage() throws Exception {
        Path target = temporaryDirectory.resolve("Applications/Chat2DB.AppImage");
        UpdateLayout layout = testLayout(target);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old-image");
        Path staged = layout.stagedPackage(UpdatePackageTypeEnum.LINUX_APPIMAGE);
        Files.createDirectories(staged.getParent());
        Files.writeString(staged, "new-image");
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        switcher.switchToCandidate("tx-image", UpdatePackageTypeEnum.LINUX_APPIMAGE);
        assertEquals("new-image", Files.readString(target));
        assertEquals("new-image", Files.readString(target));
    }

    @Test
    void refusesMissingCandidateBeforeDeletingCurrentPackage() throws Exception {
        UpdateLayout layout = testLayout(temporaryDirectory.resolve("Applications/install"));
        Files.createDirectories(layout.installTarget());
        FullPackageSwitcher switcher = new FullPackageSwitcher(layout);

        assertThrows(IllegalStateException.class,
            () -> switcher.switchToCandidate("tx-1", UpdatePackageTypeEnum.MACOS_APP_ARCHIVE));

        assertTrue(Files.isDirectory(layout.installTarget()));
    }

    private UpdateLayout prepareArchiveLayout(String transactionId) throws Exception {
        UpdateLayout layout = testLayout(temporaryDirectory.resolve("Applications/Chat2DB Community.app"));
        Files.createDirectories(layout.installTarget().resolve("old-runtime/bin"));
        Files.writeString(layout.installTarget().resolve("old-runtime/bin/java"), "old-java");
        Files.writeString(layout.installTarget().resolve("version.txt"), "old");
        Path stagedPackage = layout.stagedPackage(UpdatePackageTypeEnum.MACOS_APP_ARCHIVE);
        Files.createDirectories(stagedPackage.resolve("new-runtime/bin"));
        Files.writeString(stagedPackage.resolve("new-runtime/bin/java"), "new-java");
        Files.writeString(stagedPackage.resolve("version.txt"), "new");
        return layout;
    }

    private UpdateLayout testLayout(Path installTarget) {
        return new UpdateLayout(
            installTarget,
            installTarget.resolve("Contents/app"),
            temporaryDirectory.resolve("Library/Caches/Chat2DB/Updater/community"),
            temporaryDirectory.resolve("Library/Application Support/Chat2DB/Updater/community")
        );
    }
}
