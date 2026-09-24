package ai.chat2db.community.sqlx;

import ai.chat2db.community.tools.sqlx.SqlxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the SQLX executable and captures its JSON output.
 * <p>
 * Credentials are never passed on the command line; callers that need them write to stdin.
 */
public final class SqlxProcessRunner {

    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);

    private SqlxProcessRunner() {
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }

        /** Standard output when the command produced any, otherwise standard error. */
        public String output() {
            return stdout == null || stdout.isBlank() ? (stderr == null ? "" : stderr) : stdout;
        }
    }

    public static Result run(Path executable, List<String> arguments, Duration timeout) {
        return run(executable, arguments, timeout, null);
    }

    /**
     * @param standardInput written to the process and closed, or {@code null} for no input.
     */
    public static Result run(Path executable, List<String> arguments, Duration timeout, String standardInput) {
        List<String> command = new java.util.ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        // The CLI otherwise schedules its own background update check.
        environment.put("SQLX_NO_UPDATE_CHECK", "1");
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new SqlxException("sqlx.not_executable", "cannot start " + executable + ": " + exception.getMessage(),
                    exception);
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = reader(process.getInputStream(), stdout);
        Thread errReader = reader(process.getErrorStream(), stderr);
        try {
            if (standardInput != null) {
                process.getOutputStream().write(standardInput.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // impl-contract: a command that exits before reading stdin is not a failure by itself.
        }
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new SqlxException("sqlx.cancelled", "the SQLX command was interrupted", exception);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        join(outReader);
        join(errReader);
        int exitCode = finished ? process.exitValue() : -1;
        return new Result(exitCode, stdout.toString(), stderr.toString(), !finished);
    }

    private static Thread reader(InputStream stream, StringBuilder target) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    target.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // impl-contract: a destroyed process closes its streams mid-read.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
