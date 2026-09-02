package ai.chat2db.spi.model.export;

/**
 * Self-described table-export abilities of one database, declared by its plugin.
 *
 * <p>{@code keysetSharding} means the dialect supports {@code WHERE pk > ? ORDER BY pk} style
 * paging so a table export can be split into parallel key ranges. {@code streamingCursor} means a
 * plain forward-only cursor can stream the whole table; without it the exporter must fall back to
 * offset paging. Plugins declare this instead of the core branching on dbType strings.
 */
public final class ExportCapability {

    private final boolean keysetSharding;

    private final boolean streamingCursor;

    private final int suggestedFetchSize;

    private ExportCapability(boolean keysetSharding, boolean streamingCursor, int suggestedFetchSize) {
        this.keysetSharding = keysetSharding;
        this.streamingCursor = streamingCursor;
        this.suggestedFetchSize = suggestedFetchSize;
    }

    /**
     * Plain JDBC databases: keyset sharding and streaming both work through the ANSI builders.
     */
    public static final ExportCapability RELATIONAL_DEFAULT =
            new ExportCapability(true, true, 10_000);

    /**
     * Warehouses and log stores that stream fine but penalize per-range queries.
     */
    public static final ExportCapability STREAMING_ONLY =
            new ExportCapability(false, true, 10_000);

    /**
     * Non-SQL backends whose export path is entirely plugin-owned.
     */
    public static final ExportCapability NONE =
            new ExportCapability(false, false, 0);

    public boolean isKeysetSharding() {
        return keysetSharding;
    }

    public boolean isStreamingCursor() {
        return streamingCursor;
    }

    public int getSuggestedFetchSize() {
        return suggestedFetchSize;
    }
}
