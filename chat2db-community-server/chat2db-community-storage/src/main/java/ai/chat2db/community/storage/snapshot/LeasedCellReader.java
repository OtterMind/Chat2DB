package ai.chat2db.community.storage.snapshot;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.tools.exception.BusinessException;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the cells a snapshot leased from the live cursor of the execution that produced them.
 * <p>
 * Such a value never left the database, so a chunk is fetched from its {@code Clob} or {@code Blob} while the client
 * asks for it: a text value through a {@link CursorTextReader}, which keeps only the window a slice still needs, and a
 * binary value at its offset directly.
 */
class LeasedCellReader {

    /**
     * One reader per leased text cell, kept while its snapshot lives, so a sequential read walks the cursor once
     * instead of restarting from the beginning for every chunk.
     */
    private final Map<String, Map<Long, CursorTextReader>> textReaders = new ConcurrentHashMap<>();

    SnapshotReadChunk read(ResultSnapshot snapshot, ResultSnapshotCell cell, Object handle, long offset, int limit) {
        if (handle instanceof Blob blob) {
            byte[] binary = blobSlice(cell, blob, offset, limit);
            long nextOffset = offset + binary.length;
            return SnapshotReadChunk.builder()
                    .value(Base64.getEncoder().encodeToString(binary))
                    .offset(offset)
                    .nextOffset(nextOffset)
                    .eof(binary.length == 0 || nextOffset >= cell.getSizeBytes())
                    .sizeBytes(cell.getSizeBytes())
                    .sizeChars(null)
                    .build();
        }
        CursorTextReader reader = textReaders
                .computeIfAbsent(snapshot.getSnapshotId(), key -> new ConcurrentHashMap<>())
                .computeIfAbsent(ResultSnapshot.cellKey(cell.getRowIndex(), cell.getColumnIndex()),
                        key -> new CursorTextReader((Clob) handle));
        byte[] text = reader.slice(offset, limit);
        long nextOffset = offset + text.length;
        // The byte size of a text value is only known once it has been read to the end, so it grows while it is read
        cell.setSizeBytes(reader.knownSizeBytes());
        return SnapshotReadChunk.builder()
                .value(Base64.getEncoder().encodeToString(text))
                .offset(offset)
                .nextOffset(nextOffset)
                .eof(reader.isComplete() && nextOffset >= reader.knownSizeBytes())
                .sizeBytes(reader.knownSizeBytes())
                .sizeChars(cell.getSizeChars())
                .build();
    }

    /**
     * Forgets the readers of a snapshot whose content is gone, so its cursors are not read again.
     */
    void discard(String snapshotId) {
        textReaders.remove(snapshotId);
    }

    private byte[] blobSlice(ResultSnapshotCell cell, Blob blob, long offset, int limit) {
        long size = cell.getSizeBytes();
        if (offset >= size) {
            return new byte[0];
        }
        int wanted = (int) Math.min(limit, size - offset);
        try {
            return blob.getBytes(offset + 1, wanted);
        } catch (SQLException e) {
            throw new BusinessException("largeCellValue.snapshotReadFailed", new Object[]{"leased value"}, e);
        }
    }
}
