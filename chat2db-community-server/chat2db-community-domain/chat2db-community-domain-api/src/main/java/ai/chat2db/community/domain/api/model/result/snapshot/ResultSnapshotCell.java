package ai.chat2db.community.domain.api.model.result.snapshot;

import lombok.Builder;
import lombok.Data;

/**
 * Content of one result cell kept by a result snapshot.
 * <p>
 * Only cells whose value was truncated in the response need an entry here; small values are returned in full and are
 * never captured.
 */
@Data
@Builder
public class ResultSnapshotCell {

    private int rowIndex;

    private int columnIndex;

    private volatile SnapshotCellStorage storage;

    /**
     * In-memory content for {@link SnapshotCellStorage#MEMORY}: the encoded record bytes of a text value, or the raw
     * bytes of a binary value.
     */
    private transient volatile Object memoryValue;

    /**
     * Absolute path of the backing file for {@link SnapshotCellStorage#FILE}.
     */
    private volatile String filePath;

    /**
     * Size of the content in bytes (UTF-8 for text).
     */
    private long sizeBytes;

    /**
     * Size of the content in characters for text content, {@code null} for binary content.
     */
    private Long sizeChars;
}
