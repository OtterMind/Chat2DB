package ai.chat2db.community.domain.api.model.task.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ExportCell {

    private final Object value;
    private final int jdbcType;
    private final String typeName;
    private final int precision;
    private final int scale;

    public ExportCell(Object value, int jdbcType, String typeName, int precision, int scale) {
        this.value = copyValue(value);
        this.jdbcType = jdbcType;
        this.typeName = typeName;
        this.precision = precision;
        this.scale = scale;
    }

    public Object getValue() {
        return copyValue(value);
    }

    public int getJdbcType() {
        return jdbcType;
    }

    public String getTypeName() {
        return typeName;
    }

    public int getPrecision() {
        return precision;
    }

    public int getScale() {
        return scale;
    }

    public boolean isNullValue() {
        return value == null;
    }

    public ExportCell withValue(Object newValue) {
        return new ExportCell(newValue, jdbcType, typeName, precision, scale);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ExportCell other)) {
            return false;
        }
        return jdbcType == other.jdbcType
                && precision == other.precision
                && scale == other.scale
                && Objects.equals(typeName, other.typeName)
                && Objects.deepEquals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jdbcType, typeName, precision, scale, Arrays.deepHashCode(new Object[]{value}));
    }

    private static Object copyValue(Object source) {
        if (source == null) {
            return null;
        }
        if (isKnownImmutable(source)) {
            return source;
        }
        if (source instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (source instanceof char[] chars) {
            return chars.clone();
        }
        if (source instanceof Timestamp timestamp) {
            Timestamp copy = new Timestamp(timestamp.getTime());
            copy.setNanos(timestamp.getNanos());
            return copy;
        }
        if (source instanceof java.sql.Date date) {
            return new java.sql.Date(date.getTime());
        }
        if (source instanceof Time time) {
            return new Time(time.getTime());
        }
        if (source instanceof Date date) {
            return new Date(date.getTime());
        }
        if (source instanceof Calendar calendar) {
            return calendar.clone();
        }
        if (source instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }
        if (source instanceof Blob blob) {
            try (InputStream stream = blob.getBinaryStream()) {
                return stream.readAllBytes();
            } catch (SQLException | IOException e) {
                throw new IllegalStateException("Failed to snapshot export BLOB value", e);
            }
        }
        if (source instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                return readAll(reader);
            } catch (SQLException | IOException e) {
                throw new IllegalStateException("Failed to snapshot export CLOB value", e);
            }
        }
        if (source instanceof SQLXML sqlxml) {
            try {
                return sqlxml.getString();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to snapshot export SQLXML value", e);
            }
        }
        if (source instanceof RowId rowId) {
            return rowId.getBytes().clone();
        }
        if (source instanceof java.sql.Array sqlArray) {
            try {
                return copyValue(sqlArray.getArray());
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to snapshot export ARRAY value", e);
            }
        }
        if (source instanceof Struct struct) {
            try {
                return copyValue(struct.getAttributes());
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to snapshot export STRUCT value", e);
            }
        }
        if (source instanceof Ref ref) {
            try {
                return copyValue(ref.getObject());
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to snapshot export REF value", e);
            }
        }
        if (source instanceof InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to snapshot export binary stream", e);
            }
        }
        if (source instanceof Reader reader) {
            try {
                return readAll(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to snapshot export character stream", e);
            }
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            Class<?> componentType = source.getClass().getComponentType();
            Object copy = Array.newInstance(componentType.isPrimitive() ? componentType : Object.class, length);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, copyValue(Array.get(source, index)));
            }
            return copy;
        }
        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(value -> copy.add(copyValue(value)));
            return Collections.unmodifiableList(copy);
        }
        if (source instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>(set.size());
            set.forEach(value -> copy.add(copyValue(value)));
            return Collections.unmodifiableSet(copy);
        }
        if (source instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(value -> copy.add(copyValue(value)));
            return Collections.unmodifiableList(copy);
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(map.size());
            map.forEach((key, value) -> copy.put(copyValue(key), copyValue(value)));
            return Collections.unmodifiableMap(copy);
        }

        // Vendor-specific JDBC objects are frequently mutable and tied to the current result set.
        return String.valueOf(source);
    }

    private static boolean isKnownImmutable(Object source) {
        return source instanceof String
                || source instanceof Boolean
                || source instanceof Character
                || source instanceof Byte
                || source instanceof Short
                || source instanceof Integer
                || source instanceof Long
                || source instanceof Float
                || source instanceof Double
                || source instanceof BigInteger
                || source instanceof BigDecimal
                || source instanceof UUID
                || source instanceof URI
                || source instanceof URL
                || source instanceof Locale
                || source instanceof Currency
                || source instanceof Enum<?>
                || source instanceof TemporalAccessor
                || source instanceof TemporalAmount
                || source instanceof ZoneId;
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder value = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            value.append(buffer, 0, count);
        }
        return value.toString();
    }
}
