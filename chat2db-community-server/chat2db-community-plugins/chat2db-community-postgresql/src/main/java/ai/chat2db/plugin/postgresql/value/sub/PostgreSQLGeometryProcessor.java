package ai.chat2db.plugin.postgresql.value.sub;

import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.io.Ordinate;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.regex.Pattern;

public class PostgreSQLGeometryProcessor extends DefaultValueProcessor {

    public static final String GEOMETRY_TYPE = "GEOMETRY";
    public static final String GEOGRAPHY_TYPE = "GEOGRAPHY";

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLGeometryProcessor.class);
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");
    private static final int EWKB_Z_FLAG = 0x80000000;
    private static final int EWKB_M_FLAG = 0x40000000;
    private static final int EWKB_SRID_FLAG = 0x20000000;
    private static final int EWKB_BBOX_FLAG = 0x10000000;
    private static final int EWKB_TYPE_MASK = 0x0FFFFFFF;
    private static final int MAX_NESTED_DEPTH = 64;
    private static final int MAX_DISPLAY_EWKB_BYTES = 512 * 1024;
    private static final int MAX_DISPLAY_EWKB_HEX_CHARS = MAX_DISPLAY_EWKB_BYTES * 2;
    private static final int MAX_DISPLAY_WKT_CHARS = MAX_DISPLAY_EWKB_HEX_CHARS;
    private static final int MAX_DISPLAY_SRID_SUFFIX_CHARS = " | ".length()
            + Integer.toString(Integer.MIN_VALUE).length();

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return toSqlLiteral(dataValue.getValue(), dataValue.getDateTypeName());
    }

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        String value = dataValue.getString();
        if (value == null || value.length() > MAX_DISPLAY_EWKB_HEX_CHARS + 2) {
            return value;
        }
        String hexValue = normalizeHex(value);
        if (hexValue == null || hexValue.length() > MAX_DISPLAY_EWKB_HEX_CHARS) {
            return value;
        }

        try {
            byte[] ewkb = WKBReader.hexToBytes(hexValue);
            WkbHeader header = inspectEwkb(ewkb);
            if (header.hasMeasure()) {
                return value;
            }

            Geometry geometry = new WKBReader().read(ewkb);
            if (header.hasZ() && containsEmptyGeometry(geometry)) {
                return value;
            }
            WKTWriter writer = new WKTWriter(header.hasZ() ? 3 : 2);
            writer.setOutputOrdinates(header.hasZ() ? Ordinate.createXYZ() : Ordinate.createXY());
            BoundedStringWriter output = new BoundedStringWriter(MAX_DISPLAY_WKT_CHARS);
            try {
                writer.write(geometry, output);
            } catch (WktOutputLimitExceededException ignored) {
                return value;
            }
            String displayValue = removeTypeParenthesisSpace(output.value());
            if (header.srid() != null) {
                return displayValue + " | " + geometry.getSRID();
            }
            return displayValue;
        } catch (Exception e) {
            log.debug("Unable to convert PostgreSQL spatial EWKB for display", e);
            return value;
        }
    }

    private boolean containsEmptyGeometry(Geometry geometry) {
        if (geometry.isEmpty()) {
            return true;
        }
        if (geometry instanceof GeometryCollection collection) {
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                if (containsEmptyGeometry(collection.getGeometryN(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return toSqlLiteral(dataValue.getString(), dataValue.getType());
    }

    private String toSqlLiteral(String value, String dataType) {
        if (value == null) {
            return "NULL";
        }

        String sqlValue = normalizeHex(value);
        if (sqlValue == null) {
            sqlValue = toEwkt(value);
        }
        String escapedValue = PostgreSQLIdentifierProcessor.INSTANCE.escapeString(sqlValue);
        return "'" + escapedValue + "'::" + resolveSpatialType(dataType);
    }

    private String toEwkt(String value) {
        if (value.length() > MAX_DISPLAY_WKT_CHARS + MAX_DISPLAY_SRID_SUFFIX_CHARS) {
            return value;
        }
        String trimmedValue = value.trim();
        int separator = trimmedValue.lastIndexOf('|');
        if (separator < 0) {
            return trimmedValue;
        }
        String wkt = trimmedValue.substring(0, separator).trim();
        String sridValue = trimmedValue.substring(separator + 1).trim();
        if (wkt.isEmpty() || wkt.length() > MAX_DISPLAY_WKT_CHARS || sridValue.isEmpty()) {
            return trimmedValue;
        }
        try {
            int srid = Integer.parseInt(sridValue);
            return "SRID=" + srid + ";" + wkt;
        } catch (NumberFormatException ignored) {
            return trimmedValue;
        }
    }

    private String resolveSpatialType(String dataType) {
        if (dataType != null && dataType.toUpperCase(Locale.ROOT).startsWith(GEOGRAPHY_TYPE)) {
            return GEOGRAPHY_TYPE.toLowerCase(Locale.ROOT);
        }
        return GEOMETRY_TYPE.toLowerCase(Locale.ROOT);
    }

    private String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("\\x") || candidate.startsWith("\\X")
                || candidate.startsWith("0x") || candidate.startsWith("0X")) {
            candidate = candidate.substring(2);
        }
        if (candidate.length() < 10 || candidate.length() % 2 != 0 || !HEX_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }

    private WkbHeader inspectEwkb(byte[] ewkb) {
        // JTS repairs malformed rings and coordinate sequences, so validate the lossless subset first.
        WkbCursor cursor = new WkbCursor(ewkb);
        WkbHeader header = inspectGeometry(cursor, null, true, 0);
        if (!cursor.isExhausted()) {
            throw new IllegalArgumentException("EWKB contains trailing data");
        }
        return header;
    }

    private WkbHeader inspectGeometry(WkbCursor cursor, Integer inheritedSrid, boolean root, int depth) {
        if (depth > MAX_NESTED_DEPTH) {
            throw new IllegalArgumentException("EWKB nesting is too deep");
        }
        ByteOrder byteOrder = readByteOrder(cursor);
        int typeWord = cursor.readInt(byteOrder);
        if ((typeWord & EWKB_BBOX_FLAG) != 0) {
            throw new IllegalArgumentException("EWKB bounding-box headers are not supported");
        }

        int isoType = typeWord & EWKB_TYPE_MASK;
        int dimensionCode = isoType / 1000;
        int baseType = isoType % 1000;
        if (dimensionCode > 3 || baseType < 1 || baseType > 7) {
            throw new IllegalArgumentException("Unsupported EWKB geometry type");
        }

        boolean hasZ = (typeWord & EWKB_Z_FLAG) != 0 || dimensionCode == 1 || dimensionCode == 3;
        boolean hasMeasure = (typeWord & EWKB_M_FLAG) != 0 || dimensionCode == 2 || dimensionCode == 3;
        boolean hasSrid = (typeWord & EWKB_SRID_FLAG) != 0;
        Integer srid = hasSrid ? cursor.readInt(byteOrder) : null;
        if (!root && srid != null && !srid.equals(inheritedSrid)) {
            throw new IllegalArgumentException("Nested EWKB SRID differs from its parent");
        }

        WkbHeader header = new WkbHeader(baseType, hasZ, hasMeasure, srid);
        int coordinateDimension = 2 + (hasZ ? 1 : 0) + (hasMeasure ? 1 : 0);
        switch (baseType) {
            case 1 -> inspectPoint(cursor, byteOrder, coordinateDimension);
            case 2 -> inspectLineString(cursor, byteOrder, coordinateDimension);
            case 3 -> {
                int ringCount = readCount(cursor, byteOrder);
                for (int i = 0; i < ringCount; i++) {
                    inspectLinearRing(cursor, byteOrder, coordinateDimension);
                }
            }
            case 4 -> inspectChildren(cursor, byteOrder, header, inheritedSrid, 1, depth);
            case 5 -> inspectChildren(cursor, byteOrder, header, inheritedSrid, 2, depth);
            case 6 -> inspectChildren(cursor, byteOrder, header, inheritedSrid, 3, depth);
            case 7 -> inspectChildren(cursor, byteOrder, header, inheritedSrid, 0, depth);
            default -> throw new IllegalArgumentException("Unsupported EWKB geometry type");
        }
        return header;
    }

    private void inspectPoint(WkbCursor cursor, ByteOrder byteOrder, int coordinateDimension) {
        boolean allNaN = true;
        boolean allFinite = true;
        for (int i = 0; i < coordinateDimension; i++) {
            double ordinate = cursor.readDouble(byteOrder);
            allNaN &= Double.isNaN(ordinate);
            allFinite &= Double.isFinite(ordinate);
        }
        if (!allNaN && !allFinite) {
            throw new IllegalArgumentException("EWKB point contains an invalid empty coordinate");
        }
    }

    private void inspectLineString(WkbCursor cursor, ByteOrder byteOrder, int coordinateDimension) {
        int coordinateCount = readCount(cursor, byteOrder);
        if (coordinateCount == 1) {
            throw new IllegalArgumentException("EWKB line string must be empty or contain at least two coordinates");
        }
        inspectFiniteCoordinates(cursor, byteOrder, coordinateCount, coordinateDimension);
    }

    private void inspectLinearRing(WkbCursor cursor, ByteOrder byteOrder, int coordinateDimension) {
        int coordinateCount = readCount(cursor, byteOrder);
        if (coordinateCount < 4) {
            throw new IllegalArgumentException("EWKB linear ring must contain at least four coordinates");
        }

        double[] firstCoordinate = readFiniteCoordinate(cursor, byteOrder, coordinateDimension);
        inspectFiniteCoordinates(cursor, byteOrder, coordinateCount - 2, coordinateDimension);
        double[] lastCoordinate = readFiniteCoordinate(cursor, byteOrder, coordinateDimension);
        for (int i = 0; i < 2; i++) {
            if (firstCoordinate[i] != lastCoordinate[i]) {
                throw new IllegalArgumentException("EWKB linear ring is not closed");
            }
        }
    }

    private void inspectFiniteCoordinates(WkbCursor cursor, ByteOrder byteOrder,
                                          int coordinateCount, int coordinateDimension) {
        for (int coordinate = 0; coordinate < coordinateCount; coordinate++) {
            for (int ordinate = 0; ordinate < coordinateDimension; ordinate++) {
                if (!Double.isFinite(cursor.readDouble(byteOrder))) {
                    throw new IllegalArgumentException("EWKB coordinate is not finite");
                }
            }
        }
    }

    private double[] readFiniteCoordinate(WkbCursor cursor, ByteOrder byteOrder, int coordinateDimension) {
        double[] coordinate = new double[coordinateDimension];
        for (int i = 0; i < coordinateDimension; i++) {
            coordinate[i] = cursor.readDouble(byteOrder);
            if (!Double.isFinite(coordinate[i])) {
                throw new IllegalArgumentException("EWKB coordinate is not finite");
            }
        }
        return coordinate;
    }

    private void inspectChildren(WkbCursor cursor, ByteOrder byteOrder, WkbHeader parent,
                                 Integer inheritedSrid, int expectedType, int depth) {
        int childCount = readCount(cursor, byteOrder);
        Integer effectiveSrid = parent.srid() != null ? parent.srid() : inheritedSrid;
        for (int i = 0; i < childCount; i++) {
            WkbHeader child = inspectGeometry(cursor, effectiveSrid, false, depth + 1);
            if (expectedType != 0 && child.baseType() != expectedType) {
                throw new IllegalArgumentException("EWKB collection contains an unexpected geometry type");
            }
            if (child.hasZ() != parent.hasZ() || child.hasMeasure() != parent.hasMeasure()) {
                throw new IllegalArgumentException("EWKB collection contains mixed coordinate dimensions");
            }
        }
    }

    private int readCount(WkbCursor cursor, ByteOrder byteOrder) {
        int count = cursor.readInt(byteOrder);
        if (count < 0) {
            throw new IllegalArgumentException("EWKB contains a negative element count");
        }
        return count;
    }

    private ByteOrder readByteOrder(WkbCursor cursor) {
        int byteOrder = cursor.readUnsignedByte();
        if (byteOrder == 0) {
            return ByteOrder.BIG_ENDIAN;
        }
        if (byteOrder == 1) {
            return ByteOrder.LITTLE_ENDIAN;
        }
        throw new IllegalArgumentException("Unsupported EWKB byte order");
    }

    private String removeTypeParenthesisSpace(String wkt) {
        int openParenthesis = wkt.indexOf('(');
        if (openParenthesis > 0 && wkt.charAt(openParenthesis - 1) == ' ') {
            return wkt.substring(0, openParenthesis - 1) + wkt.substring(openParenthesis);
        }
        return wkt;
    }

    private record WkbHeader(int baseType, boolean hasZ, boolean hasMeasure, Integer srid) {
    }

    private static final class BoundedStringWriter extends Writer {

        private final StringBuilder value = new StringBuilder();
        private final int maxChars;

        private BoundedStringWriter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void write(int character) throws IOException {
            requireCapacity(1);
            value.append((char) character);
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            requireCapacity(length);
            value.append(buffer, offset, length);
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            requireCapacity(length);
            value.append(text, offset, offset + length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private void requireCapacity(int additionalChars) throws WktOutputLimitExceededException {
            if (additionalChars < 0 || additionalChars > maxChars - value.length()) {
                throw new WktOutputLimitExceededException();
            }
        }

        private String value() {
            return value.toString();
        }
    }

    private static final class WktOutputLimitExceededException extends IOException {
    }

    private static final class WkbCursor {

        private final byte[] data;
        private int offset;

        private WkbCursor(byte[] data) {
            this.data = data;
        }

        private int readUnsignedByte() {
            require(Byte.BYTES);
            return data[offset++] & 0xff;
        }

        private int readInt(ByteOrder byteOrder) {
            require(Integer.BYTES);
            int value;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                value = (data[offset] & 0xff) << 24
                        | (data[offset + 1] & 0xff) << 16
                        | (data[offset + 2] & 0xff) << 8
                        | data[offset + 3] & 0xff;
            } else {
                value = data[offset] & 0xff
                        | (data[offset + 1] & 0xff) << 8
                        | (data[offset + 2] & 0xff) << 16
                        | (data[offset + 3] & 0xff) << 24;
            }
            offset += Integer.BYTES;
            return value;
        }

        private double readDouble(ByteOrder byteOrder) {
            return Double.longBitsToDouble(readLong(byteOrder));
        }

        private long readLong(ByteOrder byteOrder) {
            require(Long.BYTES);
            long value = 0;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                for (int i = 0; i < Long.BYTES; i++) {
                    value = value << 8 | data[offset + i] & 0xffL;
                }
            } else {
                for (int i = Long.BYTES - 1; i >= 0; i--) {
                    value = value << 8 | data[offset + i] & 0xffL;
                }
            }
            offset += Long.BYTES;
            return value;
        }

        private boolean isExhausted() {
            return offset == data.length;
        }

        private void require(int byteCount) {
            if (byteCount > data.length - offset) {
                throw new IllegalArgumentException("EWKB ends before its declared geometry data");
            }
        }
    }
}
