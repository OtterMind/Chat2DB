package ai.chat2db.community.domain.api.service.result;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultCursorHold;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;

import java.time.Instant;

/**
 * Keeps the complete content of large values for the lifetime of one query execution result.
 * <p>
 * The store replaces the previous "re-query the row by primary key" read path: content is captured while the JDBC
 * result set is still open, so later reads never touch the database again and cannot mix versions of a changed row.
 * Storage is local to the running product: {@code <product state directory>/<env>/storage/result-snapshot}.
 */
public interface IResultSnapshotStore {

    /**
     * Registers a new snapshot for one query execution.
     * <p>
     * Snapshots are guarded by an unguessable identifier and a short lifetime instead of an owning identity, so the
     * large-value read path never has to look at who is asking.
     */
    ResultSnapshot register();

    /**
     * Registers a new snapshot that owns a live cursor.
     * <p>
     * A snapshot that leases values instead of capturing their content only works while that cursor is open, so the
     * store closes the hold as soon as the snapshot is released or expires.
     *
     * @param cursorHold keeps the cursor of the execution that produced the leased values open, or {@code null} when
     *                   the snapshot holds no cursor backed value
     */
    ResultSnapshot register(ResultCursorHold cursorHold);

    /**
     * Keeps the driver handle of one cell instead of its content.
     * <p>
     * The value stays in the database and is read from the live cursor when the client asks for it, so nothing is
     * transferred while the query runs. Only a snapshot registered with a {@link ResultCursorHold} can lease values.
     *
     * @param handle the {@code Clob} or {@code Blob} the driver produced for this cell
     */
    ResultSnapshotCell lease(String snapshotId, int rowIndex, int columnIndex, Object handle);

    /**
     * Adds a cursor this snapshot may lease values from.
     * <p>
     * The execution that owns the cursor takes the hold, the store keeps it and closes it with the snapshot, so the
     * connection is given back as soon as the content is gone.
     */
    void addCursorHold(String snapshotId, ResultCursorHold cursorHold);

    /**
     * Captures the complete content of one cell.
     *
     * @param content text ({@link String}), binary ({@code byte[]}), or the {@code Clob}/{@code Blob} a driver returns
     *                for a large value
     */
    ResultSnapshotCell capture(String snapshotId, int rowIndex, int columnIndex, Object content);

    /**
     * Reads a slice of a captured cell.
     *
     * @param offset byte offset into the UTF-8 encoded content
     * @param limit  maximum number of bytes to return
     */
    SnapshotReadChunk read(String snapshotId, int rowIndex, int columnIndex, long offset, int limit);

    boolean exists(String snapshotId);

    /**
     * Moves every in-memory cell of the snapshot to disk and releases the heap it held.
     * <p>
     * A snapshot outlives its query because the user can still copy a value while the read token is valid. Flushing on
     * execution end keeps the disk copy readable for that window without holding hundreds of megabytes of heap.
     */
    void flushToDisk(String snapshotId);

    /**
     * Drops one snapshot and deletes its files.
     */
    void release(String snapshotId);

    /**
     * Keeps the content alive while a writer or a long download still needs it.
     *
     * @param snapshotId snapshot identifier.
     */
    void hold(String snapshotId);

    /**
     * Drops one lease taken with {@link #hold(String)}.
     *
     * @param snapshotId snapshot identifier.
     */
    void unhold(String snapshotId);


    /**
     * @param snapshotId snapshot identifier.
     * @return {@code true} when nobody holds a lease, i.e. no capture, stream or download is using the content.
     */
    boolean isIdle(String snapshotId);

    /**
     * Drops every snapshot that is expired at the given instant.
     */
    void evictExpired(Instant now);

    /**
     * Drops the oldest snapshots until the total size of the snapshot directory is back within the global limit.
     */
    void evictOverCapacity();
}
