package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotCellStorage;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.tools.exception.BusinessException;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link IResultSnapshotStore} for domain tests, so they do not depend on the storage module. Content larger
 * than the spill limit is written to the temp root, which keeps size and release assertions meaningful.
 */
class InMemoryResultSnapshotStore implements IResultSnapshotStore {

    private static final long SPILL_BYTES = 1024 * 1024L;

    private final Path root;

    private final Map<String, Map<Long, Object>> cells = new ConcurrentHashMap<>();

    private final Map<String, Instant> expiresAt = new ConcurrentHashMap<>();

    /**
     * Cells the test wants the capture to fail for, keyed as {@code row:column}.
     */
    private final Set<String> failingCells = ConcurrentHashMap.newKeySet();

    private final Map<String, Integer> leases = new ConcurrentHashMap<>();



    InMemoryResultSnapshotStore(Path root) {
        this.root = root;
    }

    static <T> ObjectProvider<T> provider(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    @Override
    public ResultSnapshot register() {
        String snapshotId = UUID.randomUUID().toString();
        ResultSnapshot snapshot = new ResultSnapshot(snapshotId, Instant.now(),
                Instant.now().plusSeconds(1800));
        cells.put(snapshotId, new ConcurrentHashMap<>());
        expiresAt.put(snapshotId, snapshot.getExpiresAt());
        return snapshot;
    }

    /**
     * Makes the next capture of one cell throw, so a test can prove a single bad value does not fail the statement.
     */
    public void failCaptureAt(int rowIndex, int columnIndex) {
        failingCells.add(rowIndex + ":" + columnIndex);
    }

    @Override
    public ResultSnapshotCell capture(String snapshotId, int rowIndex, int columnIndex, Object content) {
        if (failingCells.remove(rowIndex + ":" + columnIndex)) {
            throw new IllegalStateException("capture failed for " + rowIndex + ":" + columnIndex);
        }
        Map<Long, Object> snapshotCells = require(snapshotId);
        byte[] bytes = content instanceof byte[] raw ? raw
                : String.valueOf(content).getBytes(StandardCharsets.UTF_8);
        snapshotCells.put(ResultSnapshot.cellKey(rowIndex, columnIndex), bytes);
        ResultSnapshotCell cell = ResultSnapshotCell.builder()
                .rowIndex(rowIndex)
                .columnIndex(columnIndex)
                .sizeBytes((long) bytes.length)
                .build();
        if (!(content instanceof byte[])) {
            cell.setSizeChars((long) String.valueOf(content).length());
        }
        if (bytes.length > SPILL_BYTES) {
            try {
                Files.createDirectories(root);
                Path file = root.resolve(snapshotId + "-" + rowIndex + "-" + columnIndex + ".bin");
                Files.write(file, bytes);
                cell.setStorage(SnapshotCellStorage.FILE);
                cell.setFilePath(file.toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        } else {
            cell.setStorage(SnapshotCellStorage.MEMORY);
        }
        return cell;
    }

    @Override
    public SnapshotReadChunk read(String snapshotId, int rowIndex, int columnIndex, long offset, int limit) {
        byte[] bytes = (byte[]) require(snapshotId).get(ResultSnapshot.cellKey(rowIndex, columnIndex));
        if (bytes == null) {
            throw new BusinessException("largeCellValue.snapshotCellMissing");
        }
        int start = (int) Math.max(0L, offset);
        if (start >= bytes.length) {
            return SnapshotReadChunk.builder()
                    .value("")
                    .offset(start)
                    .nextOffset(start)
                    .eof(true)
                    .sizeBytes((long) bytes.length)
                    .build();
        }
        int end = Math.min(bytes.length, start + Math.max(1, limit));
        while (end > start && end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return SnapshotReadChunk.builder()
                .value(Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(bytes, start, end)))
                .offset(start)
                .nextOffset((long) end)
                .eof(end >= bytes.length)
                .sizeBytes((long) bytes.length)
                .build();
    }

    @Override
    public boolean exists(String snapshotId) {
        return cells.containsKey(snapshotId);
    }

    @Override
    public void flushToDisk(String snapshotId) {
        // Everything stays in memory in this fake, so there is nothing to move to disk.
    }

    @Override
    public void hold(String snapshotId) {
        leases.merge(snapshotId, 1, Integer::sum);
    }

    @Override
    public void unhold(String snapshotId) {
        leases.computeIfPresent(snapshotId, (key, value) -> value > 1 ? value - 1 : null);
    }

    @Override
    public boolean isIdle(String snapshotId) {
        return leases.getOrDefault(snapshotId, 0) == 0;
    }

    @Override
    public void release(String snapshotId) {
        cells.remove(snapshotId);
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.list(root)) {
            paths.filter(path -> path.getFileName().toString().startsWith(snapshotId)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void evictExpired(Instant now) {
        cells.keySet().removeIf(snapshotId -> now.isAfter(expiresAt.get(snapshotId)));
    }

    @Override
    public void evictOverCapacity() {
    }

    private Map<Long, Object> require(String snapshotId) {
        Map<Long, Object> snapshotCells = cells.get(snapshotId);
        if (snapshotCells == null) {
            throw new BusinessException("largeCellValue.snapshotExpired");
        }
        return snapshotCells;
    }
}
