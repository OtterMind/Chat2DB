package ai.chat2db.community.domain.api.model.result.snapshot;

/**
 * Keeps the database cursor behind a snapshot's leased values open.
 * <p>
 * A value that is only leased keeps the driver handle the execution produced, so its content can be read later without
 * querying the database again. That only works while the cursor and its connection are still alive, which is what this
 * hold represents: the store closes it when the snapshot is released or expires, and the execution that produced the
 * values must not close the cursor before that.
 */
public interface ResultCursorHold {

    /**
     * Releases the cursor. Implementations must tolerate being called more than once.
     */
    void close();
}
