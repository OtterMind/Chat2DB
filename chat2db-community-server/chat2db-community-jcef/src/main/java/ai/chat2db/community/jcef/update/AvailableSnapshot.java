package ai.chat2db.community.jcef.update;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Retained manifest snapshot from a successful check.
 *
 * <p>Stores the exact manifest bytes so that the download phase uses the same
 * manifest that was discovered during the check. The snapshot has a bounded
 * in-memory lifetime measured with a monotonic clock; an already-started
 * download is not interrupted when the TTL expires.</p>
 */
public final class AvailableSnapshot {

    static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(30);

    private final String version;
    private final byte[] exactBytes;
    private final long fetchedAtNanos;

    public AvailableSnapshot(String version, byte[] exactBytes, long fetchedAtNanos) {
        this.version = version;
        this.exactBytes = exactBytes != null ? exactBytes.clone() : new byte[0];
        this.fetchedAtNanos = fetchedAtNanos;
    }

    public String version() {
        return version;
    }

    public byte[] exactBytes() {
        return exactBytes.clone();
    }

    public long fetchedAtNanos() {
        return fetchedAtNanos;
    }

    /**
     * Whether this snapshot is older than the configured TTL relative to the
     * supplied monotonic timestamp.
     */
    public boolean isExpired(long nowNanos) {
        return nowNanos - fetchedAtNanos > TTL_NANOS;
    }

    /**
     * Whether the supplied bytes are byte-for-byte identical to the retained
     * exact bytes.
     */
    public boolean sameBytes(byte[] other) {
        return Arrays.equals(exactBytes, other);
    }
}
