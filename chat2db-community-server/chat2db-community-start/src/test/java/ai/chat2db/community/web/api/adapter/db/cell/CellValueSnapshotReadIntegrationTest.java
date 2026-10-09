package ai.chat2db.community.web.api.adapter.db.cell;

// Lives in the start module because only this module sees the domain implementation and the real snapshot store at once.

import ai.chat2db.community.domain.api.model.db.CellValueChunk;
import ai.chat2db.community.domain.api.model.db.CellValueDownload;
import ai.chat2db.community.domain.api.model.db.LargeValueReference;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueChunkReadRequest;
import ai.chat2db.community.domain.api.model.result.snapshot.ResultSnapshot;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.core.impl.db.DbCellValueServiceImpl;
import ai.chat2db.community.storage.snapshot.FileResultSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serves large values through the real snapshot store and checks the properties the shipped clients rely on: byte exact
 * chunks, valid base64 downloads, reported encodings and the sniffed display mode of binary content.
 */
class CellValueSnapshotReadIntegrationTest {

    private Path root;

    private FileResultSnapshotStore store;

    private DbCellValueServiceImpl service;

    @BeforeEach
    void setUp(@TempDir Path tempDirectory) {
        root = tempDirectory.resolve("result-snapshot");
        store = new FileResultSnapshotStore(root);
        store.prepareRootDirectory();
        service = new DbCellValueServiceImpl(provider(store));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root != null && Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort cleanup of a temp directory
                    }
                });
            }
        }
    }

    @Test
    void chunkedTextReadsCrossingRecordBoundariesStayByteExact() {
        String text = "跨记录边界读取-".repeat(40_000);
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, text);
        LargeValueReference reference = reference(snapshot, "TEXT", "LONGTEXT");

        // Chunks are exact byte windows, so a character can straddle two of them: the bytes are concatenated first and
        // decoded once, which is the contract the shipped clients follow.
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        long offset = 0L;
        int guard = 0;
        while (true) {
            CellValueChunk chunk = service.readChunk(request(reference, offset, 300_000, "base64"));
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            assembled.writeBytes(bytes);
            assertEquals(offset + bytes.length, chunk.getNextOffset(), "nextOffset must follow the returned bytes");
            offset = chunk.getNextOffset();
            assertTrue(++guard < 500, "the chunk loop must terminate");
            if (chunk.isEof()) {
                break;
            }
        }
        assertEquals(text, assembled.toString(StandardCharsets.UTF_8));
    }

    @Test
    void binaryReadsReportHexAndImagesReportImageMode() {
        byte[] png = tinyPng();
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, png);
        LargeValueReference reference = reference(snapshot, "BINARY", "LONGBLOB");

        CellValueChunk auto = service.readChunk(request(reference, 0L, 4096, null));
        assertEquals("hex", auto.getEncoding(), "an auto request on binary content must report the real encoding");
        assertTrue(auto.getValue().toUpperCase().startsWith("89504E47"), "binary content is served as hex");
        assertEquals("IMAGE", auto.getDisplayMode(), "a PNG payload must be detected as an image");
        assertEquals("image/png", auto.getContentType());
    }

    @Test
    void base64DownloadStaysDecodableForValuesLargerThanTheChunkSize() throws IOException {
        String text = "下载分块校验-".repeat(80_000);
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, text);

        CellValueDownload download = service.prepareDownload(reference(snapshot, "TEXT", "LONGTEXT"), "base64");
        String encoded = new String(download.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        // A window that is not a multiple of three bytes would produce an interior padding character and a strict
        // decoder would reject the concatenation.
        byte[] decoded = Base64.getDecoder().decode(encoded.strip());
        assertEquals(text, new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void snapshotChunksNeverMixUpRowsOrColumns() {
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, "first-cell-".repeat(30_000));
        store.capture(snapshot.getSnapshotId(), 1, 0, "second-row-".repeat(30_000));
        store.capture(snapshot.getSnapshotId(), 1, 1, "second-column-".repeat(30_000));

        SnapshotReadChunk direct = store.read(snapshot.getSnapshotId(), 1, 1, 0L, 32);
        String head = new String(Base64.getDecoder().decode(direct.getValue()), StandardCharsets.UTF_8);
        assertTrue(head.startsWith("second-column-"));
        assertFalse(head.startsWith("second-row-"));
        assertFalse(head.startsWith("first-cell-"));
    }

    @Test
    void plainTextReadsKeepCharacterOffsetsExactAcrossRecordBoundaries() {
        String text = "跨记录边界读取-".repeat(40_000);
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, text);
        LargeValueReference reference = reference(snapshot, "TEXT", "LONGTEXT");

        // Character offsets: the store slices by bytes, so every window boundary that splits a character used to shift
        // the resolved offset and inject replacement characters.
        StringBuilder assembled = new StringBuilder();
        long offset = 0L;
        int guard = 0;
        while (true) {
            CellValueChunk chunk = service.readChunk(request(reference, offset, 262_144, null));
            if (chunk.getValue() == null || chunk.getValue().isEmpty()) {
                break;
            }
            assembled.append(chunk.getValue());
            assertEquals(offset + chunk.getValue().length(), chunk.getNextOffset(),
                    "a character addressed read must advance by the characters it returned");
            offset = chunk.getNextOffset();
            assertTrue(++guard < 100, "the chunk loop must terminate");
            if (chunk.isEof()) {
                break;
            }
        }
        assertEquals(text, assembled.toString());

        // Random access in the middle of the value must land on a real character as well
        CellValueChunk middle = service.readChunk(request(reference, 200_000L, 64, null));
        assertEquals(text.substring(200_000, 200_064), middle.getValue());
    }

    @Test
    void anOpenDownloadFinishesEvenWhenTheContentIsReleasedMeanwhile() throws IOException {
        String text = "下载期间释放-".repeat(80_000);
        ResultSnapshot snapshot = store.register();
        store.capture(snapshot.getSnapshotId(), 0, 0, text);

        CellValueDownload download = service.prepareDownload(reference(snapshot, "TEXT", "LONGTEXT"), "raw");
        byte[] head = download.getInputStream().readNBytes(4096);
        // The client discards the result set while the download is still running
        store.release(snapshot.getSnapshotId());
        byte[] rest = download.getInputStream().readAllBytes();
        download.getInputStream().close();

        byte[] all = new byte[head.length + rest.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(rest, 0, all, head.length, rest.length);
        assertEquals(text, new String(all, StandardCharsets.UTF_8),
                "a running download must receive every byte even after a release");
        assertFalse(store.exists(snapshot.getSnapshotId()),
                "the deferred release happens once the download is finished");
    }

    private LargeValueReference reference(ResultSnapshot snapshot, String valueType, String columnType) {
        return LargeValueReference.builder()
                .snapshotId(snapshot.getSnapshotId())
                .rowIndex(0)
                .columnIndex(0)
                .valueType(valueType)
                .columnType(columnType)
                .build();
    }

    private DbCellValueChunkReadRequest request(LargeValueReference reference, Long offset, Integer limit, String format) {
        DbCellValueChunkReadRequest request = new DbCellValueChunkReadRequest();
        request.setReference(reference);
        request.setOffset(offset);
        request.setLimit(limit);
        request.setFormat(format);
        return request;
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

    /**
     * A one pixel PNG, enough for the magic byte sniffing to recognise an image.
     */
    private static byte[] tinyPng() {
        byte[] ihdr = {0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0};
        byte[] idat = deflate(new byte[]{0, (byte) 0xFF, 0, 0});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        out.writeBytes(chunk("IHDR", ihdr));
        out.writeBytes(chunk("IDAT", idat));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(intBytes(data.length));
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(intBytes((int) crc.getValue()));
        return out.toByteArray();
    }

    private static byte[] intBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] buffer = new byte[64];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] result = new byte[length];
        System.arraycopy(buffer, 0, result, 0, length);
        return result;
    }
}
