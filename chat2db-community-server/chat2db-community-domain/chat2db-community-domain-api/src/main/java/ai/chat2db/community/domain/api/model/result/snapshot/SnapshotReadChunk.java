package ai.chat2db.community.domain.api.model.result.snapshot;

import lombok.Builder;
import lombok.Data;

/**
 * One chunk of snapshot cell content, base64 encoded for transport.
 * <p>
 * {@code offset} is a byte offset, text is encoded as UTF-8 before slicing, and {@code nextOffset} always equals
 * {@code offset + decoded byte length}, so callers can advance without gaps or repeats.
 */
@Data
@Builder
public class SnapshotReadChunk {

    private String value;

    private long offset;

    private long nextOffset;

    private boolean eof;

    private long sizeBytes;

    private Long sizeChars;
}
