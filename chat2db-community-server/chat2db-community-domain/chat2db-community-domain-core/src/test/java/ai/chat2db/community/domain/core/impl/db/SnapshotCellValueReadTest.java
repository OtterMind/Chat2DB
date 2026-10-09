package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.CellValueChunk;
import ai.chat2db.community.domain.api.model.db.LargeValueReference;
import ai.chat2db.community.domain.api.model.db.LargeValueToken;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueChunkReadRequest;
import ai.chat2db.community.domain.api.model.request.db.DbLargeValueTokensAttachRequest;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.tools.model.Context;
import ai.chat2db.community.tools.model.LoginUser;
import ai.chat2db.community.tools.util.ContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the snapshot backed read path: a large value captured during execution is served without touching the
 * database, including values that have no primary key to re-query.
 */
class SnapshotCellValueReadTest {

    private Path root;

    @AfterEach
    void tearDown() {
        ContextUtils.removeContext();
        if (root != null) {
            deleteRecursively(root);
        }
    }

    @Test
    void servesTextChunksFromTheSnapshotWithoutADatabase(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        String text = "快照读取-".repeat(5_000);
        ResultSnapshot snapshot = fixture.store.register();
        fixture.store.capture(snapshot.getSnapshotId(), 2, 1, text);

        DbCellValueServiceImpl service = new DbCellValueServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        LargeValueReference reference = LargeValueReference.builder()
                .snapshotId(snapshot.getSnapshotId())
                .rowIndex(2)
                .columnIndex(1)
                .valueType("TEXT")
                .columnType("LONGTEXT")
                .build();

        StringBuilder assembled = new StringBuilder();
        long offset = 0L;
        while (true) {
            CellValueChunk chunk = readChunk(service, reference, offset, 4096);
            assembled.append(chunk.getValue());
            assertEquals(offset, chunk.getOffset());
            assertEquals(offset + chunk.getValue().length(), chunk.getNextOffset());
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
        }

        assertEquals(text, assembled.toString());
        assertEquals((long) text.getBytes(StandardCharsets.UTF_8).length, chunkSize(service, reference));
    }

    @Test
    void servesEncodedChunksWithByteOffsets(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        String text = "编码读取-".repeat(3_000);
        ResultSnapshot snapshot = fixture.store.register();
        fixture.store.capture(snapshot.getSnapshotId(), 0, 0, text);
        DbCellValueServiceImpl service = new DbCellValueServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        LargeValueReference reference = LargeValueReference.builder()
                .snapshotId(snapshot.getSnapshotId())
                .rowIndex(0)
                .columnIndex(0)
                .valueType("TEXT")
                .build();

        StringBuilder assembled = new StringBuilder();
        long offset = 0L;
        while (true) {
            CellValueChunk chunk = readChunk(service, reference, offset, 512, "base64");
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            assertEquals(offset, chunk.getOffset(), "byte offsets must be echoed for encoded reads");
            assertEquals(offset + bytes.length, chunk.getNextOffset());
            assembled.append(new String(bytes, StandardCharsets.UTF_8));
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
        }

        assertEquals(text, assembled.toString());
    }

    @Test
    void servesBinaryChunksFromTheSnapshot(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        ResultSnapshot snapshot = fixture.store.register();
        fixture.store.capture(snapshot.getSnapshotId(), 1, 1, payload);
        DbCellValueServiceImpl service = new DbCellValueServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        LargeValueReference reference = LargeValueReference.builder()
                .snapshotId(snapshot.getSnapshotId())
                .rowIndex(1)
                .columnIndex(1)
                .valueType("BINARY")
                .columnType("BLOB")
                .build();

        byte[] assembled = new byte[payload.length];
        int position = 0;
        long offset = 0L;
        while (true) {
            CellValueChunk chunk = readChunk(service, reference, offset, 8192, "hex");
            byte[] bytes = hexToBytes(chunk.getValue());
            System.arraycopy(bytes, 0, assembled, position, bytes.length);
            position += bytes.length;
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
        }

        assertEquals(payload.length, position);
        assertTrue(java.util.Arrays.equals(payload, assembled));
    }

    @Test
    void capturesEveryLargeValueAndDropsTheDuplicateRawCopy(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        setContext(7L, 9L);
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        ResultSnapshot snapshot = fixture.store.register();
        String text = "捕获内容-".repeat(4_000);
        ResultCell cell = ResultCell.builder()
                .value("捕获…")
                .rawValue(text)
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .columnType("LONGTEXT")
                .sizeBytes((long) text.getBytes(StandardCharsets.UTF_8).length)
                .build();

        DbLargeValueTokensAttachRequest attachRequest = new DbLargeValueTokensAttachRequest();
        attachRequest.setHeaders(List.of(Header.builder().name("content").columnName("content").build()));
        attachRequest.setDataList(List.of(List.of(cell)));
        attachRequest.setSnapshotId(snapshot.getSnapshotId());
        attachRequest.setRowOffset(4);
        tokenService.attachTokens(attachRequest);

        assertNotNull(cell.getLargeValueId(), "a snapshot backed cell must get a readable id");
        assertNull(cell.getRawValue(), "the response must not keep a second copy of the content");
        LargeValueToken token = tokenService.requireValid(cell.getLargeValueId());
        assertEquals(snapshot.getSnapshotId(), token.getSnapshotId());
        assertEquals(4, token.getRowIndex());
        assertEquals(0, token.getColumnIndex());
        assertNotNull(token.getSnapshotId());

        CellValueChunk chunk = readChunk(new DbCellValueServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store)),
                LargeValueReference.builder()
                        .snapshotId(token.getSnapshotId())
                        .rowIndex(token.getRowIndex())
                        .columnIndex(token.getColumnIndex())
                        .valueType(token.getValueType())
                        .columnType(token.getColumnType())
                        .build(),
                0L, text.length() + 16);
        assertEquals(text, chunk.getValue());
    }

    @Test
    void issuesReadableIdsEvenWhenTheRowHasNoPrimaryKey(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        setContext(7L, 9L);
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        ResultSnapshot snapshot = fixture.store.register();
        ResultCell cell = ResultCell.builder()
                .value("预览")
                .rawValue("完整内容")
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .build();

        DbLargeValueTokensAttachRequest attachRequest = new DbLargeValueTokensAttachRequest();
        attachRequest.setHeaders(List.of(Header.builder().name("computed").build()));
        attachRequest.setDataList(List.of(List.of(cell)));
        attachRequest.setSnapshotId(snapshot.getSnapshotId());
        tokenService.attachTokens(attachRequest);

        assertNotNull(cell.getLargeValueId());
        assertNull(cell.getUnsupportedReason(), "a snapshot removes the primary key requirement");
    }

    @Test
    void keepsTheCellUnresolvedWhenTheCompleteValueWasNeverMaterialized(@TempDir Path tempDirectory) {
        FileResultSnapshotStoreFixture fixture = fixture(tempDirectory);
        setContext(7L, 9L);
        DbLargeValueTokenServiceImpl tokenService = new DbLargeValueTokenServiceImpl(InMemoryResultSnapshotStore.provider(fixture.store));
        ResultSnapshot snapshot = fixture.store.register();
        ResultCell cell = ResultCell.builder()
                .value("预览…")
                .largeValue(true)
                .truncated(true)
                .valueType("TEXT")
                .build();

        DbLargeValueTokensAttachRequest attachRequest = new DbLargeValueTokensAttachRequest();
        attachRequest.setHeaders(List.of(Header.builder().name("content").build()));
        attachRequest.setDataList(List.of(List.of(cell)));
        attachRequest.setSnapshotId(snapshot.getSnapshotId());
        tokenService.attachTokens(attachRequest);

        assertNull(cell.getLargeValueId(), "no token may point at content that was never captured");
        assertNotNull(cell.getUnsupportedReason());
    }

    private CellValueChunk readChunk(DbCellValueServiceImpl service, LargeValueReference reference, Long offset,
                                     Integer limit) {
        return readChunk(service, reference, offset, limit, null);
    }

    private CellValueChunk readChunk(DbCellValueServiceImpl service, LargeValueReference reference, Long offset,
                                     Integer limit, String format) {
        DbCellValueChunkReadRequest request = new DbCellValueChunkReadRequest();
        request.setReference(reference);
        request.setOffset(offset);
        request.setLimit(limit);
        request.setFormat(format);
        return service.readChunk(request);
    }

    private long chunkSize(DbCellValueServiceImpl service, LargeValueReference reference) {
        return readChunk(service, reference, 0L, 16, "base64").getSizeBytes();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void setContext(Long userId, Long organizationId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        ContextUtils.setContext(Context.builder()
                .loginUser(loginUser)
                .organizationId(organizationId)
                .build());
    }

    private FileResultSnapshotStoreFixture fixture(Path tempDirectory) {
        root = tempDirectory.resolve("result-snapshot");
        InMemoryResultSnapshotStore store = new InMemoryResultSnapshotStore(root);
        return new FileResultSnapshotStoreFixture(root, store);
    }

    private void deleteRecursively(Path directory) {
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
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record FileResultSnapshotStoreFixture(Path root, InMemoryResultSnapshotStore store) {
    }


}
