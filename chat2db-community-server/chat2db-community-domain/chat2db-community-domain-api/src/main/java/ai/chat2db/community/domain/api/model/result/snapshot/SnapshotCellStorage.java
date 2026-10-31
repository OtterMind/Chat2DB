package ai.chat2db.community.domain.api.model.result.snapshot;

/**
 * Storage location of a captured cell content.
 */
public enum SnapshotCellStorage {

    /**
     * The content is held by the source object the execution handed over and is only encoded when it is read.
     */
    LAZY,

    /**
     * The content stays in the database: the cell keeps the driver handle, and reads go through the live cursor kept
     * open by {@link ResultCursorHold}.
     */
    CURSOR,

    /**
     * The content is held by the snapshot as an in-memory value.
     */
    MEMORY,

    /**
     * The content is written to a file inside the snapshot directory.
     */
    FILE;
}
