package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ElevatedWindowsInstallationExecutorTest {

    @Test
    void alwaysUsesTheCommunityRestartUri(@TempDir Path directory) {
        ElevatedWindowsInstallationExecutor executor = new ElevatedWindowsInstallationExecutor(directory, directory,
                new ObjectMapper(), ignored -> { }, ignored -> { });

        List<String> command = executor.processBuilder("java.exe", directory.resolve("updater.jar"),
                directory.resolve("plan.json"), directory.resolve("status.txt"), "operation-1").command();

        assertEquals("chat2db-community://restart", command.get(command.size() - 5));
        assertFalse(command.contains("chat2db-local://restart"));
        assertFalse(command.contains("chat2db-pro://restart"));
    }

    @Test
    void succeedsOnlyAfterTheHelperAcknowledgesTheSameOperation(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("updater.jar"), "updater");
        ElevatedWindowsInstallationExecutor executor = new ElevatedWindowsInstallationExecutor(directory, directory,
                new ObjectMapper(), ignored -> { }, ignored -> { }, processBuilder -> {
                    List<String> command = processBuilder.command();
                    Files.writeString(Path.of(command.get(command.size() - 4)),
                            command.get(command.size() - 3) + "|ACCEPTED");
                }, ignored -> { }, 100, 1);

        assertEquals(true, executor.install(List.of(), Map.of(), new VersionMetadata()));
    }

    @Test
    void rejectsTheLaunchWhenTheHelperRejectsThePlan(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("updater.jar"), "updater");
        ElevatedWindowsInstallationExecutor executor = new ElevatedWindowsInstallationExecutor(directory, directory,
                new ObjectMapper(), ignored -> { }, ignored -> { }, processBuilder -> {
                    List<String> command = processBuilder.command();
                    Files.writeString(Path.of(command.get(command.size() - 4)),
                            command.get(command.size() - 3) + "|FAILED");
                }, ignored -> { }, 100, 1);

        assertFalse(executor.install(List.of(), Map.of(), new VersionMetadata()));
    }

    @Test
    void rejectsNonWindowsBeforeCreatingOrLaunchingAPlan(@TempDir Path directory) {
        ElevatedWindowsInstallationExecutor executor = new ElevatedWindowsInstallationExecutor(directory, directory,
                new ObjectMapper(), ignored -> { }, ignored -> { }, processBuilder -> {
                    throw new AssertionError("helper must not launch");
                }, ignored -> { }, 100, 1, () -> false);

        assertFalse(executor.install(List.of(), Map.of(), new VersionMetadata()));
    }
}
