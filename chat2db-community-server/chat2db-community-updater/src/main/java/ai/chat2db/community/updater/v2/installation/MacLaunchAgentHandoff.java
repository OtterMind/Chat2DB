package ai.chat2db.community.updater.v2.installation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the desktop update helper as a per-transaction macOS LaunchAgent.
 *
 * <p>A helper spawned as a plain child process of the application does not
 * survive the handoff: the application exits about 150 ms after spawning it,
 * while the helper needs seconds to start its JVM, so the helper is reclaimed
 * together with the application before it can switch anything. Running the
 * helper under launchd removes that dependency. {@code AbandonProcessGroup}
 * additionally keeps the application the helper relaunches alive after the
 * helper itself exits.</p>
 *
 * <p>{@code launchctl submit} is deliberately not used: launchd kills the
 * remaining processes of a submitted job's process group when its main process
 * exits, which would kill the relaunched application.</p>
 */
public final class MacLaunchAgentHandoff {

    public static final String LABEL_PREFIX = "com.chat2db.updater.";
    public static final String AGENT_SUFFIX = ".plist";

    private final Path launchAgentsDirectory;
    private final String userId;
    private final CommandRunner runner;

    public MacLaunchAgentHandoff(Path launchAgentsDirectory, String userId, CommandRunner runner) {
        this.launchAgentsDirectory = launchAgentsDirectory;
        this.userId = userId;
        this.runner = runner;
    }

    public static MacLaunchAgentHandoff forCurrentUser(Path homeDirectory, CommandRunner runner)
            throws Exception {
        return new MacLaunchAgentHandoff(
            homeDirectory.resolve("Library").resolve("LaunchAgents"),
            currentUserId(runner),
            runner
        );
    }

    public static CommandRunner processRunner() {
        return command -> {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output);
        };
    }

    public static String currentUserId(CommandRunner runner) throws Exception {
        CommandResult result = runner.run(List.of("/usr/bin/id", "-u"));
        String userId = result.output() == null ? "" : result.output().trim();
        return userId.isEmpty() ? "-1" : userId;
    }

    public static String label(String transactionId) {
        if (transactionId == null || !transactionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Update transaction id contains unsafe label characters");
        }
        return LABEL_PREFIX + transactionId;
    }

    public Path agentFile(String transactionId) {
        return launchAgentsDirectory.resolve(label(transactionId) + AGENT_SUFFIX);
    }

    /**
     * Writes the agent for this transaction and loads it. The helper then runs
     * independently of the application that handed the update over.
     */
    public int bootstrap(String transactionId, List<String> helperCommand, Path workDirectory,
            Path stdout, Path stderr) throws Exception {
        Path agent = agentFile(transactionId);
        Path parent = agent.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(agent, plist(label(transactionId), helperCommand, workDirectory, stdout, stderr));
        return runner.run(List.of("/bin/launchctl", "bootstrap", "gui/" + userId, agent.toString()))
            .exitCode();
    }

    /**
     * Unloads and deletes agents left behind by earlier transactions. Keeping
     * them would accumulate plists and could replay an outdated helper plan. A
     * job that is still running is left alone: another product may be updating
     * from the same account at the same time.
     */
    public List<String> bootoutStale(String keepTransactionId) throws Exception {
        List<String> removed = new ArrayList<>();
        if (!Files.isDirectory(launchAgentsDirectory)) {
            return removed;
        }
        String keepLabel = keepTransactionId == null ? null : label(keepTransactionId);
        try (var entries = Files.list(launchAgentsDirectory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(LABEL_PREFIX) || !name.endsWith(AGENT_SUFFIX)) {
                    continue;
                }
                String label = name.substring(0, name.length() - AGENT_SUFFIX.length());
                if (label.equals(keepLabel) || isLoaded(label)) {
                    continue;
                }
                runner.run(List.of("/bin/launchctl", "bootout", "gui/" + userId + "/" + label));
                Files.deleteIfExists(entry);
                removed.add(label);
            }
        }
        return removed;
    }

    /**
     * Unloads this transaction's agent. It is used when the helper never
     * acknowledged: the helper cannot switch anything before the application
     * exits, so unloading it prevents a later switch the user was told failed.
     */
    public int bootout(String transactionId) throws Exception {
        int exitCode = runner.run(
            List.of("/bin/launchctl", "bootout", "gui/" + userId + "/" + label(transactionId)))
            .exitCode();
        Files.deleteIfExists(agentFile(transactionId));
        return exitCode;
    }

    private boolean isLoaded(String label) {
        try {
            CommandResult result = runner.run(List.of("/bin/launchctl", "list", label));
            return result.output() != null && result.output().contains("\"PID\"");
        } catch (Exception unreadable) {
            // Never unload a job that cannot be inspected.
            return true;
        }
    }

    public static String plist(String label, List<String> helperCommand, Path workDirectory,
            Path stdout, Path stderr) {
        StringBuilder arguments = new StringBuilder();
        for (String argument : helperCommand) {
            arguments.append("    <string>").append(xmlEscape(argument)).append("</string>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
            + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "<dict>\n"
            + "  <key>Label</key>\n"
            + "  <string>" + xmlEscape(label) + "</string>\n"
            + "  <key>ProgramArguments</key>\n"
            + "  <array>\n"
            + arguments
            + "  </array>\n"
            + "  <key>WorkingDirectory</key>\n"
            + "  <string>" + xmlEscape(workDirectory.toString()) + "</string>\n"
            + "  <key>StandardOutPath</key>\n"
            + "  <string>" + xmlEscape(stdout.toString()) + "</string>\n"
            + "  <key>StandardErrorPath</key>\n"
            + "  <string>" + xmlEscape(stderr.toString()) + "</string>\n"
            + "  <key>RunAtLoad</key>\n"
            + "  <true/>\n"
            + "  <key>KeepAlive</key>\n"
            + "  <false/>\n"
            + "  <key>AbandonProcessGroup</key>\n"
            + "  <true/>\n"
            + "  <key>LimitLoadToSessionType</key>\n"
            + "  <string>Aqua</string>\n"
            + "</dict>\n"
            + "</plist>\n";
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @FunctionalInterface
    public interface CommandRunner {
        CommandResult run(List<String> command) throws Exception;
    }

    public record CommandResult(int exitCode, String output) {
    }
}
