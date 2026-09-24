package ai.chat2db.community.sqlx;

import ai.chat2db.community.tools.sqlx.SqlxException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Read-only facts about an executable that claims to be SQLX.
 * <p>
 * Every candidate is confirmed with {@code --version}: the release installers use the same
 * {@code (OtterMind/sqlx)} marker to avoid adopting an unrelated program called sqlx.
 */
public final class SqlxExecutable {

    public static final String MARKER = "(OtterMind/sqlx)";
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);

    private SqlxExecutable() {
    }

    /**
     * Version of the given executable, or empty when it is missing, foreign or broken.
     * <p>
     * The CLI prints its program name first, for example {@code sqlx 0.1.15 (OtterMind/sqlx)}, so the
     * version is the first release-looking token before the marker rather than the first token.
     */
    public static Optional<String> versionOf(Path executable) {
        if (executable == null || !Files.isRegularFile(executable)) {
            return Optional.empty();
        }
        SqlxProcessRunner.Result result;
        try {
            result = SqlxProcessRunner.run(executable, List.of("--version"), VERSION_TIMEOUT);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        String output = result.output();
        if (!result.succeeded()) {
            return Optional.empty();
        }
        return versionFromOutput(output);
    }

    /** Version token of a {@code --version} output, or empty when it is not OtterMind SQLX. */
    static Optional<String> versionFromOutput(String output) {
        if (output == null || !output.contains(MARKER)) {
            return Optional.empty();
        }
        for (String token : output.trim().split("\\s+")) {
            if (token.startsWith("(")) {
                break;
            }
            if (isValidVersion(token)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    public static boolean isOtterMind(Path executable) {
        return versionOf(executable).isPresent();
    }

    /** Whether the version string looks like a release the release channel can publish. */
    public static boolean isValidVersion(String version) {
        return version != null && version.matches("^[0-9][0-9A-Za-z.+-]*$");
    }
}
