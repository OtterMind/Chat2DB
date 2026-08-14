package ai.chat2db.community.jcef.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ElevatedWindowsInstallationExecutorTest {

    @Test
    void alwaysUsesTheCommunityRestartUri(@TempDir Path directory) {
        ElevatedWindowsInstallationExecutor executor = new ElevatedWindowsInstallationExecutor(directory,
                new ObjectMapper(), ignored -> { }, ignored -> { });

        List<String> command = executor.processBuilder("java.exe", directory.resolve("updater.jar"),
                directory.resolve("plan.json")).command();

        assertEquals("chat2db-community://restart", command.get(command.size() - 1));
        assertFalse(command.contains("chat2db-local://restart"));
        assertFalse(command.contains("chat2db-pro://restart"));
    }
}
