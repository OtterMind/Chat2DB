package ai.chat2db.community.updater.v2.installation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacLaunchAgentHandoffTest {

    @TempDir
    Path temporaryDirectory;

    private final List<List<String>> commands = new ArrayList<>();

    @Test
    void plistKeepsTheHelperAliveAcrossTheApplicationExitAndItsOwnExit() {
        String plist = MacLaunchAgentHandoff.plist(
            "com.chat2db.updater.tx-1",
            List.of("/cache/helper-runtime/bin/java", "-jar", "/cache/chat2db-updater.jar", "/cache/plan.json"),
            Path.of("/cache/helper"),
            Path.of("/cache/helper/helper-stdout.log"),
            Path.of("/cache/helper/helper-stderr.log")
        );

        assertTrue(plist.contains("<string>com.chat2db.updater.tx-1</string>"));
        assertTrue(plist.contains("<string>/cache/helper-runtime/bin/java</string>"));
        assertTrue(plist.contains("<string>-jar</string>"));
        assertTrue(plist.contains("<string>/cache/plan.json</string>"));
        assertTrue(plist.contains("<key>WorkingDirectory</key>\n  <string>"
            + Path.of("/cache/helper") + "</string>"));
        assertTrue(plist.contains("<key>StandardOutPath</key>"));
        assertTrue(plist.contains("<key>RunAtLoad</key>\n  <false/>"),
            "a plist that survives a crash must not replay the plan at the next login");
        assertTrue(plist.contains("<key>AbandonProcessGroup</key>\n  <true/>"),
            "without AbandonProcessGroup launchd kills the application the helper relaunches");
        assertTrue(plist.contains("<key>LimitLoadToSessionType</key>\n  <string>Aqua</string>"));
    }

    @Test
    void escapesArgumentsThatAreNotValidXml() {
        String plist = MacLaunchAgentHandoff.plist("com.chat2db.updater.tx-2",
            List.of("/bin/echo", "a&b<c>d"), Path.of("/work"), Path.of("/out"), Path.of("/err"));
        assertTrue(plist.contains("<string>a&amp;b&lt;c&gt;d</string>"));
    }

    @Test
    void bootstrapsTheAgentForThisTransaction() throws Exception {
        MacLaunchAgentHandoff handoff = handoff(0, "501\n");

        int exitCode = handoff.bootstrap(
            "LOCAL",
            List.of("/cache/helper-runtime/bin/java", "-jar", "/cache/chat2db-updater.jar", "/cache/plan.json"),
            temporaryDirectory.resolve("work"),
            temporaryDirectory.resolve("helper-stdout.log"),
            temporaryDirectory.resolve("helper-stderr.log")
        );

        assertEquals(0, exitCode);
        Path agent = temporaryDirectory.resolve("Library/LaunchAgents/com.chat2db.updater.local.plist");
        assertTrue(Files.isRegularFile(agent));
        assertEquals(
            List.of("/bin/launchctl", "bootstrap", "gui/501", agent.toString()),
            commands.get(commands.size() - 2),
            "a label that is not registered yet must be loaded once"
        );
        assertEquals(
            List.of("/bin/launchctl", "kickstart", "gui/501/com.chat2db.updater.local"),
            commands.get(commands.size() - 1),
            "the agent is loaded without RunAtLoad and started explicitly"
        );
    }

    @Test
    void reusesTheRegistrationOfAnAlreadyLoadedAgent() throws Exception {
        MacLaunchAgentHandoff handoff = new MacLaunchAgentHandoff(
            temporaryDirectory.resolve("Library/LaunchAgents"), "501",
            command -> {
                commands.add(List.copyOf(command));
                return new MacLaunchAgentHandoff.CommandResult(0, "501\n");
            });

        int exitCode = handoff.bootstrap("PRO", List.of("/bin/true"),
            temporaryDirectory.resolve("work"), temporaryDirectory.resolve("out"), temporaryDirectory.resolve("err"));

        assertEquals(0, exitCode);
        assertTrue(commands.stream().anyMatch(command -> command.contains("list")),
            "the handoff must check whether the label is already registered");
        assertEquals(
            List.of("/bin/launchctl", "kickstart", "gui/501/com.chat2db.updater.pro"),
            commands.get(commands.size() - 1));
        assertFalse(commands.stream().anyMatch(command -> command.contains("bootstrap")),
            "re-registering the same label would report a new background item to the user");
    }

    @Test
    void doesNotStartTheAgentWhenLoadingFails() throws Exception {
        MacLaunchAgentHandoff handoff = new MacLaunchAgentHandoff(
            temporaryDirectory.resolve("Library/LaunchAgents"), "501",
            command -> {
                commands.add(List.copyOf(command));
                boolean listing = command.contains("list");
                return new MacLaunchAgentHandoff.CommandResult(listing ? 113 : 5, "501\n");
            });

        assertEquals(5, handoff.bootstrap("LOCAL", List.of("/bin/true"),
            temporaryDirectory.resolve("work"), temporaryDirectory.resolve("out"), temporaryDirectory.resolve("err")));

        assertFalse(commands.stream().anyMatch(command -> command.contains("kickstart")),
            "a job that failed to load must not be started");
    }

    @Test
    void reportsAFailedBootstrapToTheCaller() throws Exception {
        MacLaunchAgentHandoff handoff = handoff(5, "501\n");
        assertEquals(5, handoff.bootstrap("LOCAL", List.of("/bin/true"),
            temporaryDirectory.resolve("work"), temporaryDirectory.resolve("out"), temporaryDirectory.resolve("err")));
    }

    @Test
    void unloadsTheAgentOfAFailedHandoff() throws Exception {
        MacLaunchAgentHandoff handoff = handoff(0, "501\n");
        Path agent = handoff.agentFile("LOCAL");
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "plist");

        handoff.bootout("LOCAL");

        assertFalse(Files.exists(agent));
        assertEquals(List.of("/bin/launchctl", "bootout", "gui/501/com.chat2db.updater.local"),
            commands.get(commands.size() - 1));
    }

    @Test
    void oneStableLabelPerProduct() {
        assertEquals("com.chat2db.updater.pro", MacLaunchAgentHandoff.label("PRO"));
        assertEquals("com.chat2db.updater.local", MacLaunchAgentHandoff.label("local"));
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label("../../evil"));
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label("pro/1"));
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label(null));
    }

    @Test
    void readsTheCurrentUserId() throws Exception {
        assertEquals("502", MacLaunchAgentHandoff.currentUserId(
            command -> new MacLaunchAgentHandoff.CommandResult(0, "502\n")));
        assertEquals("-1", MacLaunchAgentHandoff.currentUserId(
            command -> new MacLaunchAgentHandoff.CommandResult(1, "")));
    }

    private static void ageAgent(Path agent) throws Exception {
        Files.setLastModifiedTime(agent, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private MacLaunchAgentHandoff handoff(int exitCode, String output) {
        return new MacLaunchAgentHandoff(
            temporaryDirectory.resolve("Library/LaunchAgents"), "501", runner(exitCode, output));
    }

    private MacLaunchAgentHandoff.CommandRunner runner() {
        return runner(0, "501\n");
    }

    private MacLaunchAgentHandoff.CommandRunner runner(int exitCode, String output) {
        return command -> {
            commands.add(List.copyOf(command));
            // A label that is not registered yet makes `launchctl list` fail.
            boolean listing = command.contains("list");
            return new MacLaunchAgentHandoff.CommandResult(listing ? 113 : exitCode, output);
        };
    }
}
