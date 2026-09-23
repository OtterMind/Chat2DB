package ai.chat2db.community.updater.v2.installation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Starts the desktop update helper as a macOS LaunchAgent.
 *
 * <p>A helper spawned as a plain child process of the application does not
 * survive the handoff: the application exits about 150 ms after spawning it,
 * while the helper needs seconds to start its JVM, so the helper is reclaimed
 * together with the application before it can switch anything. Running the
 * helper under launchd removes that dependency, and {@code AbandonProcessGroup}
 * keeps the application the helper relaunches alive after the helper exits.</p>
 *
 * <p>The agent uses one stable label per product and stays registered: a label
 * per transaction, or unloading the job after every update, would register a new
 * background item with macOS each time, which the system reports to the user and
 * lists under login items. Later updates reuse the loaded job with
 * {@code kickstart} instead of registering again. The agent is loaded with
 * {@code RunAtLoad} disabled, so the plist that stays behind cannot replay an
 * outdated plan at the next login.</p>
 *
 * <p>{@code launchctl submit} is deliberately not used: launchd kills the
 * remaining processes of a submitted job's process group when its main process
 * exits, which would kill the relaunched application.</p>
 */
public final class MacLaunchAgentHandoff {

    static final String LABEL_PREFIX = "com.chat2db.updater.";
    static final String AGENT_SUFFIX = ".plist";
    private static final Duration LAUNCHCTL_TIMEOUT = Duration.ofSeconds(15L);

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

    /**
     * A wedged launchd must not block the handoff: every launchctl call is bounded,
     * and a timeout is reported as a failure so the caller can fall back.
     */
    public static CommandRunner processRunner() {
        return command -> {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(LAUNCHCTL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(LAUNCHCTL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return new CommandResult(-1, "launchctl timed out after " + LAUNCHCTL_TIMEOUT.toSeconds() + "s");
            }
            String output = new String(process.getInputStream().readAllBytes());
            return new CommandResult(process.exitValue(), output);
        };
    }

    static String currentUserId(CommandRunner runner) throws Exception {
        CommandResult result = runner.run(List.of("/usr/bin/id", "-u"));
        String userId = result.output() == null ? "" : result.output().trim();
        return userId.isEmpty() ? "-1" : userId;
    }

    static String label(String product) {
        String normalized = product == null ? "" : product.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Update product id contains unsafe label characters");
        }
        return LABEL_PREFIX + normalized;
    }

    public Path agentFile(String product) {
        return launchAgentsDirectory.resolve(label(product) + AGENT_SUFFIX);
    }

    /**
     * Makes sure the agent of this product is registered and starts the helper.
     * The job is registered once and then reused: registering it again after every
     * update would make macOS report a new background item to the user each time.
     */
    public int bootstrap(String product, List<String> helperCommand, Path workDirectory,
            Path stdout, Path stderr) throws Exception {
        Path agent = agentFile(product);
        Path parent = agent.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(agent, plist(label(product), helperCommand, workDirectory, stdout, stderr));
        if (!isLoaded(product)) {
            int loadExit = runner.run(List.of("/bin/launchctl", "bootstrap", "gui/" + userId, agent.toString()))
                .exitCode();
            if (loadExit != 0) {
                return loadExit;
            }
        }
        return runner.run(List.of("/bin/launchctl", "kickstart", "gui/" + userId + "/" + label(product)))
            .exitCode();
    }

    /** Whether this product's agent is already registered with launchd. */
    boolean isLoaded(String product) {
        try {
            return runner.run(List.of("/bin/launchctl", "list", label(product))).exitCode() == 0;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * Unloads this product's agent. It is used when the helper never acknowledged:
     * the helper cannot switch anything before the application exits, so unloading
     * it prevents a later switch the user was told failed. A non-zero exit code
     * also means "this job was not loaded", which is normal on a first handoff.
     */
    public int bootout(String product) throws Exception {
        int exitCode = runner.run(
            List.of("/bin/launchctl", "bootout", "gui/" + userId + "/" + label(product)))
            .exitCode();
        Files.deleteIfExists(agentFile(product));
        return exitCode;
    }

    static String plist(String label, List<String> helperCommand, Path workDirectory,
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
            + "  <false/>\n"
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
