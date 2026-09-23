package ai.chat2db.community.updater.v2.runtime;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Waits for the update helper to confirm that it accepted the persisted plan.
 *
 * <p>The helper appends its first audit lines, tagged {@code actor=HELPER},
 * before it touches the installation, so the first helper line appended to the
 * transaction audit log is the acknowledgement the handoff waits for. The log is
 * used instead of a new marker file so that a helper from an older release still
 * acknowledges correctly.</p>
 *
 * <p>Only content appended after the handoff started counts. A retry of the same
 * transaction reuses the transaction id and therefore the same log, and a marker
 * written by the previous attempt must not make the retry pass instantly.</p>
 */
public final class UpdateHelperAck {

    static final String HELPER_ACTOR_MARKER = "actor=HELPER";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250L);
    private static final int MAX_SCAN_BYTES = 1 << 20;

    private UpdateHelperAck() {
    }

    /** Length of the audit log before the handoff starts, or zero when it does not exist yet. */
    public static long offset(Path auditLogFile) {
        try {
            return Files.isRegularFile(auditLogFile) ? Files.size(auditLogFile) : 0L;
        } catch (Exception unreadable) {
            return 0L;
        }
    }

    public static boolean acknowledgedSince(Path auditLogFile, long offset) {
        try (SeekableByteChannel channel = Files.newByteChannel(auditLogFile)) {
            long size = channel.size();
            if (size <= offset) {
                return false;
            }
            channel.position(offset);
            int length = (int) Math.min(size - offset, MAX_SCAN_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.read(buffer);
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
                .contains(HELPER_ACTOR_MARKER);
        } catch (Exception unreadable) {
            return false;
        }
    }

    public static boolean awaitSince(Path auditLogFile, long offset, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (acknowledgedSince(auditLogFile, offset)) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return acknowledgedSince(auditLogFile, offset);
    }
}
