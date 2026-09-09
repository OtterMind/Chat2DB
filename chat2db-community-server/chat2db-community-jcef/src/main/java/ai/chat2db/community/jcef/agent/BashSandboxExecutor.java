package ai.chat2db.community.jcef.agent;

import ai.chat2db.community.domain.api.service.agent.AgentShellExecutor;
import ai.chat2db.community.domain.api.service.agent.AgentShellSettingsService;
import ai.chat2db.community.domain.api.model.agent.AgentShellCommand;
import ai.chat2db.community.tools.exception.BusinessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class BashSandboxExecutor implements AgentShellExecutor {
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private final AgentShellSettingsService settings;

    public BashSandboxExecutor(AgentShellSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public AgentShellCommand prepare(String sessionId, String command) {
        if (command == null || command.isBlank() || command.length() > 16 * 1024) {
            throw new IllegalArgumentException("Invalid shell command");
        }
        return new AgentShellCommand(sessionId, settings.resolveWorkingDirectory(sessionId), command);
    }

    @Override
    public String execute(AgentShellCommand invocation, BooleanSupplier cancelled) throws Exception {
        String sessionId = invocation.sessionId();
        Path workspace = BashSettingsService.existingDirectory(invocation.workingDirectory());
        if (!workspace.toString().equals(invocation.workingDirectory())) {
            throw new BusinessException("agent.bash.directory.changed");
        }
        ProcessBuilder builder = new ProcessBuilder(command(workspace, invocation.command()));
        builder.directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("LANG", "en_US.UTF-8");
        if (cancelled.getAsBoolean()) throw new IOException("Shell command was cancelled");
        Process process = builder.start();
        ai.chat2db.community.tools.util.AgentTrace.record("shell.started", sessionId, null,
                java.util.Map.of("pid", process.pid(), "sandbox", builder.command().get(0),
                        "workingDirectory", workspace.toString()));
        process.getOutputStream().close();
        var reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-shell-output");
            thread.setDaemon(true);
            return thread;
        });
        var output = reader.submit(() -> {
            byte[] bytes = process.getInputStream().readNBytes(OUTPUT_LIMIT + 1);
            if (bytes.length > OUTPUT_LIMIT) terminate(process);
            return bytes;
        });
        String outcome = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline) {
                    outcome = cancelled.getAsBoolean() ? "Command cancelled" : "Command timed out";
                    terminate(process);
                    break;
                }
            }
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            String text = new String(bytes, 0, Math.min(bytes.length, OUTPUT_LIMIT), StandardCharsets.UTF_8);
            ai.chat2db.community.tools.util.AgentTrace.record("shell.finished", sessionId, null,
                    java.util.Map.of("bytes", bytes.length, "outcome", outcome.isEmpty() ? "EXITED" : outcome));
            return text + (bytes.length > OUTPUT_LIMIT ? "\n[Output truncated]" : "")
                    + "\n" + (outcome.isEmpty() ? "Exit code: " + process.exitValue() : outcome);
        } finally {
            terminate(process);
            process.getInputStream().close();
            reader.shutdownNow();
        }
    }

    private List<String> command(Path workspace, String command) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>();
        if (os.contains("mac") || os.contains("darwin")) {
            String profile = """
                    (version 1)
                    (deny default)
                    (import "dyld-support.sb")
                    (allow file-read-metadata)
                    (allow process* sysctl-read mach-lookup)
                    (allow file-read*
                      (subpath "/System") (subpath "/usr/lib") (subpath "/usr/share")
                      (subpath "/bin") (subpath "/usr/bin") (subpath "/private/var/db/dyld")
                      (literal "/dev/null") (literal "/dev/urandom") (literal "/dev/random")
                      (subpath %s))
                    (allow file-write* (literal "/dev/null") (subpath %s))
                    """.formatted(quote(workspace), quote(workspace));
            args.addAll(List.of("/usr/bin/sandbox-exec", "-p", profile));
        } else if (os.contains("linux") && Files.isExecutable(Path.of("/usr/bin/bwrap"))) {
            args.addAll(List.of("/usr/bin/bwrap", "--unshare-all", "--die-with-parent", "--new-session",
                    "--ro-bind", "/usr", "/usr", "--ro-bind", "/bin", "/bin",
                    "--ro-bind", "/lib", "/lib", "--ro-bind-try", "/lib64", "/lib64",
                    "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
                    "--bind", workspace.toString(), workspace.toString(), "--chdir", workspace.toString()));
        } else {
            throw new IOException("A supported shell sandbox is unavailable");
        }
        args.addAll(List.of("/bin/bash", "--noprofile", "--norc", "-c", command));
        return args;
    }

    private String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
