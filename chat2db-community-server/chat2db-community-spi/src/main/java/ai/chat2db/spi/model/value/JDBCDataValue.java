package ai.chat2db.spi.model.value;

import ai.chat2db.community.domain.api.enums.value.BinaryContentTypeEnum;
import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import ai.chat2db.community.domain.api.enums.value.LobUnitEnum;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.spi.util.ResultSetUtils;
import com.google.common.io.BaseEncoding;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.io.IOUtils;
import org.apache.tika.Tika;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.*;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Data
@AllArgsConstructor
public class JDBCDataValue {
    private static final Logger log = LoggerFactory.getLogger(JDBCDataValue.class);
    private static final int LARGE_VALUE_THRESHOLD_BYTES = 10 * 1024;
    private static final int LARGE_VALUE_PREVIEW_CHARS = 200;
    private static final Pattern SUMMARY_SIZE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private ResultSet resultSet;
    private ResultSetMetaData metaData;
    private int columnIndex;
    private boolean limitSize;
    private String timezone;

    public JDBCDataValue(ResultSet resultSet, ResultSetMetaData metaData, int columnIndex, boolean limitSize) {
        this.resultSet = resultSet;
        this.metaData = metaData;
        this.columnIndex = columnIndex;
        this.limitSize = limitSize;
    }

    public Object getObject() {
        if (isJsonType()) {
            return getJsonString();
        }
        try {
            return resultSet.getObject(columnIndex);
        } catch (Exception e) {
            log.warn("Failed to retrieve object from database", e);
            try {
                return resultSet.getString(columnIndex);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public String getString() {
        if (isJsonType()) {
            return getJsonString();
        }
        return ResultSetUtils.getString(resultSet, columnIndex);
    }

    public String getType() {
        return ResultSetUtils.getColumnDataTypeName(metaData, columnIndex);
    }

    public int getSqlType() {
        try {
            return metaData.getColumnType(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public InputStream getBinaryStream() {
        return ResultSetUtils.getBinaryStream(resultSet, columnIndex);
    }

    public int getPrecision() {
        return ResultSetUtils.getColumnPrecision(metaData, columnIndex);
    }

    public byte[] getBytes() {
        return ResultSetUtils.getBytes(resultSet, columnIndex);
    }

    public boolean getBoolean() {
        return ResultSetUtils.getBoolean(resultSet, columnIndex);
    }

    public int getScale() {
        return ResultSetUtils.getColumnScale(metaData, columnIndex);
    }

    public int getInt() {
        return ResultSetUtils.getInt(resultSet, columnIndex);
    }
    public long getLong() {
        return ResultSetUtils.getLong(resultSet, columnIndex);
    }

    public Date getDate() {
        return ResultSetUtils.getDate(resultSet, columnIndex);
    }

    public Timestamp getTimestamp() {
        return ResultSetUtils.getTimestamp(resultSet, columnIndex);
    }

    public Clob getClob() {
        return ResultSetUtils.getClob(resultSet, columnIndex);
    }

    public Blob getBlob() {
        return ResultSetUtils.getBlob(resultSet, columnIndex);
    }

    public Reader getCharacterStream() {
        return ResultSetUtils.getCharacterStream(resultSet, columnIndex);
    }

    public String getBlobHexString() {
        byte[] bytes = getBytes();
        if (Objects.isNull(bytes)) {
            return null;
        }
        return BaseEncoding.base16().encode(bytes);
    }

    public BigDecimal getBigDecimal() {
        return ResultSetUtils.getBigDecimal(resultSet, columnIndex);
    }

    public String getBigDecimalString() {
        BigDecimal bigDecimal = getBigDecimal();
        return bigDecimal == null ? new String(getBytes()) : bigDecimal.toPlainString();
    }


    public String getBlobString() {
        Blob blob = getBlob();
        if (blob == null) {
            return null;
        }
        try (InputStream binaryStream = blob.getBinaryStream()) {
            long length = blob.length();
            return converterBinaryData(length, binaryStream);
        } catch (SQLException | IOException e) {
            log.warn("Error while reading binary stream", e);
            return getString();
        }
    }


    public String getClobString() {
        Clob clob = getClob();
        if (clob == null) {
            return getStringValue();
        }
        try (Reader reader = clob.getCharacterStream()) {
            long length = clob.length();
            LOBInfo cLobInfo = getLobInfo(length);
            double size = cLobInfo.getSize();
            if (size == 0) {
                return "";
            }
            String unit = cLobInfo.getUnit();
            if (limitSize && isBigSize(unit, size)) {
                return String.format("[%s] %s", getType(), cLobInfo);
            }
            return IOUtils.toString(reader);
        } catch (IOException | SQLException e) {
            log.warn("Error while reading clob stream", e);
            return getStringValue();
        }
    }

    public String getCharsetString() {
        StringBuilder sb = new StringBuilder(8192);
        long totalSize = 0;
        try (Reader characterStream = getCharacterStream()) {
            if (Objects.isNull(characterStream)) {
                return null;
            }
            char[] buffer = new char[8192];
            int charsRead;
            while ((charsRead = characterStream.read(buffer)) != -1) {
                sb.append(buffer, 0, charsRead);
                if (sb.length() > LobUnitEnum.M.getSize()) {
                    totalSize += sb.length();
                    sb.setLength(0);
                }
            }
            totalSize += sb.length();
            if (totalSize > LobUnitEnum.M.getSize()) {
                return String.format("[%s] %s", getType(), getLobInfo(totalSize));
            } else {
                return sb.toString();
            }
        } catch (IOException e) {
            return getStringValue();
        }
    }

    private String handleImageType(InputStream imageStream, LOBInfo lobInfo) {
        if (limitSize) {
            try {
                BufferedImage bufferedImage = ImageIO.read(imageStream);
                return String.format("[%s] %dx%d JPEG image  %s", getType(), bufferedImage.getWidth(), bufferedImage.getHeight(), lobInfo);
            } catch (IOException e) {
                log.warn("Error while reading image stream", e);
                return getStringValue();
            }
        } else {
            return "0x" + getBlobHexString();
        }
    }

    private String handleStringType(InputStream binaryStream, LOBInfo lobInfo) throws IOException {
        if (isBigSize(lobInfo.getUnit(), lobInfo.size) && limitSize) {
            return String.format("[%s] %s", getType(), lobInfo);
        } else {
            return new String(binaryStream.readAllBytes());
        }
    }

    private boolean isBigSize(String unit, double size) {
        if (LobUnitEnum.M.getUnit().equals(unit) || LobUnitEnum.G.getUnit().equals(unit)) {
            return true;
        }
        if (LobUnitEnum.K.getUnit().equals(unit)) {
            return size > 500;
        }
        return false;
    }


    @NotNull
    private LOBInfo getLobInfo(long size) {
        if (size == 0) {
            return new LOBInfo(LobUnitEnum.B.getUnit(), 0);
        }
        return new LOBInfo(size);
    }

    public String getStringValue() {
        if (isJsonType()) {
            return getJsonString();
        }
        return ResultSetUtils.getStringValue(resultSet, columnIndex);
    }

    public ResultCell buildResultCell(String value) {
        String columnType = getType();
        int sqlType = getSqlType();
        long displayBytes = value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
        long displayChars = value == null ? 0L : value.length();
        LargeValueInfo largeValueInfo = detectLargeValue(value, columnType, sqlType, displayBytes);
        String displayValue = previewValue(value, largeValueInfo);
        Object rawValue = getRawCellValue(value, largeValueInfo);
        return ResultCell.builder()
                .value(displayValue)
                .rawValue(rawValue)
                .largeValue(largeValueInfo.largeValue)
                .valueType(largeValueInfo.valueType.code())
                .sqlType(sqlType)
                .columnType(columnType)
                .sizeBytes(largeValueInfo.sizeBytes)
                .sizeChars(largeValueInfo.sizeChars)
                .loadedBytes(largeValueInfo.largeValue && displayValue != null
                        ? (long) displayValue.getBytes(StandardCharsets.UTF_8).length : null)
                .loadedChars(largeValueInfo.largeValue && displayValue != null ? (long) displayValue.length() : null)
                .truncated(largeValueInfo.largeValue)
                .build();
    }

    public String getBinaryDataString() {
        InputStream binaryStream = null;
        try {
            binaryStream = getBinaryStream();
            if (Objects.isNull(binaryStream)) {
                return null;
            }
            if (!binaryStream.markSupported()) {
                binaryStream = new BufferedInputStream(binaryStream);
            }

            binaryStream.mark(Integer.MAX_VALUE);

            long size = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = binaryStream.read(buffer)) != -1) {
                size += bytesRead;
            }
            binaryStream.reset();
            return converterBinaryData(size, binaryStream);
        } catch (SQLException | IOException e) {
            log.warn("Error while reading binary stream", e);
            return getStringValue();
        } finally {
            if (binaryStream != null) {
                try {
                    binaryStream.close();
                } catch (IOException e) {
                    log.warn("Error while closing binary stream", e);
                }
            }
        }
    }

    private LargeValueInfo detectLargeValue(String value, String columnType, int sqlType, long valueBytes) {
        LargeValueInfo info = new LargeValueInfo();
        info.valueType = LargeValueTypeEnum.resolve(columnType, sqlType);
        if (!limitSize || value == null) {
            return info;
        }
        Long summaryBytes = parseSummaryBytes(value);
        if (summaryBytes != null) {
            info.largeValue = true;
            info.summary = true;
            info.sizeBytes = summaryBytes;
            if (info.valueType == LargeValueTypeEnum.BINARY && isImageSummary(value)) {
                info.valueType = LargeValueTypeEnum.IMAGE;
            }
            if (info.valueType.isTextLike()) {
                info.sizeChars = summaryBytes;
            }
            return info;
        }
        if (LargeValueTypeEnum.isPotentialLargeType(columnType, sqlType)
                && valueBytes > LARGE_VALUE_THRESHOLD_BYTES) {
            info.largeValue = true;
            info.sizeBytes = valueBytes;
            info.sizeChars = (long) value.length();
        }
        return info;
    }

    private boolean isImageSummary(String value) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(" IMAGE");
    }

    private String previewValue(String value, LargeValueInfo largeValueInfo) {
        if (value == null || !largeValueInfo.largeValue || largeValueInfo.summary
                || value.length() <= LARGE_VALUE_PREVIEW_CHARS) {
            return value;
        }
        return value.substring(0, LARGE_VALUE_PREVIEW_CHARS);
    }

    private Object getRawCellValue(String value, LargeValueInfo largeValueInfo) {
        try {
            if (largeValueInfo.valueType == LargeValueTypeEnum.JSON) {
                return value;
            }
            if (!largeValueInfo.largeValue) {
                return getObject();
            }
            if (largeValueInfo.valueType.isTextLike()) {
                return getClob();
            }
            if (largeValueInfo.valueType.isBinaryLike()) {
                return getBlob();
            }
            return getObject();
        } catch (Exception e) {
            log.warn("Failed to retrieve raw cell value", e);
            return null;
        }
    }

    private Long parseSummaryBytes(String value) {
        if (value == null || !value.startsWith("[")) {
            return null;
        }
        int endIndex = value.indexOf(']');
        if (endIndex <= 0 || endIndex + 1 >= value.length()) {
            return null;
        }
        String sizeText = value.substring(endIndex + 1).trim();
        Matcher matcher = SUMMARY_SIZE_PATTERN.matcher(sizeText);
        if (!matcher.find()) {
            return null;
        }
        try {
            double size = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toUpperCase(Locale.ROOT);
            if ("B".equals(unit)) {
                return (long) size;
            }
            if ("KB".equals(unit)) {
                return (long) (size * LobUnitEnum.K.getSize());
            }
            if ("MB".equals(unit)) {
                return (long) (size * LobUnitEnum.M.getSize());
            }
            if ("GB".equals(unit)) {
                return (long) (size * LobUnitEnum.G.getSize());
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private boolean isJsonType() {
        try {
            return LargeValueTypeEnum.resolve(getType(), getSqlType()) == LargeValueTypeEnum.JSON;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getJsonString() {
        try {
            return resultSet.getString(columnIndex);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static class LargeValueInfo {
        private boolean largeValue;
        private boolean summary;
        private LargeValueTypeEnum valueType = LargeValueTypeEnum.UNKNOWN;
        private Long sizeBytes;
        private Long sizeChars;
    }

    private String converterBinaryData(long size, InputStream binaryStream) throws IOException, SQLException {
        LOBInfo lobInfo = getLobInfo(size);
        String unit = lobInfo.unit;
        if (size == 0) {
            return "";
        }
        Tika tika = new Tika();
        String contentType = tika.detect(binaryStream);
        BinaryContentTypeEnum binaryContentType = BinaryContentTypeEnum.fromContentType(contentType);
        if (binaryContentType == BinaryContentTypeEnum.UNKNOWN) {
            if (isTextContentType(contentType)) {
                return handleStringType(binaryStream, lobInfo);
            }
            if (isBigSize(unit, lobInfo.size) && limitSize) {
                return String.format("[%s] %s", getType(), lobInfo);
            }
            return "0x" + BaseEncoding.base16().encode(binaryStream.readAllBytes());
        }

        if (binaryContentType.isImage()) {
            return handleImageType(binaryStream, lobInfo);
        }
        if (isBigSize(unit, lobInfo.size) && limitSize) {
            return String.format("[%s] %s", getType(), lobInfo);
        }
        return "0x" + BaseEncoding.base16().encode(binaryStream.readAllBytes());
    }

    private boolean isTextContentType(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/");
    }

    public float getFloat() {
        return ResultSetUtils.getFloat(resultSet,columnIndex);
    }

    public double getDouble() {
        return ResultSetUtils.getDouble(resultSet,columnIndex);
    }

    @Getter
    public static class LOBInfo {
        private final String unit;
        private final double size;

        public LOBInfo(String unit, double size) {
            this.unit = unit;
            this.size = size;
        }

        public LOBInfo(long size) {
            if (size >= LobUnitEnum.G.getSize()) {
                this.unit = LobUnitEnum.G.getUnit();
                this.size = (double) size / LobUnitEnum.G.getSize();
            } else if (size >= LobUnitEnum.M.getSize()) {
                this.unit = LobUnitEnum.M.getUnit();
                this.size = (double) size / LobUnitEnum.M.getSize();
            } else if (size >= LobUnitEnum.K.getSize()) {
                this.unit = LobUnitEnum.K.getUnit();
                this.size = (double) size / LobUnitEnum.K.getSize();
            } else {
                this.unit = LobUnitEnum.B.getUnit();
                this.size = (double) size;
            }
        }

        @Override
        public String toString() {
            return String.format("%.2f %s", size, unit);
        }
    }

}
