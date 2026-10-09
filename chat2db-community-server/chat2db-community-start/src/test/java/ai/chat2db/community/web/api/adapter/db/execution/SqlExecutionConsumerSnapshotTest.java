package ai.chat2db.community.web.api.adapter.db.execution;

// Lives in the start module because the event context it needs is package private, while only this module can see
// the web, domain and storage implementations at once.

import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.web.api.model.request.db.SqlEditorExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbExecuteResultEnhanceRequest;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.api.service.db.IDbExecuteResultEnhanceService;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.domain.core.impl.db.DbLargeValueTokenServiceImpl;
import ai.chat2db.community.storage.snapshot.FileResultSnapshotStore;
import ai.chat2db.community.web.api.converter.db.DbWebConverterImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the desktop capture path end to end at the wiring level: the streaming consumer must capture complete large
 * values into a real snapshot, hand the client a readable id, drop the duplicate raw copy, keep row positions absolute
 * across batches, and move content to disk when the execution ends.
 */
class SqlExecutionConsumerSnapshotTest {

    @Test
    void capturesLargeValuesFromRowBatchesAndFlushesOnClose(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(provider(store));
        SqlExecutionConsumer consumer = new SqlExecutionConsumer(request(), new CapturingSink(), new DbWebConverterImpl(),
                tokenService, noopEnhancer(), new SqlExecutionEventContext(), store);

        String text = "流式捕获-".repeat(150_000);
        ResultCell cell = ResultCell.builder()
                .value("流式捕获…")
                .rawValue(text)
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("LONGTEXT")
                .sizeBytes(1L)
                .build();
        List<List<ResultCell>> rows = List.of(List.of(ResultCell.builder().value("1").build(), cell));
        ExecuteResponse result = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .resultSetId(1)
                .headerList(List.of(Header.builder().name("row").build(),
                        Header.builder().name("payload").columnName("payload").build()))
                .dataList(rows)
                .build();

        consumer.rows(result, rows);

        assertNotNull(cell.getLargeValueId(), "the client must receive a readable id");
        assertNull(cell.getRawValue(), "the response must not keep a second copy of the content");
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, cell.getSizeBytes());
        LargeValueToken token = tokenService.requireValid(cell.getLargeValueId());
        assertNotNull(token.getSnapshotId());
        assertEquals(0, token.getRowIndex());
        assertEquals(1, token.getColumnIndex());
        assertEquals("payload", token.getColumnName());
        String assembled = readAll(store, token);
        assertEquals(text.length(), assembled.length(), "the snapshot must serve the complete value");
        assertEquals(text, assembled, "the snapshot must serve the complete value");

        // Row batches of the same result share one snapshot and keep advancing the absolute row offset
        ResultCell second = ResultCell.builder()
                .value("第二批…")
                .rawValue("second batch")
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .build();
        List<List<ResultCell>> secondRows = List.of(List.of(ResultCell.builder().value("2").build(), second));
        ExecuteResponse secondBatch = ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .resultSetId(1)
                .headerList(result.getHeaderList())
                .dataList(secondRows)
                .build();
        consumer.rows(secondBatch, secondRows);

        LargeValueToken secondToken = tokenService.requireValid(second.getLargeValueId());
        assertEquals(token.getSnapshotId(), secondToken.getSnapshotId(), "one snapshot per result");
        assertEquals(1, secondToken.getRowIndex(), "row indexes stay absolute across batches");

        consumer.close();

        Path directory = root.resolve(token.getSnapshotId());
        assertTrue(Files.exists(directory), "close must move the captured content to disk");
        try (var files = Files.list(directory)) {
            assertFalse(files.findAny().isEmpty(), "the flushed snapshot must contain files");
        }
        String afterFlush = readAll(store, token);
        assertEquals(text.length(), afterFlush.length(), "content stays readable after the flush");
        assertEquals(text, afterFlush, "content stays readable after the flush");
    }

    @Test
    void aClientReleaseCanNotFreeASnapshotThatIsStillStreaming(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(provider(store));
        SqlExecutionConsumer consumer = new SqlExecutionConsumer(request(), new CapturingSink(), new DbWebConverterImpl(),
                tokenService, noopEnhancer(), new SqlExecutionEventContext(), store);

        ResultCell first = largeCell("first-batch");
        ExecuteResponse firstResult = batch(1, List.of(List.of(ResultCell.builder().value("1").build(), first)));
        consumer.rows(firstResult, firstResult.getDataList());
        String snapshotId = tokenService.requireValid(first.getLargeValueId()).getSnapshotId();

        // The client hands back the handles it knows while the query is still streaming: the running capture must not
        // lose the snapshot, otherwise every later batch would come back without a readable value.
        tokenService.releaseTokens(List.of(first.getLargeValueId()));
        assertTrue(store.exists(snapshotId), "a release must not free a snapshot the execution still writes to");

        ResultCell second = largeCell("second-batch");
        ExecuteResponse secondResult = batch(1, List.of(List.of(ResultCell.builder().value("2").build(), second)));
        consumer.rows(secondResult, secondResult.getDataList());
        assertNotNull(second.getLargeValueId(), "later batches must still get a usable handle");

        consumer.close();
        assertTrue(store.exists(snapshotId), "the valid handle of the later batch still needs the content");

        // Once the client discards that handle too, the content goes away immediately
        tokenService.releaseTokens(List.of(second.getLargeValueId()));
        assertFalse(store.exists(snapshotId),
                "a snapshot without handles is freed instead of waiting for the snapshot TTL");
    }

    @Test
    void aResultWithoutLargeValuesRegistersNoSnapshot(@TempDir Path tempDirectory) throws IOException {
        Path root = tempDirectory.resolve("result-snapshot");
        FileResultSnapshotStore store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(provider(store));
        SqlExecutionConsumer consumer = new SqlExecutionConsumer(request(), new CapturingSink(), new DbWebConverterImpl(),
                tokenService, noopEnhancer(), new SqlExecutionEventContext(), store);

        ExecuteResponse plain = batch(7, List.of(
                List.of(ResultCell.builder().value("1").build(), ResultCell.builder().value("plain").build()),
                List.of(ResultCell.builder().value("2").build(), ResultCell.builder().value("text").build())));
        consumer.rows(plain, plain.getDataList());
        consumer.close();

        try (var entries = Files.list(root)) {
            assertTrue(entries.filter(Files::isDirectory).findAny().isEmpty(),
                    "a result without a large value must not leave a snapshot behind");
        }
    }

    private static ResultCell largeCell(String content) {
        return ResultCell.builder()
                .value(content + "…")
                .rawValue(content.repeat(20_000))
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("LONGTEXT")
                .sizeBytes(1L)
                .build();
    }

    private static ExecuteResponse batch(int resultSetId, List<List<ResultCell>> rows) {
        return ExecuteResponse.builder()
                .success(Boolean.TRUE)
                .resultSetId(resultSetId)
                .headerList(List.of(Header.builder().name("row").build(),
                        Header.builder().name("payload").columnName("payload").build()))
                .dataList(rows)
                .build();
    }

    /**
     * Snapshot chunks are byte windows, so they must be concatenated and decoded once: a boundary may fall inside a
     * multi-byte character.
     */
    private static String readAll(IResultSnapshotStore store, LargeValueToken token) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        long offset = 0L;
        int guard = 0;
        while (true) {
            SnapshotReadChunk chunk = store.read(token.getSnapshotId(), token.getRowIndex(), token.getColumnIndex(),
                    offset, 256 * 1024);
            byte[] slice = Base64.getDecoder().decode(chunk.getValue());
            bytes.write(slice, 0, slice.length);
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
            assertTrue(++guard < 1000, "chunk loop must terminate");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static SqlExecutionRequest request() {
        return SqlExecutionRequest.builder()
                .requestUuid("test-uuid")
                .sqlEditorRequest(new SqlEditorExecuteRequest())
                .build();
    }

    private static IDbExecuteResultEnhanceService noopEnhancer() {
        return new IDbExecuteResultEnhanceService() {
            @Override
            public void enhance(DbExecuteResultEnhanceRequest dbExecuteResultEnhanceRequest) {
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T instance) {
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

    private static final class CapturingSink implements ISqlExecutionSink {

        private final List<String> events = new ArrayList<>();

        @Override
        public void send(String eventType, Object message, Integer statementSequence, Integer resultSequence) {
            events.add(eventType);
        }
    }
}
