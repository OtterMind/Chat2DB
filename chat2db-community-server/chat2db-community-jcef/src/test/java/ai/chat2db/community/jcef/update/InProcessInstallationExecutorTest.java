package ai.chat2db.community.jcef.update;

import ai.chat2db.community.jcef.enums.update.UpdateActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessInstallationExecutorTest {

    @Test
    void restoresTheOriginalFileWhenMetadataCommitFails(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("chat2db-community.jar");
        Path downloaded = directory.resolve("downloaded.jar");
        Files.writeString(target, "old");
        Files.writeString(downloaded, "new");

        FileInfo remote = new FileInfo();
        remote.id = "app";
        remote.localTargetName = target.getFileName().toString();
        remote.type = "jar";
        InProcessInstallationExecutor executor = new InProcessInstallationExecutor(directory,
                relative -> directory.resolve(relative).normalize(),
                ignored -> { throw new IOException("metadata write failed"); },
                ignored -> { }, ignored -> { });

        boolean installed = executor.install(List.of(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remote,
                null, "test")), Map.of("app", downloaded), new VersionMetadata());

        assertFalse(installed);
        assertEquals("old", Files.readString(target));
    }

    @Test
    void preservesBackupSessionWhenRollbackCannotRestoreOriginalFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("chat2db-community.jar");
        Path downloaded = directory.resolve("downloaded.jar");
        Files.writeString(target, "old");
        Files.writeString(downloaded, "new");
        FileInfo remote = new FileInfo();
        remote.id = "app";
        remote.localTargetName = target.getFileName().toString();
        remote.type = "jar";
        List<String> messages = new ArrayList<>();
        InProcessInstallationExecutor executor = new InProcessInstallationExecutor(directory,
                relative -> directory.resolve(relative).normalize(),
                ignored -> { throw new IOException("metadata write failed"); }, messages::add, ignored -> { },
                (backup, restoreTarget) -> { throw new IOException("simulated restore failure"); });

        boolean installed = executor.install(List.of(new FileUpdateAction(UpdateActionType.UPDATE_EXISTING, remote,
                null, "test")), Map.of("app", downloaded), new VersionMetadata());

        assertFalse(installed);
        Path backupRoot = directory.resolve(".chat2db-update-backups");
        try (var sessions = Files.list(backupRoot)) {
            Path session = sessions.findFirst().orElseThrow();
            assertEquals("old", Files.readString(session.resolve(target.getFileName())));
        }
        assertTrue(messages.stream().anyMatch(message -> message.contains("preserving update backup session")));
    }
}
