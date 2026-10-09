package ai.chat2db.community.storage.snapshot;

import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshotCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotCellStorage;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.storage.TestHome;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileResultSnapshotStoreTest {

    private FileResultSnapshotStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDirectory) {
        store = new FileResultSnapshotStore(tempDirectory.resolve("result-snapshot"));
        store.prepareRootDirectory();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(store.getRootDirectory());
    }

    @Test
    void defaultRootDirectoryFollowsTheSharedStorageConvention() {
        TestHome.init();
        FileResultSnapshotStore defaultStore = new FileResultSnapshotStore();
        Path root = defaultStore.getRootDirectory();
        assertTrue(root.endsWith(Path.of("storage", FileResultSnapshotStore.SNAPSHOT_DIRECTORY)),
                "unexpected snapshot root: " + root);
        assertTrue(root.startsWith(Path.of(System.getProperty("user.home"))), "snapshot root must stay inside the state directory: " + root);
    }

    @Test
    void smallTextStaysInMemoryAndRoundTrips() {
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 3, 4, "hello 世界");

        SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 3, 4, 0, 1024);

        assertEquals("hello 世界", decode(chunk));
        assertEquals(0L, chunk.getOffset());
        assertEquals("hello 世界".getBytes(StandardCharsets.UTF_8).length, chunk.getNextOffset());
        assertTrue(chunk.isEof());
        assertEquals(chunk.getNextOffset(), chunk.getSizeBytes());
        assertEquals(8L, chunk.getSizeChars());
        assertEquals(SnapshotCellStorage.MEMORY,
                snapshot.getCells().get(ResultSnapshot.cellKey(3, 4)).getStorage());
    }

    @Test
    void readingBeyondTheContentReturnsAnEmptyEofChunk() {
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 1, "abc");

        SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 0, 1, 10, 16);

        assertEquals("", decode(chunk));
        assertEquals(10L, chunk.getOffset());
        assertEquals(10L, chunk.getNextOffset());
        assertTrue(chunk.isEof());
    }

    @Test
    void textSlicesKeepByteOffsetsStableAcrossChunks() {
        ResultSnapshot snapshot = store.register();
        String text = "中文内容-".repeat(2000);
        store.capture(snapshot.getSnapshotId(), 1, 1, text);

        long offset = 0L;
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        int chunks = 0;
        while (true) {
            SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 1, 1, offset, 256);
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            assertEquals(offset, chunk.getOffset(), "chunk offset must echo the requested offset");
            assertEquals(offset + bytes.length, chunk.getNextOffset(), "nextOffset must equal offset + bytes");
            assertTrue(bytes.length <= 256 + 3, "chunk must not exceed the requested limit by a character");
            assertTrue(bytes.length > 0, "a non-final chunk must make progress");
            assembled.write(bytes, 0, bytes.length);
            offset = chunk.getNextOffset();
            chunks++;
            if (chunk.isEof()) {
                break;
            }
            assertTrue(chunks < 500, "chunk loop did not terminate");
        }

        // Callers concatenate the byte stream and decode it once, which is what the client copy flow does
        String assembledText = assembled.toString(StandardCharsets.UTF_8);
        assertEquals(text.length(), assembledText.length(), "assembled char count must match the source text");
        assertEquals(text, assembledText, "assembled content must match the source text");
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, offset);
        assertTrue(chunks > 1, "expected more than one chunk");
    }

    @Test
    void contentAboveTheCellLimitIsWrittenToAFile() throws IOException {
        ResultSnapshot snapshot = store.register();
        byte[] big = new byte[(int) FileResultSnapshotStore.CELL_SPILL_BYTES + 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        store.capture(snapshot.getSnapshotId(), 7, 3, big);

        ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(7, 3));
        assertEquals(SnapshotCellStorage.FILE, cell.getStorage());
        assertNotNull(cell.getFilePath());
        assertTrue(Files.exists(Path.of(cell.getFilePath())), "spilled file must exist");
        assertEquals(big.length, Files.size(Path.of(cell.getFilePath())));
        assertEquals(0L, snapshot.getMemoryBytes().get(), "spilled content must not stay accounted in memory");

        SnapshotReadChunk head = store.read(snapshot.getSnapshotId(), 7, 3, 0, 32);
        assertArrayEquals(java.util.Arrays.copyOf(big, 32), Base64.getDecoder().decode(head.getValue()));
        assertFalse(head.isEof());

        long tailOffset = big.length - 10;
        SnapshotReadChunk tail = store.read(snapshot.getSnapshotId(), 7, 3, tailOffset, 64);
        assertArrayEquals(java.util.Arrays.copyOfRange(big, (int) tailOffset, big.length),
                Base64.getDecoder().decode(tail.getValue()));
        assertTrue(tail.isEof());
        assertEquals(big.length, tail.getNextOffset());
        assertEquals(big.length, tail.getSizeBytes());
    }

    @Test
    void driverClobIsStreamedIntoTheSnapshot(@TempDir Path tempDirectory) throws Exception {
        FileResultSnapshotStore localStore = new FileResultSnapshotStore(tempDirectory.resolve("clob-snapshot"));
        localStore.prepareRootDirectory();
        ResultSnapshot snapshot = localStore.register();
        byte[] payload = "streamed-text-".repeat(2000).getBytes(StandardCharsets.UTF_8);
        java.sql.Clob clob = new StringClob(new String(payload, StandardCharsets.UTF_8));

        ResultSnapshotCell cell = localStore.capture(snapshot.getSnapshotId(), 2, 2, clob);

        assertEquals(payload.length, cell.getSizeBytes());
        assertArrayEquals(payload, Base64.getDecoder().decode(
                localStore.read(snapshot.getSnapshotId(), 2, 2, 0, payload.length + 8).getValue()));

        // A Clob that already exceeds the spill limit goes straight to a file
        byte[] largePayload = new byte[(int) FileResultSnapshotStore.CELL_SPILL_BYTES + 4096];
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 97);
        }
        ResultSnapshot spilledSnapshot = localStore.register();
        ResultSnapshotCell spilled = localStore.capture(spilledSnapshot.getSnapshotId(), 0, 0,
                new StringClob(new String(largePayload, StandardCharsets.ISO_8859_1)));

        assertEquals(SnapshotCellStorage.FILE, spilled.getStorage());
        assertArrayEquals(largePayload, Base64.getDecoder().decode(
                localStore.read(spilledSnapshot.getSnapshotId(), 0, 0, 0, largePayload.length + 8).getValue()));
    }

    @Test
    void exceedingTheMemoryBudgetSpillsTheLargestCellsFirst() {
        ResultSnapshot snapshot = store.register();
        int cellSize = 512 * 1024;
        int cellCount = 80;
        for (int i = 0; i < cellCount; i++) {
            byte[] value = new byte[cellSize];
            value[0] = (byte) i;
            store.capture(snapshot.getSnapshotId(), i, 3, value);
        }

        long memory = snapshot.getMemoryBytes().get();
        List<ResultSnapshotCell> spilled = new ArrayList<>();
        snapshot.getCells().forEach((key, cell) -> {
            if (cell.getStorage() == SnapshotCellStorage.FILE) {
                spilled.add(cell);
            }
        });

        assertTrue(memory <= FileResultSnapshotStore.SNAPSHOT_MEMORY_BUDGET_BYTES,
                "in-memory footprint must stay within the budget, was " + memory);
        assertFalse(spilled.isEmpty(), "expected some cells to be spilled");
        for (ResultSnapshotCell cell : spilled) {
            assertTrue(Files.exists(Path.of(cell.getFilePath())), "spilled cell must have a file: " + cell.getFilePath());
        }
        SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), spilled.get(0).getRowIndex(), 3, 0, 4);
        assertEquals(4, Base64.getDecoder().decode(chunk.getValue()).length);
    }

    @Test
    void spilledTextIsReassembledFromCharacterAlignedChunks() {
        ResultSnapshot snapshot = store.register();
        String text = "跨块中文-".repeat(200_000);
        store.capture(snapshot.getSnapshotId(), 3, 3, text);
        ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(3, 3));
        assertEquals(SnapshotCellStorage.FILE, cell.getStorage(), "large text must be spilled");
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, cell.getSizeBytes());

        long offset = 0L;
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        int chunks = 0;
        while (true) {
            SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 3, 3, offset, 4096);
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            assertEquals(offset, chunk.getOffset(), "chunk offset must echo the requested offset");
            assertEquals(offset + bytes.length, chunk.getNextOffset(), "nextOffset must equal offset + bytes");
            assertTrue(bytes.length <= 4096 + 3, "chunk must not exceed the requested limit by a character");
            assertTrue(bytes.length > 0 || chunk.isEof(), "a non-final chunk must make progress");
            assembled.write(bytes, 0, bytes.length);
            offset = chunk.getNextOffset();
            chunks++;
            if (chunk.isEof()) {
                break;
            }
            assertTrue(chunks < 5000, "chunk loop did not terminate");
        }

        String assembledText = assembled.toString(StandardCharsets.UTF_8);
        assertEquals(text.length(), assembledText.length(), "assembled char count must match the source text");
        assertEquals(text, assembledText, "assembled content must match the source text");
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, offset);
    }

    @Test
    void releaseDeletesFilesAndMakesTheSnapshotUnknown() throws IOException {
        ResultSnapshot snapshot = store.register();
        byte[] big = new byte[(int) FileResultSnapshotStore.CELL_SPILL_BYTES + 10];
        store.capture(snapshot.getSnapshotId(), 1, 1, big);
        Path directory = Path.of(snapshot.getCells().get(ResultSnapshot.cellKey(1, 1)).getFilePath()).getParent();
        assertTrue(Files.exists(directory));

        store.release(snapshot.getSnapshotId());

        assertFalse(store.exists(snapshot.getSnapshotId()));
        assertFalse(Files.exists(directory), "snapshot directory must be deleted");
        assertThrows(BusinessException.class, () -> store.read(snapshot.getSnapshotId(), 1, 1, 0, 16));
    }

    @Test
    void expiredSnapshotsAreEvictedAndUnknownSnapshotsAreRejected() {
        ResultSnapshot expired = store.register();
        store.capture(expired.getSnapshotId(), 0, 0, "x");
        ResultSnapshot kept = store.register();
        // Expire only one snapshot explicitly instead of relying on millisecond timing between registrations
        expired.setExpiresAt(Instant.now().minusSeconds(1));

        store.evictExpired(Instant.now());

        assertFalse(store.exists(expired.getSnapshotId()));
        assertTrue(store.exists(kept.getSnapshotId()));
        assertThrows(BusinessException.class, () -> store.read("missing-snapshot", 0, 0, 0, 16));
        assertThrows(BusinessException.class, () -> store.capture("missing-snapshot", 0, 0, "x"));
    }

    @Test
    void concurrentReadersOfTheSameCellGetConsistentSlices() throws Exception {
        ResultSnapshot snapshot = store.register();
        String text = "并发读取-".repeat(5000);
        store.capture(snapshot.getSnapshotId(), 0, 0, text);
        long size = text.getBytes(StandardCharsets.UTF_8).length;

        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger completed = new AtomicInteger();
        try {
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        long offset = 0L;
                        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
                        while (true) {
                            SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 0, 0, offset, 512);
                            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
                            assembled.write(bytes, 0, bytes.length);
                            offset = chunk.getNextOffset();
                            if (chunk.isEof()) {
                                break;
                            }
                        }
                        if (!text.equals(assembled.toString(StandardCharsets.UTF_8)) || offset != size) {
                            throw new IllegalStateException("reader assembled unexpected content");
                        }
                        completed.incrementAndGet();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "readers did not finish");
        } finally {
            pool.shutdownNow();
        }

        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(readers, completed.get());
    }

    @Test
    void chunkInvariantsHoldForTextOnEveryPath() {
        ResultSnapshot snapshot = store.register();
        String smallText = "中文内容-".repeat(30);
        store.capture(snapshot.getSnapshotId(), 0, 0, smallText);
        String largeText = "跨块中文-".repeat(200_000);
        store.capture(snapshot.getSnapshotId(), 1, 0, largeText);
        assertEquals(SnapshotCellStorage.MEMORY,
                snapshot.getCells().get(ResultSnapshot.cellKey(0, 0)).getStorage());
        assertEquals(SnapshotCellStorage.FILE,
                snapshot.getCells().get(ResultSnapshot.cellKey(1, 0)).getStorage());

        for (int row : new int[]{0, 1}) {
            String text = row == 0 ? smallText : largeText;
            long size = text.getBytes(StandardCharsets.UTF_8).length;
            for (int limit : new int[]{33, 256, 4096, 262140}) {
                long offset = 0L;
                ByteArrayOutputStream assembled = new ByteArrayOutputStream();
                int guard = 0;
                while (offset < size) {
                    SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), row, 0, offset, limit);
                    byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
                    String detail = "row=" + row + " limit=" + limit + " offset=" + offset
                            + " returned=" + bytes.length + " next=" + chunk.getNextOffset()
                            + " eof=" + chunk.isEof();
                    assertEquals(offset, chunk.getOffset(), "offset echo: " + detail);
                    assertEquals(offset + bytes.length, chunk.getNextOffset(), "nextOffset: " + detail);
                    // A slice may carry up to three extra bytes of the character that crosses the limit
                    assertTrue(bytes.length <= limit + 3, "limit respected: " + detail);
                    assertTrue(bytes.length > 0, "progress: " + detail);
                    assembled.write(bytes, 0, bytes.length);
                    offset = chunk.getNextOffset();
                    assertTrue(++guard < 1_000_000, "loop guard: " + detail);
                }
                assertEquals(size, offset, "total bytes: row=" + row + " limit=" + limit);
                String assembledText = assembled.toString(StandardCharsets.UTF_8);
                assertEquals(text.length(), assembledText.length(),
                        "char count: row=" + row + " limit=" + limit);
                assertEquals(text, assembledText, "content: row=" + row + " limit=" + limit);
            }
        }
    }

    @Test
    void writesGoThroughAStagingFileSoNoPartialCellIsEverVisible() throws IOException {
        ResultSnapshot snapshot = store.register();
        String text = "原子写入-".repeat(200_000);
        store.capture(snapshot.getSnapshotId(), 5, 2, text);

        ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(5, 2));
        assertEquals(SnapshotCellStorage.FILE, cell.getStorage());
        Path directory = Path.of(cell.getFilePath()).getParent();
        try (var files = Files.list(directory)) {
            List<String> names = files.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(List.of("5-2.bin"), names, "no staging file may remain after a successful write");
        }
        // The file stores padded records, so sizeBytes is the logical size and the content is verified by reading it
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, cell.getSizeBytes());
        assertTrue(Files.size(Path.of(cell.getFilePath())) >= cell.getSizeBytes());
    }

    @Test
    void startupRemovesStaleSnapshotDirectoriesAndStagingFiles(@TempDir Path tempDirectory) throws Exception {
        Path root = tempDirectory.resolve("result-snapshot");
        Path staleSnapshot = root.resolve("stale-snapshot");
        Files.createDirectories(staleSnapshot);
        Path staleCell = staleSnapshot.resolve("0-0.bin");
        Files.write(staleCell, "left over".getBytes(StandardCharsets.UTF_8));
        Path staleStaging = staleSnapshot.resolve("1-1.bin.part");
        Files.write(staleStaging, "half written".getBytes(StandardCharsets.UTF_8));
        Path freshSnapshot = root.resolve("fresh-snapshot");
        Files.createDirectories(freshSnapshot);
        Path freshCell = freshSnapshot.resolve("0-0.bin");
        Files.write(freshCell, "recent".getBytes(StandardCharsets.UTF_8));

        long staleMillis = Instant.now().minus(FileResultSnapshotStore.SNAPSHOT_TTL).minusSeconds(120).toEpochMilli();
        Files.setLastModifiedTime(staleSnapshot, FileTime.fromMillis(staleMillis));
        Files.setLastModifiedTime(staleCell, FileTime.fromMillis(staleMillis));
        Files.setLastModifiedTime(staleStaging, FileTime.fromMillis(staleMillis));

        FileResultSnapshotStore restarted = new FileResultSnapshotStore(root);
        restarted.prepareRootDirectory();

        assertFalse(Files.exists(staleSnapshot), "stale snapshot directory must be removed on startup");
        assertTrue(Files.exists(freshSnapshot), "recent snapshot directory must be kept");
        assertTrue(Files.exists(freshCell));
        assertFalse(restarted.exists("stale-snapshot"));
    }

    @Test
    void indexedLeftoversAreRemovedOnStartupWithoutWaitingForTheTtl(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore first = new FileResultSnapshotStore(root);
        first.prepareRootDirectory();
        ResultSnapshot dropped = first.register();
        ResultSnapshot kept = first.register();
        String text = "leftover-".repeat(200_000);
        first.capture(dropped.getSnapshotId(), 0, 0, text);
        first.capture(kept.getSnapshotId(), 0, 0, text);
        Path droppedDirectory = root.resolve(dropped.getSnapshotId());
        Path keptDirectory = root.resolve(kept.getSnapshotId());
        assertTrue(Files.exists(droppedDirectory));
        assertTrue(Files.exists(keptDirectory));
        Path ownIndex = root.resolve("index-" + ProcessHandle.current().pid() + ".json");
        assertTrue(Files.readString(ownIndex).contains(dropped.getSnapshotId()),
                "index must list the snapshots owned by this process");

        // Simulate a crash followed by a restart in a NEW process: the index that survives belongs to a pid that is
        // gone, which is exactly what the startup cleanup keys on
        long deadPid = 999_999L;
        Files.delete(ownIndex);
        Files.writeString(root.resolve("index-" + deadPid + ".json"),
                "[\"" + dropped.getSnapshotId() + "\",\"" + kept.getSnapshotId() + "\"]");

        FileResultSnapshotStore restarted = new FileResultSnapshotStore(root);
        restarted.prepareRootDirectory();

        assertFalse(Files.exists(droppedDirectory), "indexed leftover must be removed immediately");
        assertFalse(Files.exists(keptDirectory), "indexed leftover must be removed immediately");
        assertFalse(Files.exists(root.resolve("index-" + deadPid + ".json")),
                "the index of a dead process must be deleted with its snapshots");
    }

    @Test
    void anIndexOwnedByALivingProcessIsLeftAlone(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        Files.createDirectories(root);
        String foreignSnapshot = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
        Path foreignDirectory = root.resolve(foreignSnapshot);
        Files.createDirectories(foreignDirectory);
        Files.writeString(foreignDirectory.resolve("0-0.bin"), "another process is using this");
        // The owner process is this very test JVM, so it counts as alive
        Files.writeString(root.resolve("index-" + ProcessHandle.current().pid() + ".json"),
                "[\"" + foreignSnapshot + "\"]");

        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();

        assertTrue(Files.exists(foreignDirectory), "a live process's snapshots must never be deleted");
        assertTrue(Files.exists(foreignDirectory.resolve("0-0.bin")));
    }

    @Test
    void aDamagedIndexCannotDeleteOutsideTheSnapshotDirectory(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        Files.createDirectories(root);
        Path outside = tempDirectory.resolve("precious");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Files.writeString(root.resolve("index.json"), "[\"../precious\", \"/tmp/definitely-not-a-snapshot\"]");

        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();

        assertTrue(Files.exists(outside.resolve("keep.txt")), "cleanup must ignore ids that are not snapshot ids");
    }

    @Test
    void missingIndexFallsBackToTheTtlSweep(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        Path orphan = root.resolve("orphan-without-index");
        Files.createDirectories(orphan);
        Files.write(orphan.resolve("0-0.bin"), "data".getBytes(StandardCharsets.UTF_8));
        long staleMillis = Instant.now().minus(FileResultSnapshotStore.SNAPSHOT_TTL).minusSeconds(60).toEpochMilli();
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(staleMillis));

        FileResultSnapshotStore restarted = new FileResultSnapshotStore(root);
        restarted.prepareRootDirectory();

        assertFalse(Files.exists(orphan), "without an index only directories older than the TTL are removed");
    }

    @Test
    void damagedIndexDoesNotBreakStartup(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.json"), "{not-json");
        Path orphan = root.resolve("orphan");
        Files.createDirectories(orphan);
        Files.write(orphan.resolve("0-0.bin"), "data".getBytes(StandardCharsets.UTF_8));
        long staleMillis = Instant.now().minus(FileResultSnapshotStore.SNAPSHOT_TTL).minusSeconds(60).toEpochMilli();
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(staleMillis));

        FileResultSnapshotStore restarted = new FileResultSnapshotStore(root);
        restarted.prepareRootDirectory();

        assertFalse(Files.exists(orphan), "a damaged index must fall back to the TTL sweep");
    }

    @Test
    void cleanShutdownReleasesSnapshotsAndTheIndex(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore first = new FileResultSnapshotStore(root);
        first.prepareRootDirectory();
        ResultSnapshot snapshot = first.register();
        String text = "shutdown-".repeat(200_000);
        first.capture(snapshot.getSnapshotId(), 0, 0, text);
        Path directory = root.resolve(snapshot.getSnapshotId());
        assertTrue(Files.exists(directory));

        first.releaseAllOnShutdown();

        assertFalse(Files.exists(directory), "clean shutdown must delete snapshot files");
        assertFalse(Files.exists(root.resolve("index.json")), "clean shutdown must delete the index");
        assertTrue(first.exists(snapshot.getSnapshotId()) == false);
        assertFalse(first.exists(snapshot.getSnapshotId()));
    }

    @Test
    void flushToDiskKeepsContentReadableWhileReleasingHeap() {
        ResultSnapshot snapshot = store.register();
        String first = "内存内容-" .repeat(1000);
        String second = "another-".repeat(5000);
        store.capture(snapshot.getSnapshotId(), 0, 0, first);
        store.capture(snapshot.getSnapshotId(), 1, 0, second);
        assertTrue(snapshot.getMemoryBytes().get() > 0L, "cells are expected to start in memory");

        store.flushToDisk(snapshot.getSnapshotId());

        assertEquals(0L, snapshot.getMemoryBytes().get(), "flush must release the in-memory accounting");
        snapshot.getCells().forEach((key, cell) -> assertEquals(SnapshotCellStorage.FILE, cell.getStorage()));

        SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), 0, 0, 0, 64 * 1024);
        assertEquals(first, new String(Base64.getDecoder().decode(chunk.getValue()), StandardCharsets.UTF_8));
        SnapshotReadChunk other = store.read(snapshot.getSnapshotId(), 1, 0, 0, 64 * 1024);
        assertEquals(second, new String(Base64.getDecoder().decode(other.getValue()), StandardCharsets.UTF_8));
        assertTrue(store.exists(snapshot.getSnapshotId()));
    }

    @Test
    void reassemblesBytesExactlyOnBothPathsForEveryChunkSize() {
        ResultSnapshot snapshot = store.register();
        // Mix of ASCII, three byte and four byte characters, plus enough content to spill to a file
        String text = "跨块中文-abc-\uD83D\uDE00-".repeat(40_000);
        store.capture(snapshot.getSnapshotId(), 0, 0, text);
        store.capture(snapshot.getSnapshotId(), 1, 0, text);
        byte[] expected = text.getBytes(StandardCharsets.UTF_8);

        for (int row : new int[]{0, 1}) {
            for (int limit : new int[]{4, 33, 4096, 262140}) {
                long offset = 0L;
                ByteArrayOutputStream assembled = new ByteArrayOutputStream();
                int guard = 0;
                while (offset < expected.length) {
                    SnapshotReadChunk chunk = store.read(snapshot.getSnapshotId(), row, 0, offset, limit);
                    byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
                    String detail = "row=" + row + " limit=" + limit + " offset=" + offset;
                    assertEquals(offset, chunk.getOffset(), "offset echo: " + detail);
                    assertEquals(offset + bytes.length, chunk.getNextOffset(), "nextOffset: " + detail);
                    assertTrue(bytes.length > 0, "progress: " + detail);
                    assertTrue(bytes.length <= Math.max(4, limit), "limit respected: " + detail);
                    assembled.write(bytes, 0, bytes.length);
                    offset = chunk.getNextOffset();
                    assertTrue(++guard < 400_000, "loop guard: " + detail);
                }
                byte[] actual = assembled.toByteArray();
                assertEquals(expected.length, actual.length, "byte count: row=" + row + " limit=" + limit);
                assertArrayEquals(expected, actual, "bytes must reassemble exactly: row=" + row + " limit=" + limit);
                assertEquals(text, new String(actual, StandardCharsets.UTF_8),
                        "text must decode exactly: row=" + row + " limit=" + limit);
            }
        }
    }

    @Test
    void textSpilledByTheMemoryBudgetStillReadsBackExactly() {
        ResultSnapshot snapshot = store.register();
        String text = "预算削减-".repeat(6000);
        int cells = 700;
        for (int i = 0; i < cells; i++) {
            store.capture(snapshot.getSnapshotId(), i, 0, text);
        }

        int checked = 0;
        for (int i = 0; i < cells; i++) {
            ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(i, 0));
            if (cell.getStorage() != SnapshotCellStorage.FILE) {
                continue;
            }
            checked++;
            assertEquals(text, readWholeText(snapshot.getSnapshotId(), i, 0),
                    "a cell spilled by the budget must keep its exact content");
        }
        assertTrue(checked > 0, "the budget must have pushed at least one cell to disk");
        assertTrue(snapshot.getMemoryBytes().get() <= FileResultSnapshotStore.SNAPSHOT_MEMORY_BUDGET_BYTES);
    }

    @Test
    void contentWhoseCharacterStraddlesARecordBoundaryIsReadable() {
        // 256 KiB records hold 262140 payload bytes; these lengths put a multi byte character on the seam
        for (int filler : new int[]{262139, 262137, 262140, 524279}) {
            ResultSnapshot snapshot = store.register();
            String text = "a".repeat(filler) + "\uD83D\uDE00" + "尾";
            store.capture(snapshot.getSnapshotId(), 0, 0, text);
            assertEquals(text, readWholeText(snapshot.getSnapshotId(), 0, 0),
                    "content with a character on the record seam must round trip, filler=" + filler);

            ResultSnapshot wide = store.register();
            String wideText = "中".repeat(87_381) + "文";
            store.capture(wide.getSnapshotId(), 0, 0, wideText);
            assertEquals(wideText, readWholeText(wide.getSnapshotId(), 0, 0),
                    "wide characters on the seam must round trip");
        }
    }

    @Test
    void readersSeeIntactContentWhileTheCellIsBeingFlushedToDisk() throws Exception {
        ResultSnapshot snapshot = store.register();
        String text = "并发校验-".repeat(40_000);
        store.capture(snapshot.getSnapshotId(), 0, 0, text);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean reading = new AtomicBoolean(true);
        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread reader = new Thread(() -> {
                while (reading.get()) {
                    try {
                        assertEquals(text, readWholeText(snapshot.getSnapshotId(), 0, 0));
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        return;
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            readers.add(reader);
        }

        store.flushToDisk(snapshot.getSnapshotId());
        reading.set(false);
        for (Thread reader : readers) {
            reader.join(5_000L);
        }
        assertNull(failure.get(), "a reader must never observe a half published cell: " + failure.get());
        assertEquals(text, readWholeText(snapshot.getSnapshotId(), 0, 0));
    }

    @Test
    void diskAccountingFollowsWhatWasWritten() {
        ResultSnapshot snapshot = store.register();
        assertEquals(0L, snapshot.getDiskBytes().get());
        store.capture(snapshot.getSnapshotId(), 0, 0, "落盘记账-".repeat(200_000));
        long afterCapture = snapshot.getDiskBytes().get();
        assertTrue(afterCapture > FileResultSnapshotStore.CELL_SPILL_BYTES,
                "a big value must be written to disk and counted, was " + afterCapture);

        ResultSnapshotCell cell = snapshot.getCells().get(ResultSnapshot.cellKey(0, 0));
        assertEquals(SnapshotCellStorage.FILE, cell.getStorage());
        assertEquals(afterCapture, snapshot.getDiskBytes().get(), "flushing an already stored cell must not double count");
    }

    @Test
    void aSnapshotCanNotGrowPastItsOwnDiskLimit(@TempDir Path tempDirectory) {
        long cap = 2L * 1024 * 1024;
        FileResultSnapshotStore limited = new FileResultSnapshotStore(tempDirectory.resolve("limited"), cap,
                100L * 1024 * 1024);
        limited.prepareRootDirectory();
        ResultSnapshot snapshot = limited.register();
        // The first cell fits under the cap, the second one would push the snapshot past it
        limited.capture(snapshot.getSnapshotId(), 0, 0, "上限校验-".repeat(90_000));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> limited.capture(snapshot.getSnapshotId(), 1, 0, "上限校验-".repeat(90_000)));
        assertEquals("largeCellValue.snapshotWriteFailed", failure.getCode());
        assertTrue(snapshot.getDiskBytes().get() <= cap,
                "the snapshot must stop at its cap instead of growing past it");
    }

    @Test
    void evictionSkipsSnapshotsThatAreStillInUse(@TempDir Path tempDirectory) {
        // Both cells fit the per snapshot cap, while the shared total forces an eviction
        FileResultSnapshotStore limited = new FileResultSnapshotStore(tempDirectory.resolve("evict"),
                16L * 1024 * 1024, 2L * 1024 * 1024);
        limited.prepareRootDirectory();
        ResultSnapshot active = limited.register();
        limited.capture(active.getSnapshotId(), 0, 0, "活跃快照-".repeat(90_000));
        ResultSnapshot idle = limited.register();
        limited.capture(idle.getSnapshotId(), 0, 0, "空闲快照-".repeat(90_000));
        // The idle one has not been touched for two minutes, the active one was just written to
        idle.getLastActivityAt().set(System.currentTimeMillis() - 120_000L);
        long activeBytes = active.getDiskBytes().get();
        assertTrue(activeBytes > 0 && idle.getDiskBytes().get() > 0);

        limited.evictOverCapacity();

        assertTrue(limited.exists(active.getSnapshotId()),
                "capacity eviction must not free a snapshot that is still being used");
        assertFalse(limited.exists(idle.getSnapshotId()), "an idle snapshot is the right thing to evict");
    }

    @Test
    void aReleaseDuringAnOpenReadIsDeferredInsteadOfTruncatingIt() throws Exception {
        ResultSnapshot snapshot = store.register();
        byte[] content = new byte[600_000];
        new java.util.Random(7L).nextBytes(content);
        store.capture(snapshot.getSnapshotId(), 0, 0, content);

        store.hold(snapshot.getSnapshotId());
        SnapshotReadChunk first = store.read(snapshot.getSnapshotId(), 0, 0, 0L, 4096);
        assertEquals(4096, Base64.getDecoder().decode(first.getValue()).length);

        // A release arrives while the reader is still consuming the value
        store.release(snapshot.getSnapshotId());
        assertTrue(store.exists(snapshot.getSnapshotId()), "an open read must not lose its content");

        byte[] tail = Base64.getDecoder().decode(
                store.read(snapshot.getSnapshotId(), 0, 0, 4096L, 8192).getValue());
        assertEquals(8192, tail.length, "the reader can keep going after the release");

        store.unhold(snapshot.getSnapshotId());
        assertFalse(store.exists(snapshot.getSnapshotId()),
                "the deferred release happens as soon as the last lease is gone");
    }

    @Test
    void evictExpiredKeepsSnapshotsThatAreStillInUse(@TempDir Path tempDirectory) {
        ResultSnapshot leased = store.register();
        store.capture(leased.getSnapshotId(), 0, 0, "仍在使用-".repeat(50_000));
        store.hold(leased.getSnapshotId());
        leased.setExpiresAt(Instant.now().minusSeconds(60));

        store.evictExpired(Instant.now());
        assertTrue(store.exists(leased.getSnapshotId()),
                "a snapshot a reader still holds must survive the sweep");
        assertTrue(leased.getExpiresAt().isAfter(Instant.now()), "and it gets a fresh window");

        store.unhold(leased.getSnapshotId());
        // The sweep pushed the window forward, so the next one has to look past that new deadline
        store.evictExpired(leased.getExpiresAt().plusSeconds(1));
        assertFalse(store.exists(leased.getSnapshotId()), "without a lease the sweep removes it");
    }

    private String readWholeText(String snapshotId, int row, int column) {
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        long offset = 0L;
        int guard = 0;
        while (true) {
            SnapshotReadChunk chunk = store.read(snapshotId, row, column, offset, 256 * 1024);
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            assembled.write(bytes, 0, bytes.length);
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
            assertTrue(++guard < 100_000, "chunk loop must terminate");
        }
        return assembled.toString(StandardCharsets.UTF_8);
    }

    private String decode(SnapshotReadChunk chunk) {
        return new String(Base64.getDecoder().decode(chunk.getValue()), StandardCharsets.UTF_8);
    }

    private void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    /**
     * Minimal {@link java.sql.Clob} so the driver type path is covered without a database.
     */
    private static final class StringClob implements java.sql.Clob {

        private final String value;

        private StringClob(String value) {
            this.value = value;
        }

        @Override
        public long length() {
            return value.length();
        }

        @Override
        public String getSubString(long pos, int length) {
            return value.substring((int) pos - 1, (int) pos - 1 + length);
        }

        @Override
        public java.io.Reader getCharacterStream() {
            return new java.io.StringReader(value);
        }

        @Override
        public java.io.InputStream getAsciiStream() {
            return new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public long position(String searchstr, long start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position(java.sql.Clob searchstr, long start) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int setString(long pos, String str) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int setString(long pos, String str, int offset, int len) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.OutputStream setAsciiStream(long pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.Writer setCharacterStream(long pos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void truncate(long len) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void free() {
        }

        @Override
        public java.io.Reader getCharacterStream(long pos, long length) {
            throw new UnsupportedOperationException();
        }
    }
}
