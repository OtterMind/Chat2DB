package ai.chat2db.community.updater.v2.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Waits for the update helper to confirm that it accepted the persisted plan.
 *
 * <p>The helper appends its first audit lines, tagged {@code actor=HELPER},
 * before it touches the installation, so the first helper line in the
 * transaction audit log is the acknowledgement the handoff waits for. The log
 * is used instead of a new marker file so that a helper from an older release
 * still acknowledges correctly.</p>
 */
public final class UpdateHelperAck {

    public static final String HELPER_ACTOR_MARKER = "actor=HELPER";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250L);

    private UpdateHelperAck() {
    }

    public static boolean acknowledged(Path auditLogFile) {
        try {
            return auditLogFile != null
                && Files.isRegularFile(auditLogFile)
                && Files.readString(auditLogFile).contains(HELPER_ACTOR_MARKER);
        } catch (Exception unreadable) {
            return false;
        }
    }

    public static boolean await(Path auditLogFile, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (acknowledged(auditLogFile)) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return acknowledged(auditLogFile);
    }
}
