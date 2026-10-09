package ai.chat2db.community.storage.snapshot;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotCellStorage;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File backed {@link IResultSnapshotStore}.
 * <p>
 * Layout: {@code <storage base path>/result-snapshot/<snapshotId>/<rowIndex>-<columnIndex>.bin}. Only cells whose
 * content does not fit the in-memory budget are written to a file.
 */
@Slf4j
@Component
public class FileResultSnapshotStore implements IResultSnapshotStore {

    /**
     * Directory name under the shared storage base path.
     */
    public static final String SNAPSHOT_DIRECTORY = "result-snapshot";

    /**
     * Content larger than this is written to a file instead of being kept in memory. Mirrors the reference default
     * used by desktop database tools for cached content.
     */
    public static final long CELL_SPILL_BYTES = 1024 * 1024L;

    /**
     * In-memory budget of a single snapshot. When exceeded, the largest in-memory cells are written to files until the
     * footprint is back within the budget.
     */
    public static final long SNAPSHOT_MEMORY_BUDGET_BYTES = 32 * 1024 * 1024L;

    /**
     * Upper bound of one snapshot directory.
     */
    public static final long SNAPSHOT_DISK_LIMIT_BYTES = 512 * 1024 * 1024L;

    /**
     * Upper bound of the whole snapshot directory; the oldest snapshots are dropped first.
     */
    /**
     * Soft target for the bytes this process may keep in the snapshot directory. Eviction only sees the snapshots of
     * this process, so several processes sharing one state directory can each hold this much.
     */
    public static final long GLOBAL_DISK_LIMIT_BYTES = 2L * 1024 * 1024 * 1024L;

    public static final Duration SNAPSHOT_TTL = Duration.ofMinutes(30);

    /**
     * A snapshot used within this window is treated as in use and is skipped by capacity eviction.
     */
    private static final Duration ACTIVE_SNAPSHOT_GRACE = Duration.ofSeconds(60);

    private static final int COPY_BUFFER_BYTES = 8192;

    private static final String STAGING_FILE_SUFFIX = ".part";

    /**
     * Text content is stored as fixed size records: a four byte payload length followed by plain UTF-8 bytes, padded to
     * {@link #TEXT_RECORD_SIZE}. Records are cut on byte offsets, so a record can end inside a multi byte character;
     * readers concatenate the byte windows first and decode once, which keeps every byte reachable exactly once.
     */
    private static final int TEXT_RECORD_SIZE = 256 * 1024;

    private static final int TEXT_RECORD_HEADER_BYTES = 4;


    /**
     * Records the snapshots owned by the running process. The next process reads it on startup, so leftovers of a run
     * that never got to release its snapshots can be removed immediately instead of waiting for the TTL sweep.
     */
    private static final String INDEX_FILE_NAME = "index.json";

    /**
     * Every process writes its own index, so two instances sharing one state directory can never mistake each other's
     * live snapshots for leftovers.
     */
    private static final String INDEX_FILE_PREFIX = "index-";

    private static final String INDEX_FILE_SUFFIX = ".json";

    private static final Pattern SNAPSHOT_ID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Map<String, ResultSnapshot> snapshots = new ConcurrentHashMap<>();

    private final Path rootDirectory;

    private final long snapshotDiskLimitBytes;

    private final long globalDiskLimitBytes;

    public FileResultSnapshotStore() {
        this(Path.of(ConfigUtils.getEnvBasePath(), "storage", SNAPSHOT_DIRECTORY));
    }

    public FileResultSnapshotStore(Path rootDirectory) {
        this(rootDirectory, SNAPSHOT_DISK_LIMIT_BYTES, GLOBAL_DISK_LIMIT_BYTES);
    }

    /**
     * Test seam: the shipped limits are constants, and only a caller that supplies smaller ones can exercise the
     * eviction and cap paths without writing hundreds of megabytes.
     */
    FileResultSnapshotStore(Path rootDirectory, long snapshotDiskLimitBytes, long globalDiskLimitBytes) {
        this.rootDirectory = rootDirectory;
        this.snapshotDiskLimitBytes = snapshotDiskLimitBytes;
        this.globalDiskLimitBytes = globalDiskLimitBytes;
    }

    @PostConstruct
    public void prepareRootDirectory() {
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create result snapshot directory: " + rootDirectory, e);
        }
        cleanupPreviousRun();
    }

    /**
     * Drops expired snapshots and enforces the global disk limit. Runs periodically so a snapshot whose owner never
     * came back for it does not keep large values in memory or on disk.
     */
    @Scheduled(fixedDelay = 60_000L)
    public void evictExpiredSnapshots() {
        evictExpired(Instant.now());
        evictOverCapacity();
        // Directories whose process died without an index entry would otherwise stay on disk forever
        sweepStaleDirectories();
    }

    /**
     * Releases every snapshot on a clean shutdown so a normal exit leaves neither files nor index entries behind.
     */
    @PreDestroy
    public void releaseAllOnShutdown() {
        for (String snapshotId : new ArrayList<>(snapshots.keySet())) {
            release(snapshotId);
        }
        try {
            Files.deleteIfExists(ownIndexFile());
        } catch (IOException e) {
            log.warn("Failed to delete result snapshot index on shutdown", e);
        }
    }

    /**
     * Removes everything a previous process left behind. The index lists the snapshots that process owned, so those
     * directories are stale by definition and are deleted immediately; directories that are neither indexed nor
     * tracked and are older than the TTL are removed as a fallback when the index is missing or damaged.
     */
    void cleanupPreviousRun() {
        if (!Files.isDirectory(rootDirectory)) {
            return;
        }
        List<Path> indexFiles = listIndexFiles();
        for (Path indexFile : indexFiles) {
            if (isOwnIndexFile(indexFile) || hasLiveOwner(indexFile)) {
                continue;
            }
            for (String snapshotId : readIndex(indexFile)) {
                if (!snapshots.containsKey(snapshotId)) {
                    deleteDirectory(snapshotDirectory(snapshotId));
                }
            }
            try {
                Files.deleteIfExists(indexFile);
            } catch (IOException e) {
                log.warn("Failed to delete stale result snapshot index {}", indexFile, e);
            }
        }
        writeIndex();
        sweepStaleDirectories();
    }

    private List<Path> listIndexFiles() {
        try (var entries = Files.list(rootDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return INDEX_FILE_NAME.equals(name)
                                || (name.startsWith(INDEX_FILE_PREFIX) && name.endsWith(INDEX_FILE_SUFFIX));
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to scan result snapshot indexes in {}", rootDirectory, e);
            return List.of();
        }
    }

    private Path ownIndexFile() {
        return rootDirectory.resolve(INDEX_FILE_PREFIX + ProcessHandle.current().pid() + INDEX_FILE_SUFFIX);
    }

    private boolean isOwnIndexFile(Path indexFile) {
        return indexFile.getFileName().toString().equals(ownIndexFile().getFileName().toString());
    }

    /**
     * @return {@code true} when the process that wrote this index is still running, in which case its snapshots must be
     *         left alone
     */
    private boolean hasLiveOwner(Path indexFile) {
        String name = indexFile.getFileName().toString();
        if (!name.startsWith(INDEX_FILE_PREFIX) || !name.endsWith(INDEX_FILE_SUFFIX)) {
            // Legacy index without an owner: it can only come from a process that is gone
            return false;
        }
        String pid = name.substring(INDEX_FILE_PREFIX.length(), name.length() - INDEX_FILE_SUFFIX.length());
        try {
            return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void sweepStaleDirectories() {
        Instant staleBefore = Instant.now().minus(SNAPSHOT_TTL);
        try (var entries = Files.list(rootDirectory)) {
            entries.filter(Files::isDirectory).forEach(directory -> {
                if (snapshots.containsKey(directory.getFileName().toString())) {
                    return;
                }
                try {
                    Instant lastModified = Files.getLastModifiedTime(directory).toInstant();
                    if (lastModified.isBefore(staleBefore)) {
                        log.info("Removing stale result snapshot directory {}", directory);
                        deleteDirectory(directory);
                    }
                } catch (IOException e) {
                    log.warn("Failed to inspect snapshot directory {}", directory, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to scan result snapshot directory {}", rootDirectory, e);
        }
    }

    /**
     * @return snapshot ids recorded by the previous process, or {@code null} when no readable index exists
     */
    private List<String> readIndex(Path indexFile) {
        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return List.of();
            }
            List<String> snapshotIds = new ArrayList<>();
            for (String token : content.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
                String value = token.trim();
                // Only accept the id shape this store creates, so a damaged or hand edited index can never point the
                // cleanup outside the snapshot directory.
                if (SNAPSHOT_ID_PATTERN.matcher(value).matches()) {
                    snapshotIds.add(value);
                }
            }
            return snapshotIds;
        } catch (IOException e) {
            log.warn("Failed to read result snapshot index {}", indexFile, e);
            return List.of();
        }
    }

    /**
     * Rewrites the index atomically. The index is an optimisation for the next startup, so a failure only degrades
     * cleanup and must never break the running query.
     */
    private void writeIndex() {
        Path indexFile = ownIndexFile();
        StringBuilder content = new StringBuilder("[");
        boolean first = true;
        for (String snapshotId : snapshots.keySet()) {
            if (!first) {
                content.append(',');
            }
            content.append('"').append(snapshotId).append('"');
            first = false;
        }
        content.append(']');
        try {
            writeAtomically(indexFile, content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to update result snapshot index {}", indexFile, e);
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path staging = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(staging, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /**
     * Directory that holds every snapshot of the running product.
     */
    public Path getRootDirectory() {
        return rootDirectory;
    }

    @Override
    public ResultSnapshot register() {
        Instant now = Instant.now();
        String snapshotId = UUID.randomUUID().toString();
        ResultSnapshot snapshot = new ResultSnapshot(snapshotId, now, now.plus(SNAPSHOT_TTL));
        snapshots.put(snapshotId, snapshot);
        writeIndex();
        return snapshot;
    }

    @Override
    public ResultSnapshotCell capture(String snapshotId, int rowIndex, int columnIndex, Object content) {
        ResultSnapshot snapshot = requireSnapshot(snapshotId);
        snapshot.touch();
        ResultSnapshotCell cell = captureContent(snapshot, rowIndex, columnIndex, content);
        snapshot.getCells().put(ResultSnapshot.cellKey(rowIndex, columnIndex), cell);
        if (snapshot.getMemoryBytes().get() > SNAPSHOT_MEMORY_BUDGET_BYTES) {
            spillLargestCells(snapshot);
        }
        return cell;
    }

    @Override
    public SnapshotReadChunk read(String snapshotId, int rowIndex, int columnIndex, long offset, int limit) {
        ResultSnapshot snapshot = requireSnapshot(snapshotId);
        snapshot.touch();
        ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(rowIndex, columnIndex));
        if (cell == null) {
            throw new BusinessException("largeCellValue.snapshotCellMissing");
        }
        long effectiveOffset = Math.max(0L, offset);
        // Reserve room for one complete UTF-8 character so a caller can never stall on a zero progress chunk
        int effectiveLimit = Math.max(4, limit);
        byte[] bytes = readSlice(cell, effectiveOffset, effectiveLimit);
        long nextOffset = effectiveOffset + bytes.length;
        // EOF only means the content is exhausted: character alignment can make a chunk shorter than the limit
        boolean eof = bytes.length == 0 || nextOffset >= cell.getSizeBytes();
        return SnapshotReadChunk.builder()
                .value(Base64.getEncoder().encodeToString(bytes))
                .offset(effectiveOffset)
                .nextOffset(nextOffset)
                .eof(eof)
                .sizeBytes(cell.getSizeBytes())
                .sizeChars(cell.getSizeChars())
                .build();
    }

    @Override
    public boolean exists(String snapshotId) {
        return snapshotId != null && snapshots.containsKey(snapshotId);
    }

    @Override
    public void flushToDisk(String snapshotId) {
        ResultSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            return;
        }
        for (ResultSnapshotCell cell : snapshot.getCells().values()) {
            Object memoryValue = cell.getMemoryValue();
            if (cell.getStorage() == SnapshotCellStorage.MEMORY && memoryValue instanceof byte[] bytes) {
                // writeFile subtracts what it flushed, so a capture that lands while this loop runs keeps its accounting
                writeFile(snapshot, cell, bytes);
            }
        }
    }

    @Override
    public void hold(String snapshotId) {
        ResultSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot != null) {
            snapshot.lease();
        }
    }

    @Override
    public void unhold(String snapshotId) {
        ResultSnapshot snapshot = snapshots.get(snapshotId);
        if (snapshot == null) {
            return;
        }
        snapshot.releaseLease();
        if (snapshot.isReleaseRequested() && !snapshot.hasLease()) {
            release(snapshotId);
        }
    }


    @Override
    public boolean isIdle(String snapshotId) {
        ResultSnapshot snapshot = snapshots.get(snapshotId);
        return snapshot != null && !snapshot.hasLease();
    }

    @Override
    public void release(String snapshotId) {
        ResultSnapshot leased = snapshots.get(snapshotId);
        if (leased != null && leased.hasLease()) {
            // A stream or a capture is still reading this content: delete it once the last lease is gone
            leased.requestRelease();
            return;
        }
        ResultSnapshot snapshot = snapshots.remove(snapshotId);
        if (snapshot == null) {
            return;
        }
        snapshot.getCells().clear();
        snapshot.getMemoryBytes().set(0L);
        deleteDirectory(snapshotDirectory(snapshotId));
        writeIndex();
    }

    @Override
    public void evictExpired(Instant now) {
        List<String> expired = new ArrayList<>();
        snapshots.forEach((snapshotId, snapshot) -> {
            if (snapshot.getExpiresAt() == null || !now.isAfter(snapshot.getExpiresAt())) {
                return;
            }
            if (snapshot.hasLease()) {
                // A capture or download is still using the content, so the snapshot gets a fresh window instead of
                // being deleted under it.
                snapshot.setExpiresAt(now.plus(SNAPSHOT_TTL));
                return;
            }
            expired.add(snapshotId);
        });
        expired.forEach(this::release);
    }

    @Override
    public void evictOverCapacity() {
        long total = 0L;
        List<ResultSnapshot> ordered = new ArrayList<>(snapshots.values());
        for (ResultSnapshot snapshot : ordered) {
            total += directorySize(snapshotDirectory(snapshot.getSnapshotId()));
        }
        if (total <= globalDiskLimitBytes) {
            return;
        }
        ordered.sort(Comparator.comparing(ResultSnapshot::getCreatedAt));
        Instant activeSince = Instant.now().minus(ACTIVE_SNAPSHOT_GRACE);
        for (ResultSnapshot snapshot : ordered) {
            if (total <= globalDiskLimitBytes) {
                return;
            }
            if (snapshot.hasLease()
                    || Instant.ofEpochMilli(snapshot.getLastActivityAt().get()).isAfter(activeSince)) {
                // A query may still be streaming into it, a download may still be running, or a client may still be
                // paging through it
                continue;
            }
            total -= directorySize(snapshotDirectory(snapshot.getSnapshotId()));
            release(snapshot.getSnapshotId());
        }
    }

    private ResultSnapshot requireSnapshot(String snapshotId) {
        ResultSnapshot snapshot = snapshotId == null ? null : snapshots.get(snapshotId);
        if (snapshot == null) {
            throw new BusinessException("largeCellValue.snapshotExpired");
        }
        return snapshot;
    }

    private ResultSnapshotCell captureContent(ResultSnapshot snapshot, int rowIndex, int columnIndex, Object content) {
        ResultSnapshotCell cell = ResultSnapshotCell.builder()
                .rowIndex(rowIndex)
                .columnIndex(columnIndex)
                .build();
        if (content == null) {
            cell.setStorage(SnapshotCellStorage.MEMORY);
            cell.setSizeBytes(0L);
            cell.setSizeChars(0L);
            return cell;
        }
        if (content instanceof String text) {
            storeText(snapshot, cell, text);
            return cell;
        }
        if (content instanceof byte[] bytes) {
            storeBinary(snapshot, cell, bytes);
            return cell;
        }
        if (content instanceof java.sql.Clob clob) {
            storeText(snapshot, cell, readClob(clob));
            return cell;
        }
        if (content instanceof java.sql.Blob blob) {
            storeBinary(snapshot, cell, readBlob(blob));
            return cell;
        }
        // Any other driver specific value object is stored through its textual form
        storeText(snapshot, cell, String.valueOf(content));
        return cell;
    }

    /**
     * Text content is kept as a String; byte offsets are derived from the UTF-8 encoding on demand, which keeps every
     * slice on a character boundary.
     */
    private void storeText(ResultSnapshot snapshot, ResultSnapshotCell cell, String text) {
        int sizeBytes = utf8Length(text);
        cell.setSizeChars((long) text.length());
        cell.setSizeBytes(sizeBytes);
        if (sizeBytes > CELL_SPILL_BYTES) {
            try {
                writeTextFile(snapshot, cell, text);
            } catch (RuntimeException e) {
                // The value cannot be stored (for example the snapshot is at its disk cap). Failing loudly is fine, but
                // the content must not stay pinned in memory where it would break the budget for every later value.
                releaseMemory(snapshot, cell);
                throw e;
            }
            return;
        }
        byte[] records = toRecordBytes(text.getBytes(StandardCharsets.UTF_8));
        cell.setStorage(SnapshotCellStorage.MEMORY);
        cell.setMemoryValue(records);
        // Account the array that is actually retained, so the budget matches the heap and adding and subtracting agree
        snapshot.getMemoryBytes().addAndGet(records.length);
    }

    private void storeBinary(ResultSnapshot snapshot, ResultSnapshotCell cell, byte[] bytes) {
        cell.setSizeBytes((long) bytes.length);
        if (cell.getSizeChars() == null) {
            cell.setSizeChars(null);
        }
        if (bytes.length > CELL_SPILL_BYTES) {
            try {
                writeBinaryFile(snapshot, cell, bytes);
            } catch (RuntimeException e) {
                releaseMemory(snapshot, cell);
                throw e;
            }
            return;
        }
        cell.setStorage(SnapshotCellStorage.MEMORY);
        cell.setMemoryValue(bytes);
        snapshot.getMemoryBytes().addAndGet(bytes.length);
    }

    private String readClob(java.sql.Clob clob) {
        try (java.io.Reader reader = clob.getCharacterStream()) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[COPY_BUFFER_BYTES];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) {
                    text.append(buffer, 0, read);
                }
            }
            return text.toString();
        } catch (IOException | java.sql.SQLException e) {
            throw new BusinessException("largeCellValue.snapshotWriteFailed", new Object[]{e.getMessage()}, e);
        }
    }

    private byte[] readBlob(java.sql.Blob blob) {
        try (InputStream input = blob.getBinaryStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (read > 0) {
                    buffer.write(chunk, 0, read);
                }
            }
            return buffer.toByteArray();
        } catch (IOException | java.sql.SQLException e) {
            throw new BusinessException("largeCellValue.snapshotWriteFailed", new Object[]{e.getMessage()}, e);
        }
    }


    private void writeTextFile(ResultSnapshot snapshot, ResultSnapshotCell cell, String text) {
        writeFile(snapshot, cell, toRecordBytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeBinaryFile(ResultSnapshot snapshot, ResultSnapshotCell cell, byte[] bytes) {
        writeFile(snapshot, cell, bytes);
    }

    /**
     * Writes to a staging file first and then moves it into place, so a crash can never leave a half written cell
     * that a later read would treat as complete.
     */
    /**
     * Splits content into fixed size records.
     * <p>
     * A record is a four byte length followed by up to {@code payloadLimit} content bytes, so the logical byte at
     * index {@code i} always lives in record {@code i / payloadLimit}. Records are plain byte containers: one can end
     * inside a multi byte character, which is fine because readers hand bytes back verbatim and callers decode the
     * concatenation once.
     */
    private byte[] toRecordBytes(byte[] content) {
        int payloadLimit = TEXT_RECORD_SIZE - TEXT_RECORD_HEADER_BYTES;
        int recordCount = Math.max(1, (content.length + payloadLimit - 1) / payloadLimit);
        byte[] records = new byte[recordCount * TEXT_RECORD_SIZE];
        int position = 0;
        for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
            int payloadLength = Math.min(payloadLimit, content.length - position);
            int recordStart = recordIndex * TEXT_RECORD_SIZE;
            records[recordStart] = (byte) (payloadLength >>> 24);
            records[recordStart + 1] = (byte) (payloadLength >>> 16);
            records[recordStart + 2] = (byte) (payloadLength >>> 8);
            records[recordStart + 3] = (byte) payloadLength;
            System.arraycopy(content, position, records, recordStart + TEXT_RECORD_HEADER_BYTES, payloadLength);
            position += payloadLength;
        }
        return records;
    }

    /**
     * Appends the logical bytes {@code [offset, offset + targetBytes)} of a record stream.
     */
    private void appendRecordRange(byte[] records, long offset, long targetBytes, ByteArrayOutputStream output) {
        int payloadLimit = TEXT_RECORD_SIZE - TEXT_RECORD_HEADER_BYTES;
        long recordsCount = records.length / TEXT_RECORD_SIZE;
        long produced = 0L;
        for (long record = 0; record < recordsCount && produced < targetBytes; record++) {
            int recordStart = (int) (record * TEXT_RECORD_SIZE);
            int payloadLength = ((records[recordStart] & 0xFF) << 24) | ((records[recordStart + 1] & 0xFF) << 16)
                    | ((records[recordStart + 2] & 0xFF) << 8) | (records[recordStart + 3] & 0xFF);
            if (payloadLength <= 0 || payloadLength > payloadLimit) {
                break;
            }
            long logicalStart = record * payloadLimit;
            long logicalEnd = logicalStart + payloadLength;
            if (logicalEnd > offset) {
                int from = (int) Math.max(0L, offset - logicalStart);
                int room = (int) Math.min(targetBytes - produced, payloadLength - from);
                output.write(records, recordStart + TEXT_RECORD_HEADER_BYTES + from, room);
                produced += room;
            }
        }
    }

    private void writeFile(ResultSnapshot snapshot, ResultSnapshotCell cell, byte[] bytes) {
        long previousSize = cell.getStorage() == SnapshotCellStorage.FILE ? cell.getSizeBytes() : 0L;
        long afterWrite = snapshot.getDiskBytes().addAndGet(bytes.length - previousSize);
        if (afterWrite > snapshotDiskLimitBytes) {
            snapshot.getDiskBytes().addAndGet(previousSize - bytes.length);
            throw new BusinessException("largeCellValue.snapshotWriteFailed",
                    new Object[]{"snapshot exceeds " + snapshotDiskLimitBytes + " bytes"});
        }
        Path directory = snapshotDirectory(snapshot.getSnapshotId());
        Path file = directory.resolve(fileName(cell));
        Path staging = directory.resolve(fileName(cell) + "." + UUID.randomUUID() + STAGING_FILE_SUFFIX);
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(staging);
            Files.write(staging, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanupFailure) {
                log.warn("Failed to delete staging file {}", staging, cleanupFailure);
            }
            throw new BusinessException("largeCellValue.snapshotWriteFailed", new Object[]{e.getMessage()}, e);
        }
        // Publish the file location before flipping the storage state, and drop the in memory copy last, so a
        // concurrent reader never observes a FILE cell without a path or a MEMORY cell without content.
        cell.setFilePath(file.toString());
        cell.setStorage(SnapshotCellStorage.FILE);
        releaseMemory(snapshot, cell);
    }

    private void releaseMemory(ResultSnapshot snapshot, ResultSnapshotCell cell) {
        Object previous = cell.getMemoryValue();
        if (previous instanceof byte[] bytes) {
            snapshot.getMemoryBytes().addAndGet(-bytes.length);
        }
        cell.setMemoryValue(null);
    }

    private void spillLargestCells(ResultSnapshot snapshot) {
        List<ResultSnapshotCell> inMemory = new ArrayList<>();
        snapshot.getCells().forEach((key, cell) -> {
            if (cell.getStorage() == SnapshotCellStorage.FILE) {
                releaseMemory(snapshot, cell);
                return;
            }
            if (cell.getMemoryValue() != null) {
                inMemory.add(cell);
            }
        });
        inMemory.sort(Comparator.comparingLong(ResultSnapshotCell::getSizeBytes).reversed());
        for (ResultSnapshotCell cell : inMemory) {
            if (snapshot.getMemoryBytes().get() <= SNAPSHOT_MEMORY_BUDGET_BYTES) {
                return;
            }
            Object value = cell.getMemoryValue();
            if (value instanceof byte[] bytes) {
                // The in-memory value is already the exact byte image that belongs in the file (record framed for text,
                // raw for binary), so it is written verbatim.
                try {
                    writeFile(snapshot, cell, bytes);
                } catch (RuntimeException e) {
                    // Keep the budget meaningful even when the disk cap blocks the spill
                    releaseMemory(snapshot, cell);
                    throw e;
                }
            }
        }
    }

    private byte[] readSlice(ResultSnapshotCell cell, long offset, int limit) {
        if (isBinaryCell(cell)) {
            return readBinarySlice(cell, offset, limit);
        }
        return readTextSlice(cell, offset, limit);
    }

    private boolean isBinaryCell(ResultSnapshotCell cell) {
        return cell.getSizeChars() == null;
    }

    private byte[] readBinarySlice(ResultSnapshotCell cell, long offset, int limit) {
        long size = cell.getSizeBytes();
        if (offset >= size) {
            return new byte[0];
        }
        int wanted = (int) Math.min(limit, size - offset);
        if (cell.getStorage() == SnapshotCellStorage.MEMORY) {
            byte[] bytes = (byte[]) cell.getMemoryValue();
            if (bytes == null) {
                // A concurrent flush may have moved this cell to a file between the two reads: fall back to the file
                // instead of reporting a live snapshot as expired.
                return readBinarySlice(cell, offset, limit);
            }
            return java.util.Arrays.copyOfRange(bytes, (int) offset, (int) offset + wanted);
        }
        try (RandomAccessFile file = new RandomAccessFile(cell.getFilePath(), "r")) {
            file.seek(offset);
            byte[] buffer = new byte[wanted];
            int read = file.read(buffer);
            return read == wanted ? buffer : java.util.Arrays.copyOf(buffer, Math.max(read, 0));
        } catch (IOException e) {
            // The raw message contains a server side path, so only the log keeps the detail
            log.warn("Failed to read a result snapshot file", e);
            throw new BusinessException("largeCellValue.snapshotReadFailed", new Object[]{"snapshot file"}, e);
        }
    }

    /**
     * Text is sliced by characters and then encoded, so a slice never splits a multi-byte character. The returned
     * offset is still a byte offset to keep the read contract identical to the previous implementation.
     */
    /**
     * Reads a byte range of text content.
     * <p>
     * Records always end on a character boundary, so a slice taken inside a record is valid UTF-8 and
     * {@code nextOffset} always equals {@code offset + returned length}. Callers therefore receive every byte exactly
     * once and never a broken character.
     */
    private byte[] readTextSlice(ResultSnapshotCell cell, long offset, int limit) {
        long byteSize = cell.getSizeBytes();
        if (offset >= byteSize) {
            return new byte[0];
        }
        // Four bytes is the longest UTF-8 sequence, so a smaller limit could never make progress on such a character
        long targetBytes = Math.min(Math.max(4, limit), byteSize - offset);
        if (cell.getStorage() == SnapshotCellStorage.MEMORY) {
            Object memoryValue = cell.getMemoryValue();
            if (!(memoryValue instanceof byte[] records)) {
                // A concurrent flush may have moved this cell to a file between the two reads: read the file instead of
                // reporting a live snapshot as expired.
                return readFileRecordRange(cell.getFilePath(), offset, targetBytes);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(targetBytes, Integer.MAX_VALUE));
            appendRecordRange(records, offset, targetBytes, output);
            return output.toByteArray();
        }
        return readFileRecordRange(cell.getFilePath(), offset, targetBytes);
    }

    /**
     * Reads the requested window from a record file one record at a time, so a large value is never loaded whole.
     */
    private byte[] readFileRecordRange(String filePath, long offset, long targetBytes) {
        int payloadLimit = TEXT_RECORD_SIZE - TEXT_RECORD_HEADER_BYTES;
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(targetBytes, Integer.MAX_VALUE));
        try (RandomAccessFile file = new RandomAccessFile(filePath, "r")) {
            long records = (file.length() + TEXT_RECORD_SIZE - 1) / TEXT_RECORD_SIZE;
            long produced = 0L;
            for (long record = 0; record < records && produced < targetBytes; record++) {
                long logicalStart = record * payloadLimit;
                if (logicalStart >= offset + targetBytes) {
                    break;
                }
                file.seek(record * TEXT_RECORD_SIZE);
                int payloadLength = file.readInt();
                if (payloadLength <= 0 || payloadLength > payloadLimit) {
                    break;
                }
                long logicalEnd = logicalStart + payloadLength;
                if (logicalEnd <= offset) {
                    continue;
                }
                byte[] payload = new byte[payloadLength];
                file.readFully(payload);
                int from = (int) Math.max(0L, offset - logicalStart);
                int room = (int) Math.min(targetBytes - produced, payloadLength - from);
                output.write(payload, from, room);
                produced += room;
            }
        } catch (IOException e) {
            // The raw message contains a server side path, so only the log keeps the detail
            log.warn("Failed to read a result snapshot file", e);
            throw new BusinessException("largeCellValue.snapshotReadFailed", new Object[]{"snapshot file"}, e);
        }
        return output.toByteArray();
    }



    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private Path snapshotDirectory(String snapshotId) {
        return rootDirectory.resolve(snapshotId);
    }

    private String fileName(ResultSnapshotCell cell) {
        return cell.getRowIndex() + "-" + cell.getColumnIndex() + ".bin";
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete snapshot file {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete snapshot directory {}", directory, e);
        }
    }

    private long directorySize(Path directory) {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
