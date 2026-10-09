package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.value.BinaryContentTypeEnum;
import ai.chat2db.community.domain.api.enums.value.CellValueFormatEnum;
import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import ai.chat2db.community.domain.api.model.db.CellValueChunk;
import ai.chat2db.community.domain.api.model.db.CellValueDownload;
import ai.chat2db.community.domain.api.model.db.LargeValueReference;
import ai.chat2db.community.domain.api.model.result.snapshot.SnapshotReadChunk;
import ai.chat2db.community.domain.api.service.result.IResultSnapshotStore;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueChunkReadRequest;
import ai.chat2db.community.domain.api.service.db.IDbCellValueService;
import ai.chat2db.community.tools.exception.BusinessException;
import com.google.common.io.BaseEncoding;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DbCellValueServiceImpl implements IDbCellValueService {

    private final ObjectProvider<IResultSnapshotStore> snapshotStoreProvider;

    /**
     * Last translated character offset per cell. Plain text reads address content by character offset while the
     * snapshot is byte addressed, so without a checkpoint every chunk of a sequential read would rescan the prefix.
     */
    private final Map<String, long[]> characterByteCheckpoints = new ConcurrentHashMap<>();

    public DbCellValueServiceImpl() {
        this(null);
    }

    @Autowired
    public DbCellValueServiceImpl(ObjectProvider<IResultSnapshotStore> snapshotStoreProvider) {
        this.snapshotStoreProvider = snapshotStoreProvider;
    }

    private IResultSnapshotStore snapshotStore() {
        return snapshotStoreProvider == null ? null : snapshotStoreProvider.getIfAvailable();
    }

    private static final int DEFAULT_CHUNK_SIZE = 64 * 1024;
    private static final int MAX_CHUNK_SIZE = 256 * 1024;
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int BASE64_BYTE_GROUP = 3;
    private static final int BINARY_TYPE_SAMPLE_SIZE = 64 * 1024;
    private static final String TEXT_PLAIN = "text/plain";
    private static final String TEXT_DOWNLOAD = "text/plain;charset=UTF-8";
    private static final String APPLICATION_JSON = "application/json";
    private static final String IMAGE_WILDCARD = "image/*";

    @Override
    public CellValueChunk readChunk(DbCellValueChunkReadRequest readCellValueChunkRequest) {
        LargeValueReference reference = readCellValueChunkRequest == null ? null
                : readCellValueChunkRequest.getReference();
        Long offsetParam = readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getOffset();
        Integer limitParam = readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getLimit();
        CellValueFormatEnum format = CellValueFormatEnum.fromRequest(
                readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getFormat());
        long offset = Math.max(0L, offsetParam == null ? 0L : offsetParam);
        int limit = normalizeLimit(limitParam, format);
        IResultSnapshotStore snapshotStore = reference != null && reference.snapshotBacked() ? snapshotStore() : null;
        if (snapshotStore == null) {
            throw new BusinessException("largeCellValue.snapshotExpired");
        }
        return readSnapshotChunk(snapshotStore, reference, offset, limit, format);
    }

    @Override
    public CellValueDownload prepareDownload(LargeValueReference reference, String format) {
        IResultSnapshotStore snapshotStore = reference != null && reference.snapshotBacked() ? snapshotStore() : null;
        if (snapshotStore == null) {
            throw new BusinessException("largeCellValue.snapshotExpired");
        }
        return prepareSnapshotDownload(snapshotStore, reference, format);
    }

    /**
     * Reads one chunk straight from the result snapshot. Text offsets are byte offsets for encoded formats and
     * character offsets otherwise, matching what the previous database backed implementation returned.
     */
    private CellValueChunk readSnapshotChunk(IResultSnapshotStore snapshotStore, LargeValueReference reference,
                                             long offset, int limit, CellValueFormatEnum format) {
        int rowIndex = reference.getRowIndex() == null ? 0 : reference.getRowIndex();
        int columnIndex = reference.getColumnIndex() == null ? 0 : reference.getColumnIndex();
        LargeValueTypeEnum valueType = LargeValueTypeEnum.resolveForRead(reference.getColumnType(),
                reference.getSqlType(), reference.getValueType());
        // Auto keeps the historical behaviour: plain text for text values, hex for binary ones. Only the reported
        // encoding has to name what is really produced instead of echoing "auto".
        CellValueFormatEnum effectiveFormat = format.forRead();
        boolean encodedFormat = format.isEncoded();
        boolean binaryLike = valueType.isBinaryLike();
        boolean characterOffsets = !binaryLike && !encodedFormat;
        SnapshotReadChunk chunk = snapshotStore.read(reference.getSnapshotId(), rowIndex, columnIndex,
                characterOffsets
                        ? characterOffsetToBytes(snapshotStore, reference, rowIndex, columnIndex, offset)
                        : offset,
                characterOffsets
                        ? characterLimitToBytes(snapshotStore, reference, rowIndex, columnIndex, offset, limit)
                        : limit);
        byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
        String value;
        long nextOffset;
        String encoding;
        if (encodedFormat) {
            value = format.isBase64() ? chunk.getValue() : BaseEncoding.base16().encode(bytes);
            nextOffset = chunk.getNextOffset();
            encoding = format.code();
        } else if (binaryLike) {
            boolean base64 = effectiveFormat == CellValueFormatEnum.BASE64;
            value = base64 ? chunk.getValue() : BaseEncoding.base16().encode(bytes);
            nextOffset = chunk.getNextOffset();
            encoding = base64 ? CellValueFormatEnum.BASE64.code() : CellValueFormatEnum.HEX.code();
        } else {
            value = new String(bytes, DEFAULT_CHARSET);
            nextOffset = offset + value.length();
            encoding = DEFAULT_CHARSET.name();
        }
        BinaryContentTypeEnum binaryContentType = binaryLike
                ? detectSnapshotBinaryContentType(snapshotStore, reference, rowIndex, columnIndex, valueType)
                : BinaryContentTypeEnum.UNKNOWN;
        LargeValueTypeEnum displayMode = valueType.withDetectedBinaryContent(binaryContentType);
        return CellValueChunk.builder()
                .value(value)
                .offset(offset)
                .nextOffset(nextOffset)
                .eof(chunk.isEof())
                // The snapshot owns the content, so its size is authoritative over the size stored on the token
                .sizeBytes(chunk.getSizeBytes())
                .sizeChars(chunk.getSizeChars())
                .encoding(encoding)
                .contentType(previewContentType(displayMode, binaryContentType))
                .displayMode(displayMode.code())
                .build();
    }

    /**
     * Sniffs the leading bytes of a binary value so images keep their real mime type and extension, like the database
     * backed read path did.
     */
    private BinaryContentTypeEnum detectSnapshotBinaryContentType(IResultSnapshotStore snapshotStore,
                                                                 LargeValueReference reference, int rowIndex,
                                                                 int columnIndex, LargeValueTypeEnum valueType) {
        SnapshotReadChunk sample = snapshotStore.read(reference.getSnapshotId(), rowIndex, columnIndex, 0L,
                BINARY_TYPE_SAMPLE_SIZE);
        byte[] bytes = Base64.getDecoder().decode(sample.getValue());
        BinaryContentTypeEnum detected = BinaryContentTypeEnum.detect(bytes);
        return valueType == LargeValueTypeEnum.IMAGE && detected == BinaryContentTypeEnum.UNKNOWN
                ? BinaryContentTypeEnum.PNG
                : detected;
    }

    /**
     * Byte range of a run of characters in a byte addressed snapshot.
     *
     * @param byteOffset byte offset of the first character.
     * @param byteLength bytes that hold exactly {@code characters} characters.
     * @param characters characters actually covered (less than requested at the end of the value).
     */
    private record CharacterRange(long byteOffset, long byteLength, long characters) {
    }

    private long characterOffsetToBytes(IResultSnapshotStore snapshotStore, LargeValueReference reference, int rowIndex,
                                        int columnIndex, long characterOffset) {
        return resolveCharacterRange(snapshotStore, reference, rowIndex, columnIndex, characterOffset, 1).byteOffset();
    }

    private int characterLimitToBytes(IResultSnapshotStore snapshotStore, LargeValueReference reference, int rowIndex,
                                      int columnIndex, long characterOffset, int characterLimit) {
        CharacterRange range = resolveCharacterRange(snapshotStore, reference, rowIndex, columnIndex, characterOffset,
                Math.max(1, characterLimit));
        return (int) Math.max(1L, range.byteLength());
    }

    /**
     * Walks the snapshot window by window and maps a character position to a byte position.
     * <p>
     * A window can end in the middle of a multi byte character, so the tail of a window is carried over to the next one
     * instead of being decoded as a replacement character. Counting characters that way keeps the byte offsets exact no
     * matter where the store puts its record boundaries.
     */
    private CharacterRange resolveCharacterRange(IResultSnapshotStore snapshotStore, LargeValueReference reference,
                                                 int rowIndex, int columnIndex, long characterOffset,
                                                 long characterCount) {
        String checkpointKey = reference.getSnapshotId() + ':' + rowIndex + ':' + columnIndex;
        long byteOffset = 0L;
        long charactersSeen = 0L;
        long[] checkpoint = characterByteCheckpoints.get(checkpointKey);
        if (checkpoint != null && checkpoint[0] <= characterOffset) {
            byteOffset = checkpoint[1];
            charactersSeen = checkpoint[0];
        }
        byte[] carry = new byte[0];
        long startByte = -1L;
        long startCharacter = -1L;
        long endByte = -1L;
        while (true) {
            SnapshotReadChunk window = snapshotStore.read(reference.getSnapshotId(), rowIndex, columnIndex, byteOffset,
                    MAX_CHUNK_SIZE);
            byte[] windowBytes = Base64.getDecoder().decode(window.getValue());
            byte[] combined = new byte[carry.length + windowBytes.length];
            System.arraycopy(carry, 0, combined, 0, carry.length);
            System.arraycopy(windowBytes, 0, combined, carry.length, windowBytes.length);
            long windowStartByte = byteOffset - carry.length;
            int usable = completeCharacterPrefix(combined);
            String text = new String(combined, 0, usable, DEFAULT_CHARSET);
            for (int index = 0; index < text.length(); index++) {
                long characterIndex = charactersSeen + index;
                if (characterIndex == characterOffset && startByte < 0) {
                    startByte = windowStartByte + utf8Length(text, index);
                    startCharacter = characterIndex;
                }
                if (startByte >= 0 && characterIndex == startCharacter + characterCount) {
                    endByte = windowStartByte + utf8Length(text, index);
                    break;
                }
            }
            charactersSeen += text.length();
            if (endByte >= 0) {
                break;
            }
            if (startByte < 0) {
                rememberCheckpoint(checkpointKey, charactersSeen, windowStartByte + usable);
            }
            carry = Arrays.copyOfRange(combined, usable, combined.length);
            byteOffset = window.getNextOffset();
            if (windowBytes.length == 0 || window.isEof()) {
                if (startByte < 0) {
                    startByte = windowStartByte + usable;
                    startCharacter = charactersSeen;
                }
                endByte = windowStartByte + usable;
                break;
            }
        }
        long resolvedStart = Math.max(0L, startByte);
        long resolvedEnd = Math.max(resolvedStart, endByte);
        if (characterOffset <= 0L) {
            rememberCheckpoint(checkpointKey, 0L, 0L);
        }
        return new CharacterRange(resolvedStart, Math.max(1L, resolvedEnd - resolvedStart),
                Math.max(0L, charactersSeen - startCharacter));
    }

    /**
     * @return the length of the longest prefix of {@code bytes} that ends on a character boundary, so decoding the
     *         prefix can never produce a replacement character for a sequence the window cut in half.
     */
    private static int completeCharacterPrefix(byte[] bytes) {
        for (int trailing = 1; trailing <= 3 && trailing <= bytes.length; trailing++) {
            byte value = bytes[bytes.length - trailing];
            if ((value & 0xC0) == 0x80) {
                continue;
            }
            return trailing >= utf8SequenceLength(value) ? bytes.length : bytes.length - trailing;
        }
        return bytes.length;
    }

    private static int utf8SequenceLength(byte leadingByte) {
        if ((leadingByte & 0x80) == 0) {
            return 1;
        }
        if ((leadingByte & 0xE0) == 0xC0) {
            return 2;
        }
        if ((leadingByte & 0xF0) == 0xE0) {
            return 3;
        }
        if ((leadingByte & 0xF8) == 0xF0) {
            return 4;
        }
        return 1;
    }

    private static int utf8Length(String text, int endExclusive) {
        return endExclusive <= 0 ? 0 : text.substring(0, endExclusive).getBytes(DEFAULT_CHARSET).length;
    }

    private void rememberCheckpoint(String key, long characterOffset, long byteOffset) {
        if (characterByteCheckpoints.size() > 4096) {
            characterByteCheckpoints.clear();
        }
        characterByteCheckpoints.put(key, new long[]{characterOffset, byteOffset});
    }

    private CellValueDownload prepareSnapshotDownload(IResultSnapshotStore snapshotStore, LargeValueReference reference,
                                                      String format) {
        int rowIndex = reference.getRowIndex() == null ? 0 : reference.getRowIndex();
        int columnIndex = reference.getColumnIndex() == null ? 0 : reference.getColumnIndex();
        LargeValueTypeEnum valueType = LargeValueTypeEnum.resolveForRead(reference.getColumnType(),
                reference.getSqlType(), reference.getValueType());
        CellValueFormatEnum outputFormat = CellValueFormatEnum.fromRequest(format).forDownload();
        SnapshotReadChunk first = snapshotStore.read(reference.getSnapshotId(), rowIndex, columnIndex, 0L,
                BINARY_TYPE_SAMPLE_SIZE);
        byte[] sample = Base64.getDecoder().decode(first.getValue());
        BinaryContentTypeEnum binaryContentType = valueType.isBinaryLike()
                ? BinaryContentTypeEnum.detect(sample)
                : BinaryContentTypeEnum.UNKNOWN;
        LargeValueTypeEnum displayMode = valueType.withDetectedBinaryContent(binaryContentType);
        String fileName = fileName(reference, outputFormat, displayMode, binaryContentType);
        return CellValueDownload.builder()
                .inputStream(new SnapshotContentInputStream(snapshotStore, reference.getSnapshotId(), rowIndex,
                        columnIndex, outputFormat))
                .fileName(fileName)
                .contentType(downloadContentType(outputFormat, displayMode, binaryContentType))
                .build();
    }

    /**
     * Streams snapshot content in the requested download format without loading the whole value into memory.
     */
    private static final class SnapshotContentInputStream extends InputStream {

        private final IResultSnapshotStore snapshotStore;
        private final String snapshotId;
        private final int rowIndex;
        private final int columnIndex;
        private final CellValueFormatEnum outputFormat;
        private byte[] buffer = new byte[0];
        private int position;
        private long offset;
        private boolean finished;

        private boolean leaseReleased;

        private SnapshotContentInputStream(IResultSnapshotStore snapshotStore, String snapshotId, int rowIndex,
                                           int columnIndex, CellValueFormatEnum outputFormat) {
            this.snapshotStore = snapshotStore;
            this.snapshotId = snapshotId;
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
            this.outputFormat = outputFormat;
            // A download can outlive the handle that started it, so it keeps the content alive until it is done
            snapshotStore.hold(snapshotId);
        }

        @Override
        public void close() {
            releaseLease();
        }

        private void releaseLease() {
            if (!leaseReleased) {
                leaseReleased = true;
                snapshotStore.unhold(snapshotId);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position >= buffer.length) {
                if (finished || !fill()) {
                    releaseLease();
                    return -1;
                }
            }
            int available = Math.min(length, buffer.length - position);
            System.arraycopy(buffer, position, target, targetOffset, available);
            position += available;
            return available;
        }

        private boolean fill() {
            // An encoded window must be a multiple of three bytes, otherwise each window is encoded separately and the
            // concatenation is not valid base64.
            int window = outputFormat.isEncoded() ? MAX_CHUNK_SIZE - (MAX_CHUNK_SIZE % BASE64_BYTE_GROUP) : MAX_CHUNK_SIZE;
            SnapshotReadChunk chunk = snapshotStore.read(snapshotId, rowIndex, columnIndex, offset, window);
            byte[] bytes = Base64.getDecoder().decode(chunk.getValue());
            offset = chunk.getNextOffset();
            finished = chunk.isEof();
            if (bytes.length == 0) {
                return false;
            }
            if (outputFormat.isEncoded()) {
                buffer = outputFormat.isBase64() ? Base64.getEncoder().encode(bytes)
                        : BaseEncoding.base16().encode(bytes).getBytes(DEFAULT_CHARSET);
            } else {
                buffer = bytes;
            }
            position = 0;
            return true;
        }
    }

    private int normalizeLimit(Integer limit, CellValueFormatEnum format) {
        int resolved = limit == null || limit <= 0 ? DEFAULT_CHUNK_SIZE : limit;
        resolved = Math.min(resolved, MAX_CHUNK_SIZE);
        if (format.isBase64() && resolved < BASE64_BYTE_GROUP) {
            return BASE64_BYTE_GROUP;
        }
        if (format.isBase64() && resolved > BASE64_BYTE_GROUP) {
            return resolved - (resolved % BASE64_BYTE_GROUP);
        }
        return resolved;
    }


    private String fileName(LargeValueReference reference, CellValueFormatEnum format, LargeValueTypeEnum valueType,
                            BinaryContentTypeEnum binaryContentType) {
        String base = StringUtils.defaultIfBlank(reference.getTableName(), "cell")
                + "-" + StringUtils.defaultIfBlank(reference.getColumnName(), "value");
        return sanitize(base) + suffix(format, valueType, binaryContentType);
    }


    private String downloadContentType(CellValueFormatEnum format, LargeValueTypeEnum valueType,
                                       BinaryContentTypeEnum binaryContentType) {
        if (format.isEncoded() || !valueType.isBinaryLike()) {
            return TEXT_DOWNLOAD;
        }
        return binaryContentType == null ? BinaryContentTypeEnum.UNKNOWN.contentType() : binaryContentType.contentType();
    }


    private String previewContentType(LargeValueTypeEnum displayMode, BinaryContentTypeEnum binaryContentType) {
        return switch (displayMode) {
            case IMAGE -> binaryContentType == null || binaryContentType == BinaryContentTypeEnum.UNKNOWN
                    ? IMAGE_WILDCARD
                    : binaryContentType.contentType();
            case BINARY -> binaryContentType == null
                    ? BinaryContentTypeEnum.UNKNOWN.contentType()
                    : binaryContentType.contentType();
            case JSON -> APPLICATION_JSON;
            default -> TEXT_PLAIN;
        };
    }

    private String suffix(CellValueFormatEnum format, LargeValueTypeEnum valueType,
                          BinaryContentTypeEnum binaryContentType) {
        return switch (format) {
            case HEX -> ".hex.txt";
            case BASE64 -> ".base64.txt";
            case TEXT -> valueType == LargeValueTypeEnum.JSON ? ".json" : ".txt";
            default -> rawSuffix(valueType, binaryContentType);
        };
    }

    private String rawSuffix(LargeValueTypeEnum valueType, BinaryContentTypeEnum binaryContentType) {
        if (valueType == LargeValueTypeEnum.JSON) {
            return ".json";
        }
        if (valueType == LargeValueTypeEnum.TEXT) {
            return ".txt";
        }
        return valueType.isBinaryLike() && binaryContentType != null ? binaryContentType.extension() : ".bin";
    }

    private String sanitize(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }


}
