package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.value.BinaryContentTypeEnum;
import ai.chat2db.community.domain.api.enums.value.CellValueFormatEnum;
import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import ai.chat2db.community.domain.api.model.db.CellValueChunk;
import ai.chat2db.community.domain.api.model.db.CellValueDownload;
import ai.chat2db.community.domain.api.model.db.LargeValueReference;
import ai.chat2db.community.domain.api.model.request.db.DbCellValueChunkReadRequest;
import ai.chat2db.community.domain.api.service.db.IDbCellValueService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.google.common.io.BaseEncoding;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class DbCellValueServiceImpl implements IDbCellValueService {

    private static final int DEFAULT_CHUNK_SIZE = 64 * 1024;
    private static final int MAX_CHUNK_SIZE = 256 * 1024;
    private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
    private static final int STREAM_BUFFER_SIZE = 8192;
    private static final int BASE64_BYTE_GROUP = 3;
    private static final int BINARY_TYPE_SAMPLE_SIZE = 64 * 1024;
    private static final String TEXT_PLAIN = "text/plain";
    private static final String TEXT_DOWNLOAD = "text/plain;charset=UTF-8";
    private static final String APPLICATION_JSON = "application/json";
    private static final String IMAGE_WILDCARD = "image/*";

    @Override
    public CellValueChunk readChunk(DbCellValueChunkReadRequest readCellValueChunkRequest) {
        LargeValueReference reference = readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getReference();
        Long offsetParam = readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getOffset();
        Integer limitParam = readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getLimit();
        CellValueFormatEnum format = CellValueFormatEnum.fromRequest(
                readCellValueChunkRequest == null ? null : readCellValueChunkRequest.getFormat());
        long offset = Math.max(0L, offsetParam == null ? 0L : offsetParam);
        int limit = normalizeLimit(limitParam, format);
        try (PreparedStatement statement = prepareStatement(reference);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new BusinessException("largeCellValue.rowNotFound");
            }
            Object value = resultSet.getObject(1);
            int sqlType = reference.getSqlType() == null ? resultSet.getMetaData().getColumnType(1) : reference.getSqlType();
            String columnType = StringUtils.defaultIfBlank(reference.getColumnType(),
                    resultSet.getMetaData().getColumnTypeName(1));
            LargeValueTypeEnum valueType = LargeValueTypeEnum.resolveForRead(value, columnType, sqlType,
                    reference.getValueType());
            return valueType.isBinaryLike()
                    ? readBinaryChunk(resultSet, value, offset, limit, format, reference, valueType)
                    : readTextChunk(resultSet, value, offset, limit, format, reference, valueType);
        } catch (SQLException | IOException e) {
            throw new BusinessException("largeCellValue.readFailed", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public CellValueDownload prepareDownload(LargeValueReference reference, String format) {
        try (PreparedStatement statement = prepareStatement(reference);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new BusinessException("largeCellValue.rowNotFound");
            }
            Object value = resultSet.getObject(1);
            int sqlType = reference.getSqlType() == null ? resultSet.getMetaData().getColumnType(1) : reference.getSqlType();
            String columnType = StringUtils.defaultIfBlank(reference.getColumnType(),
                    resultSet.getMetaData().getColumnTypeName(1));
            LargeValueTypeEnum valueType = LargeValueTypeEnum.resolveForRead(value, columnType, sqlType,
                    reference.getValueType());
            CellValueFormatEnum outputFormat = CellValueFormatEnum.fromRequest(format).forDownload();
            BinaryContentTypeEnum binaryContentType = valueType.isBinaryLike()
                    ? detectBinaryContentType(valueType, openBinaryStream(resultSet, value))
                    : BinaryContentTypeEnum.UNKNOWN;
            LargeValueTypeEnum displayMode = valueType.withDetectedBinaryContent(binaryContentType);
            String fileName = fileName(reference, outputFormat, displayMode, binaryContentType);
            byte[] payload = toDownloadBytes(resultSet, value, outputFormat, displayMode);
            return CellValueDownload.builder()
                    .inputStream(new ByteArrayInputStream(payload))
                    .fileName(fileName)
                    .contentType(downloadContentType(outputFormat, displayMode, binaryContentType))
                    .build();
        } catch (SQLException | IOException e) {
            throw new BusinessException("largeCellValue.downloadFailed", new Object[]{e.getMessage()}, e);
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

    private PreparedStatement prepareStatement(LargeValueReference reference) throws SQLException {
        if (reference.getPrimaryKey() == null || reference.getPrimaryKey().isEmpty()) {
            throw new BusinessException("largeCellValue.rowLocatorRequired");
        }
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        QualifiedTableName tableName = parseQualifiedTableName(reference);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ")
                .append(metaData.getMetaDataName(reference.getColumnName()))
                .append(" FROM ")
                .append(metaData.getQualifiedTableName(tableName.databaseName(), tableName.schemaName(),
                        tableName.tableName()))
                .append(" WHERE ");
        boolean first = true;
        for (String columnName : reference.getPrimaryKey().keySet()) {
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(metaData.getMetaDataName(columnName)).append(" = ?");
            first = false;
        }
        PreparedStatement statement = connection.prepareStatement(sql.toString());
        int index = 1;
        for (Object value : reference.getPrimaryKey().values()) {
            statement.setObject(index++, value);
        }
        return statement;
    }

    private CellValueChunk readBinaryChunk(ResultSet resultSet, Object value, long offset, int limit,
                                           CellValueFormatEnum format, LargeValueReference reference,
                                           LargeValueTypeEnum valueType) throws SQLException, IOException {
        CellValueFormatEnum outputFormat = format.forRead();
        BinaryContentTypeEnum contentType = valueType.isBinaryLike()
                ? detectBinaryContentType(valueType, openBinaryStream(resultSet, value))
                : BinaryContentTypeEnum.UNKNOWN;
        try (InputStream inputStream = openBinaryStream(resultSet, value)) {
            skipInputStream(inputStream, offset);
            byte[] readBytes = inputStream.readNBytes(limit + 1);
            int includedBytes = Math.min(readBytes.length, limit);
            byte[] bytes = readBytes.length == includedBytes ? readBytes : java.util.Arrays.copyOf(readBytes,
                    includedBytes);
            boolean eof = readBytes.length <= limit;
            String chunk = outputFormat == CellValueFormatEnum.BASE64
                    ? Base64.getEncoder().encodeToString(bytes)
                    : BaseEncoding.base16().encode(bytes);
            LargeValueTypeEnum displayMode = valueType.withDetectedBinaryContent(contentType);
            return CellValueChunk.builder()
                    .value(chunk)
                    .offset(offset)
                    .nextOffset(offset + includedBytes)
                    .eof(eof)
                    .sizeBytes(sizeBytes(value, reference))
                    .sizeChars(reference.getSizeChars())
                    .encoding(outputFormat.code())
                    .contentType(previewContentType(displayMode, contentType))
                    .displayMode(displayMode.code())
                    .build();
        }
    }

    private CellValueChunk readTextChunk(ResultSet resultSet, Object value, long offset, int limit,
                                         CellValueFormatEnum format, LargeValueReference reference,
                                         LargeValueTypeEnum valueType) throws SQLException, IOException {
        if (format.isEncoded()) {
            return readEncodedTextChunk(resultSet, value, offset, limit, format, reference, valueType);
        }
        try (Reader reader = openReader(resultSet, value)) {
            skipReader(reader, offset);
            char[] buffer = new char[limit + 1];
            int charsRead = readAtMost(reader, buffer, limit + 1);
            int includedChars = Math.min(charsRead, limit);
            String chunk = includedChars <= 0 ? "" : new String(buffer, 0, includedChars);
            boolean eof = charsRead <= limit;
            long nextOffset = offset + Math.max(includedChars, 0);
            return CellValueChunk.builder()
                    .value(chunk)
                    .offset(offset)
                    .nextOffset(nextOffset)
                    .eof(eof)
                    .sizeBytes(reference.getSizeBytes())
                    .sizeChars(sizeChars(value, reference))
                    .encoding(DEFAULT_CHARSET.name())
                    .contentType(previewContentType(valueType, BinaryContentTypeEnum.UNKNOWN))
                    .displayMode(valueType.code())
                    .build();
        }
    }

    private BinaryContentTypeEnum detectBinaryContentType(LargeValueTypeEnum displayMode, InputStream inputStream)
            throws IOException {
        try (inputStream) {
            BinaryContentTypeEnum detected = BinaryContentTypeEnum.detect(inputStream.readNBytes(BINARY_TYPE_SAMPLE_SIZE));
            return displayMode == LargeValueTypeEnum.IMAGE && detected == BinaryContentTypeEnum.UNKNOWN
                    ? BinaryContentTypeEnum.PNG
                    : detected;
        }
    }

    private InputStream openBinaryStream(ResultSet resultSet, Object value) throws SQLException {
        InputStream inputStream = resultSet.getBinaryStream(1);
        if (inputStream != null) {
            return inputStream;
        }
        Blob blob = value instanceof Blob ? (Blob) value : null;
        if (blob != null) {
            return blob.getBinaryStream();
        }
        if (value instanceof byte[] bytes) {
            return new ByteArrayInputStream(bytes);
        }
        return new ByteArrayInputStream(String.valueOf(value).getBytes(DEFAULT_CHARSET));
    }

    private String fileName(LargeValueReference reference, CellValueFormatEnum format, LargeValueTypeEnum valueType,
                            BinaryContentTypeEnum binaryContentType) {
        String base = StringUtils.defaultIfBlank(reference.getTableName(), "cell")
                + "-" + StringUtils.defaultIfBlank(reference.getColumnName(), "value");
        return sanitize(base) + suffix(format, valueType, binaryContentType);
    }

    private byte[] toDownloadBytes(ResultSet resultSet, Object value, CellValueFormatEnum format,
                                   LargeValueTypeEnum valueType) throws SQLException, IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (valueType.isBinaryLike() && format == CellValueFormatEnum.RAW) {
                try (InputStream inputStream = openBinaryStream(resultSet, value)) {
                    streamBinary(inputStream, outputStream);
                }
            } else if (format.isEncoded()) {
                try (InputStream inputStream = valueType.isBinaryLike()
                        ? openBinaryStream(resultSet, value)
                        : new ReaderInputStream(openReader(resultSet, value), DEFAULT_CHARSET)) {
                    streamEncoded(inputStream, outputStream, format);
                }
            } else {
                try (Reader reader = openReader(resultSet, value)) {
                    streamText(reader, outputStream);
                }
            }
            return outputStream.toByteArray();
        }
    }

    private String downloadContentType(CellValueFormatEnum format, LargeValueTypeEnum valueType,
                                       BinaryContentTypeEnum binaryContentType) {
        if (format.isEncoded() || !valueType.isBinaryLike()) {
            return TEXT_DOWNLOAD;
        }
        return binaryContentType == null ? BinaryContentTypeEnum.UNKNOWN.contentType() : binaryContentType.contentType();
    }

    private CellValueChunk readEncodedTextChunk(ResultSet resultSet, Object value, long offset, int limit,
                                                CellValueFormatEnum format, LargeValueReference reference,
                                                LargeValueTypeEnum valueType) throws SQLException, IOException {
        try (Reader reader = openReader(resultSet, value);
             InputStream inputStream = new ReaderInputStream(reader, DEFAULT_CHARSET)) {
            skipInputStream(inputStream, offset);
            byte[] readBytes = inputStream.readNBytes(limit + 1);
            int includedBytes = Math.min(readBytes.length, limit);
            byte[] bytes = readBytes.length == includedBytes ? readBytes : java.util.Arrays.copyOf(readBytes,
                    includedBytes);
            boolean eof = readBytes.length <= limit;
            String chunk = format.isBase64() ? Base64.getEncoder().encodeToString(bytes)
                    : BaseEncoding.base16().encode(bytes);
            return CellValueChunk.builder()
                    .value(chunk)
                    .offset(offset)
                    .nextOffset(offset + includedBytes)
                    .eof(eof)
                    .sizeBytes(reference.getSizeBytes())
                    .sizeChars(sizeChars(value, reference))
                    .encoding(format.code())
                    .contentType(previewContentType(valueType, BinaryContentTypeEnum.UNKNOWN))
                    .displayMode(valueType.code())
                    .build();
        }
    }

    private Reader openReader(ResultSet resultSet, Object value) throws SQLException {
        Reader reader = resultSet.getCharacterStream(1);
        if (reader != null) {
            return reader;
        }
        Clob clob = value instanceof Clob ? (Clob) value : null;
        if (clob != null) {
            return clob.getCharacterStream();
        }
        String stringValue = value == null ? "" : value.toString();
        return new java.io.StringReader(stringValue);
    }

    private void skipReader(Reader reader, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = reader.skip(remaining);
            if (skipped <= 0) {
                if (reader.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void skipInputStream(InputStream inputStream, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private int readAtMost(Reader reader, char[] buffer, int limit) throws IOException {
        int total = 0;
        while (total < limit) {
            int charsRead = reader.read(buffer, total, limit - total);
            if (charsRead == -1) {
                break;
            }
            total += charsRead;
        }
        return total;
    }

    private Long sizeChars(Object value, LargeValueReference reference) throws SQLException {
        if (reference.getSizeChars() != null) {
            return reference.getSizeChars();
        }
        if (value instanceof Clob clob) {
            return clob.length();
        }
        if (value instanceof String stringValue) {
            return (long) stringValue.length();
        }
        return null;
    }

    private Long sizeBytes(Object value, LargeValueReference reference) throws SQLException {
        if (reference.getSizeBytes() != null) {
            return reference.getSizeBytes();
        }
        if (value instanceof Blob blob) {
            return blob.length();
        }
        if (value instanceof byte[] bytes) {
            return (long) bytes.length;
        }
        return null;
    }

    private void streamBinary(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
    }

    private void streamText(Reader reader, OutputStream outputStream) throws IOException {
        char[] buffer = new char[STREAM_BUFFER_SIZE];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            ByteBuffer byteBuffer = DEFAULT_CHARSET.encode(CharBuffer.wrap(buffer, 0, read));
            outputStream.write(byteBuffer.array(), byteBuffer.position(), byteBuffer.remaining());
        }
    }

    private void streamEncoded(InputStream inputStream, OutputStream outputStream,
                               CellValueFormatEnum format) throws IOException {
        if (format == CellValueFormatEnum.BASE64) {
            try (OutputStream base64OutputStream = Base64.getEncoder().wrap(outputStream)) {
                streamBinary(inputStream, base64OutputStream);
            }
            return;
        }
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            byte[] bytes = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
            String encoded = BaseEncoding.base16().encode(bytes);
            outputStream.write(encoded.getBytes(DEFAULT_CHARSET));
        }
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

    private QualifiedTableName parseQualifiedTableName(LargeValueReference reference) {
        List<String> parts = split(reference.getTableName());
        if (parts.size() < 2) {
            String tableName = parts.isEmpty() ? normalizeIdentifier(reference.getTableName()) : parts.get(0);
            return new QualifiedTableName(reference.getDatabaseName(), reference.getSchemaName(), tableName);
        }

        int tableIndex = parts.size() - 1;
        String databaseName = parts.size() > 2 ? parts.get(tableIndex - 2) : null;
        String schemaName = parts.get(tableIndex - 1);
        return new QualifiedTableName(databaseName, schemaName, parts.get(tableIndex));
    }

    private List<String> split(String name) {
        if (StringUtils.isBlank(name)) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quoteEnd = 0;
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (quoteEnd != 0) {
                current.append(ch);
                if (ch == quoteEnd) {
                    quoteEnd = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                quoteEnd = ch;
                current.append(ch);
                continue;
            }
            if (ch == '[') {
                quoteEnd = ']';
                current.append(ch);
                continue;
            }
            if (ch == '.') {
                addPart(parts, current);
                continue;
            }
            current.append(ch);
        }
        addPart(parts, current);
        return parts;
    }

    private void addPart(List<String> parts, StringBuilder current) {
        String part = normalizeIdentifier(current.toString());
        if (StringUtils.isNotBlank(part)) {
            parts.add(part);
        }
        current.setLength(0);
    }

    private String normalizeIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        String trimmed = identifier.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record QualifiedTableName(String databaseName, String schemaName, String tableName) {
    }

    private static class ReaderInputStream extends InputStream {
        private final Reader reader;
        private final Charset charset;
        private byte[] current = new byte[0];
        private int index;
        private boolean eof;

        private ReaderInputStream(Reader reader, Charset charset) {
            this.reader = reader;
            this.charset = charset;
        }

        @Override
        public int read() throws IOException {
            if (index >= current.length && !fill()) {
                return -1;
            }
            return current[index++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (index >= current.length && !fill()) {
                return -1;
            }
            int count = Math.min(len, current.length - index);
            System.arraycopy(current, index, b, off, count);
            index += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }

        private boolean fill() throws IOException {
            if (eof) {
                return false;
            }
            char[] buffer = new char[STREAM_BUFFER_SIZE];
            int read = reader.read(buffer);
            if (read == -1) {
                eof = true;
                return false;
            }
            current = new String(buffer, 0, read).getBytes(charset);
            index = 0;
            return current.length > 0;
        }
    }
}
