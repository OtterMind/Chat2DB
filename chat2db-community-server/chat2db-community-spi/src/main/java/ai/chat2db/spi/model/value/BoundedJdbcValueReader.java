package ai.chat2db.spi.model.value;

import ai.chat2db.community.domain.api.enums.value.LargeValueTypeEnum;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.spi.IValueProcessor;
import java.io.*;
import java.sql.*;

/** V2-only source capture. Never materializes a large JDBC value before applying its budget. */
final class BoundedJdbcValueReader {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int SMALL_FALLBACK_CHARS = 8192;

    private BoundedJdbcValueReader() { }

    static ResultCell captureText(String value, ResultValueBudget budget) {
        Captured captured = value == null ? Captured.nullValue() : text(new StringReader(value), budget, (long) value.length());
        return ResultCell.builder().value(captured.value).rawValue(captured.value).valueType("TEXT")
                .largeValue(captured.warning != null).truncated(captured.warning != null)
                .unsupportedReason(captured.warning).sizeChars(captured.originalChars)
                .loadedBytes(captured.value == null ? null : captured.bytes)
                .loadedChars(captured.value == null ? null : (long) captured.value.length()).build();
    }

    static ResultCell read(JDBCDataValue value, ResultValueBudget budget, IValueProcessor processor) {
        LargeValueTypeEnum type = LargeValueTypeEnum.resolve(value.getType(), value.getSqlType());
        Captured captured;
        if (type.isBinaryLike()) {
            captured = binary(value, budget);
        } else if (scalar(value.getSqlType())) {
            // Numeric, temporal and boolean values retain the database plugin's formatting.
            String text = processor.getJdbcValue(value);
            captured = text == null ? Captured.nullValue() : text(new StringReader(text), budget, (long) text.length());
        } else {
            captured = characters(value, budget);
        }
        return ResultCell.builder().value(captured.value).rawValue(captured.value)
                .largeValue(captured.warning != null).truncated(captured.warning != null)
                .unsupportedReason(captured.warning).valueType(type.code()).sqlType(value.getSqlType())
                .columnType(value.getType()).sizeChars(captured.originalChars)
                .loadedBytes(captured.value == null ? null : captured.bytes)
                .loadedChars(captured.value == null ? null : (long) captured.value.length()).build();
    }

    private static Captured characters(JDBCDataValue value, ResultValueBudget budget) {
        ResultSet result = value.getResultSet();
        int index = value.getColumnIndex();
        try {
            // Ask for the stream directly: some drivers implement getClob by first
            // materializing getString, even when getCharacterStream is available.
            Reader reader = result.getCharacterStream(index);
            return reader == null ? Captured.nullValue() : text(reader, budget, null);
        } catch (SQLException exception) {
            // A bounded, declared-small value can use drivers without character-stream support.
            // Never fall back to getString for a LOB/JSON/unknown-width value.
            try {
                int size = value.getMetaData().getColumnDisplaySize(index);
                LargeValueTypeEnum type = LargeValueTypeEnum.resolve(value.getType(), value.getSqlType());
                if (!type.canBeLarge() && size > 0 && size <= SMALL_FALLBACK_CHARS) {
                    String text = result.getString(index);
                    return text == null ? Captured.nullValue() : text(new StringReader(text), budget, (long) text.length());
                }
            } catch (SQLException ignored) { }
            return new Captured("", 0, null, "SOURCE_READ_UNAVAILABLE: Driver cannot stream this value safely");
        }
    }

    private static Captured text(Reader source, ResultValueBudget budget, Long originalCharacters) {
        StringBuilder text = new StringBuilder((int) Math.min(8192, budget.remaining()));
        long bytes = 0;
        String warning = null;
        try (PushbackReader reader = new PushbackReader(new BufferedReader(source, 8192), 1)) {
            int first;
            while ((first = reader.read()) != -1) {
                int second = -1;
                int codePoint = first;
                if (Character.isHighSurrogate((char) first)) {
                    second = reader.read();
                    if (second != -1 && Character.isLowSurrogate((char) second)) {
                        codePoint = Character.toCodePoint((char) first, (char) second);
                    } else {
                        if (second != -1) reader.unread(second);
                        second = -1;
                    }
                }
                int count = codePoint < 0x80 || (codePoint >= 0xd800 && codePoint <= 0xdfff) ? 1
                        : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
                if (!budget.consume(count)) { warning = ResultValueBudget.EXCEEDED; break; }
                text.append((char) first);
                if (second != -1) text.append((char) second);
                bytes += count;
            }
        } catch (IOException exception) {
            warning = "SOURCE_READ_INTERRUPTED: Driver stopped reading this value; retained content is partial";
        }
        return new Captured(text.toString(), bytes, originalCharacters, warning);
    }

    private static Captured binary(JDBCDataValue value, ResultValueBudget budget) {
        StringBuilder text = new StringBuilder((int) Math.min(8192, budget.remaining()));
        long bytes = 0;
        String warning = null;
        try (InputStream stream = value.getResultSet().getBinaryStream(value.getColumnIndex())) {
            if (stream == null) return Captured.nullValue();
            try (InputStream input = new BufferedInputStream(stream, 8192)) {
                int next = input.read();
                if (next == -1) return new Captured("", 0, null, null);
                if (!budget.consume(2)) return new Captured("", 0, null, ResultValueBudget.EXCEEDED);
                text.append("0x");
                bytes = 2;
                do {
                    if (!budget.consume(2)) { warning = ResultValueBudget.EXCEEDED; break; }
                    text.append(HEX[next >>> 4]).append(HEX[next & 15]);
                    bytes += 2;
                } while ((next = input.read()) != -1);
            }
        } catch (IOException | SQLException exception) {
            warning = "SOURCE_READ_INTERRUPTED: Driver stopped reading binary data; retained content is partial";
        }
        return new Captured(text.toString(), bytes, null, warning);
    }

    private static boolean scalar(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL,
                    Types.DOUBLE, Types.NUMERIC, Types.DECIMAL, Types.BOOLEAN, Types.BIT, Types.DATE,
                    Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    private record Captured(String value, long bytes, Long originalChars, String warning) {
        static Captured nullValue() { return new Captured(null, 0, null, null); }
    }
}
