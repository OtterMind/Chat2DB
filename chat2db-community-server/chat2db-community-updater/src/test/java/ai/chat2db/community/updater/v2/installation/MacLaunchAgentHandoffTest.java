package ai.chat2db.community.updater.v2.installation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(plist.contains("<key>WorkingDirectory</key>\n  <string>/cache/helper</string>"));
        assertTrue(plist.contains("<key>StandardOutPath</key>"));
        assertTrue(plist.contains("<key>RunAtLoad</key>\n  <true/>"));
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
            "tx-abc",
            List.of("/cache/helper-runtime/bin/java", "-jar", "/cache/chat2db-updater.jar", "/cache/plan.json"),
            temporaryDirectory.resolve("work"),
            temporaryDirectory.resolve("helper-stdout.log"),
            temporaryDirectory.resolve("helper-stderr.log")
        );

        assertEquals(0, exitCode);
        Path agent = temporaryDirectory.resolve("Library/LaunchAgents/com.chat2db.updater.tx-abc.plist");
        assertTrue(Files.isRegularFile(agent));
        assertEquals(
            List.of("/bin/launchctl", "bootstrap", "gui/501", agent.toString()),
            commands.get(commands.size() - 1)
        );
    }

    @Test
    void reportsAFailedBootstrapToTheCaller() throws Exception {
        MacLaunchAgentHandoff handoff = handoff(5, "501\n");
        assertEquals(5, handoff.bootstrap("tx-fail", List.of("/bin/true"),
            temporaryDirectory.resolve("work"), temporaryDirectory.resolve("out"), temporaryDirectory.resolve("err")));
    }

    @Test
    void unloadsAgentsFromEarlierTransactionsButKeepsTheCurrentOne() throws Exception {
        Path agents = temporaryDirectory.resolve("Library/LaunchAgents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("com.chat2db.updater.tx-old.plist"), "old");
        Files.writeString(agents.resolve("com.chat2db.updater.tx-current.plist"), "current");
        Files.writeString(agents.resolve("com.google.keystone.agent.plist"), "unrelated");
        MacLaunchAgentHandoff handoff = new MacLaunchAgentHandoff(agents, "501", runner());

        List<String> removed = handoff.bootoutStale("tx-current");

        assertEquals(List.of("com.chat2db.updater.tx-old"), removed);
        assertFalse(Files.exists(agents.resolve("com.chat2db.updater.tx-old.plist")));
        assertTrue(Files.exists(agents.resolve("com.chat2db.updater.tx-current.plist")));
        assertTrue(Files.exists(agents.resolve("com.google.keystone.agent.plist")));
        assertEquals(
            List.of("/bin/launchctl", "bootout", "gui/501/com.chat2db.updater.tx-old"),
            commands.get(commands.size() - 1)
        );
    }

    @Test
    void leavesARunningAgentAloneWhileCleaningUp() throws Exception {
        Path agents = temporaryDirectory.resolve("Library/LaunchAgents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("com.chat2db.updater.tx-live.plist"), "live");
        MacLaunchAgentHandoff handoff = new MacLaunchAgentHandoff(agents, "501", command ->
            command.contains("list")
                ? new MacLaunchAgentHandoff.CommandResult(0, "{\"Label\" = \"x\"; \"PID\" = 4242; }")
                : new MacLaunchAgentHandoff.CommandResult(0, ""));

        assertTrue(handoff.bootoutStale("tx-other").isEmpty(),
            "another product may be updating from the same account right now");
        assertTrue(Files.exists(agents.resolve("com.chat2db.updater.tx-live.plist")));
    }

    @Test
    void unloadsTheAgentOfAFailedHandoff() throws Exception {
        MacLaunchAgentHandoff handoff = handoff(0, "501\n");
        Path agent = handoff.agentFile("tx-abort");
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "plist");

        handoff.bootout("tx-abort");

        assertFalse(Files.exists(agent));
        assertEquals(List.of("/bin/launchctl", "bootout", "gui/501/com.chat2db.updater.tx-abort"),
            commands.get(commands.size() - 1));
    }

    @Test
    void rejectsUnsafeTransactionIds() {
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label("../../evil"));
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label("tx/1"));
        assertThrows(IllegalArgumentException.class, () -> MacLaunchAgentHandoff.label(null));
    }

    @Test
    void readsTheCurrentUserId() throws Exception {
        assertEquals("502", MacLaunchAgentHandoff.currentUserId(
            command -> new MacLaunchAgentHandoff.CommandResult(0, "502\n")));
        assertEquals("-1", MacLaunchAgentHandoff.currentUserId(
            command -> new MacLaunchAgentHandoff.CommandResult(1, "")));
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
            return new MacLaunchAgentHandoff.CommandResult(exitCode, output);
        };
    }
}
