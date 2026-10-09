package ai.chat2db.community.domain.api.model.result.snapshot;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One query execution result kept by {@link ai.chat2db.community.domain.api.service.result.IResultSnapshotStore}.
 */
@Data
public class ResultSnapshot {

    private final String snapshotId;

    private final Instant createdAt;

    private volatile Instant expiresAt;

    /**
     * Captured cell content, keyed by {@link #cellKey(int, int)}. Both indexes follow the {@code dataList} layout,
     * which includes the leading row-number column at index {@code 0}.
     */
    private final Map<Long, ResultSnapshotCell> cells = new ConcurrentHashMap<>();

    /**
     * Sum of the in-memory content sizes currently held by this snapshot.
     */
    private final AtomicLong memoryBytes = new AtomicLong();

    /**
     * Sum of the bytes this snapshot wrote to disk, used to enforce the per snapshot cap.
     */
    private final AtomicLong diskBytes = new AtomicLong();

    /**
     * Last time this snapshot was captured into or read from, so eviction can avoid pulling content out from under an
     * in flight read.
     */
    private final AtomicLong lastActivityAt = new AtomicLong(System.currentTimeMillis());


    /**
     * Number of open writers or readers that need the content to stay on disk, so a streaming execution or a long
     * download is never cut short by a release.
     */
    private final AtomicInteger leases = new AtomicInteger();

    /**
     * Set when a release arrives while a lease is still open: the content is deleted as soon as the last lease is
     * dropped, so a running stream is never cut short.
     */
    private volatile boolean releaseRequested;

    public ResultSnapshot(String snapshotId, Instant createdAt, Instant expiresAt) {
        this.snapshotId = snapshotId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static long cellKey(int rowIndex, int columnIndex) {
        return ((long) rowIndex << 32) | (columnIndex & 0xffffffffL);
    }

    /**
     * Marks the snapshot as in use right now.
     */
    public void touch() {
        lastActivityAt.set(System.currentTimeMillis());
    }

    public int lease() {
        return leases.incrementAndGet();
    }

    public int releaseLease() {
        return leases.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public boolean hasLease() {
        return leases.get() > 0;
    }

    public boolean isReleaseRequested() {
        return releaseRequested;
    }

    public void requestRelease() {
        releaseRequested = true;
    }
}
