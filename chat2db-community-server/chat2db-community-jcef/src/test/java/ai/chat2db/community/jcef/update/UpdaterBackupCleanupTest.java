package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterBackupCleanupTest {

    @Test
    void clearsOnlyCompletedUpdaterBackupSessions(@TempDir Path appDirectory) throws Exception {
        Path backupSession = Files.createDirectories(appDirectory.resolve(".chat2db-update-backups/session-1/lib"));
        Files.writeString(backupSession.resolve("dependency.jar"), "old dependency");
        Files.writeString(backupSession.getParent().resolve(".owner-pid"), "0");
        Path unrelatedBackup = Files.writeString(appDirectory.resolve("app.jar.bak_1"), "keep");

        Updater.getInstance().clearOldBackups(appDirectory);

        assertFalse(Files.exists(backupSession));
        assertFalse(Files.exists(appDirectory.resolve(".chat2db-update-backups")));
        assertTrue(Files.exists(unrelatedBackup));
    }

    @Test
    void preservesBackupUntilTheInstallingProcessHasRestarted(@TempDir Path appDirectory) throws Exception {
        Path backupSession = Files.createDirectories(appDirectory.resolve(".chat2db-update-backups/session-1"));
        Files.writeString(backupSession.resolve(".owner-pid"), Long.toString(ProcessHandle.current().pid()));

        Updater.getInstance().clearOldBackups(appDirectory);

        assertTrue(Files.exists(backupSession));
    }
}
