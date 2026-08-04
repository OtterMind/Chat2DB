package ai.chat2db.plugin.postgresql.value.sub;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.plugin.postgresql.value.PostgreSQLValueProcessor;
import ai.chat2db.plugin.postgresql.value.factory.PostgreSQLValueProcessorFactory;
import ai.chat2db.spi.model.value.JDBCDataValue;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ByteOrderValues;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class PostgreSQLGeometryProcessorTest {

    private static final String POINT_4490 = "01010000208A11000074143AF510FB594082D264ABDA973E40";
    private static final String LINE_STRING_4490 = "01020000208A11000002000000876E93DDCE075A400C097316513B3E40"
            + "CD3F1768E1075A4026ECD997753B3E40";
    private static final String POLYGON_4490 = "01030000208A1100000100000006000000EEEC2B0F12FE5940793B25947C803E40"
            + "E3F24B0F12FE5940D01506947C803E40E3109AF21BFE59407B2855CB6B803E40"
            + "E86E742118FE59409738134F4D803E40912B92020DFE5940BE8C5A485D803E40"
            + "EEEC2B0F12FE5940793B25947C803E40";

    private final PostgreSQLGeometryProcessor processor = new PostgreSQLGeometryProcessor();

    @Test
    void displaysPointWithPrecisionAndSrid() {
        assertEquals("POINT(103.922910029143 30.5931803818844) | 4490",
                processor.convertJDBCValueByType(jdbcValue(POINT_4490, "geometry")));
    }

    @Test
    void displaysLineStringAndPolygonWithPrecisionAndSrid() {
        assertEquals("LINESTRING(104.12200107 30.23170605, 104.123132727341 30.2322630793607) | 4490",
                processor.convertJDBCValueByType(jdbcValue(LINE_STRING_4490, "geometry")));
        assertEquals("POLYGON((103.96985225 30.50190092, 103.969852279824 30.501900912748, "
                        + "103.97045579 30.50164481, 103.97022282 30.50117964, 103.96954407 30.50142338, "
                        + "103.96985225 30.50190092)) | 4490",
                processor.convertJDBCValueByType(jdbcValue(POLYGON_4490, "geometry")));
    }

    @Test
    void supportsBigEndianAndHexPrefixes() {
        String bigEndian = "0020000001000010E63FF00000000000004000000000000000";
        assertEquals("POINT(1 2) | 4326",
                processor.convertJDBCValueByType(jdbcValue(bigEndian, "geometry")));
        assertEquals("POINT(103.922910029143 30.5931803818844) | 4490",
                processor.convertJDBCValueByType(jdbcValue("\\x" + POINT_4490, "geometry")));
    }

    @Test
    void doesNotInventSridForPlainWkb() {
        String pointWithoutSrid = "0101000000000000000000F03F0000000000000040";
        assertEquals("POINT(1 2)",
                processor.convertJDBCValueByType(jdbcValue(pointWithoutSrid, "geometry")));
    }

    @Test
    void preservesThreeDimensionalCoordinates() {
        String pointZ = "01010000A0E6100000000000000000F03F00000000000000400000000000000840";
        assertEquals("POINT Z(1 2 3) | 4326",
                processor.convertJDBCValueByType(jdbcValue(pointZ, "geometry")));
    }

    @Test
    void acceptsThreeDimensionalRingClosedInTwoDimensions() throws Exception {
        String polygonZ = ewkb("POLYGON Z((0 0 1, 0 1 2, 1 0 3, 0 0 4))", 4490, 3);
        assertEquals("POLYGON Z((0 0 1, 0 1 2, 1 0 3, 0 0 4)) | 4490",
                processor.convertJDBCValueByType(jdbcValue(polygonZ, "geometry")));
    }

    @Test
    void displaysMultiGeometryFamiliesAndCollections() throws Exception {
        assertEquals("MULTIPOINT((1 2), (3 4)) | 4490",
                processor.convertJDBCValueByType(jdbcValue(
                        ewkb("MULTIPOINT((1 2), (3 4))", 4490), "geometry")));
        assertEquals("MULTILINESTRING((1 2, 3 4), (5 6, 7 8)) | 4490",
                processor.convertJDBCValueByType(jdbcValue(
                        ewkb("MULTILINESTRING((1 2, 3 4), (5 6, 7 8))", 4490), "geometry")));
        assertEquals("MULTIPOLYGON(((0 0, 0 1, 1 1, 0 0))) | 4490",
                processor.convertJDBCValueByType(jdbcValue(
                        ewkb("MULTIPOLYGON(((0 0, 0 1, 1 1, 0 0)))", 4490), "geometry")));
        assertEquals("GEOMETRYCOLLECTION(POINT (1 2), LINESTRING (3 4, 5 6)) | 4490",
                processor.convertJDBCValueByType(jdbcValue(
                        ewkb("GEOMETRYCOLLECTION(POINT(1 2), LINESTRING(3 4, 5 6))", 4490), "geometry")));
    }

    @Test
    void fallsBackWithoutLosingMeasuredOrInvalidValues() {
        String pointM = "0101000060E6100000000000000000F03F00000000000000400000000000000840";
        String pointZm = "01010000E0E6100000000000000000F03F00000000000000400000000000000840"
                + "0000000000001040";
        String nestedPointM = "0107000020E6100000010000000101000040000000000000F03F0000000000000040"
                + "0000000000000840";
        String pointWithTrailingData = POINT_4490 + "00";
        String unsupportedCircularString = "010800000000000000";
        String onePointLineString = "010200000001000000000000000000F03F0000000000000040";
        String unclosedPolygon = "01030000000100000003000000"
                + "00000000000000000000000000000000"
                + "000000000000F03F0000000000000000"
                + "0000000000000000000000000000F03F";
        String partiallyEmptyPoint = "0101000000000000000000F87F0000000000000040";
        assertEquals(pointM, processor.convertJDBCValueByType(jdbcValue(pointM, "geometry")));
        assertEquals(pointZm, processor.convertJDBCValueByType(jdbcValue(pointZm, "geometry")));
        assertEquals(nestedPointM, processor.convertJDBCValueByType(jdbcValue(nestedPointM, "geometry")));
        assertEquals(pointWithTrailingData,
                processor.convertJDBCValueByType(jdbcValue(pointWithTrailingData, "geometry")));
        assertEquals(unsupportedCircularString,
                processor.convertJDBCValueByType(jdbcValue(unsupportedCircularString, "geometry")));
        assertEquals(onePointLineString,
                processor.convertJDBCValueByType(jdbcValue(onePointLineString, "geometry")));
        assertEquals(unclosedPolygon,
                processor.convertJDBCValueByType(jdbcValue(unclosedPolygon, "geometry")));
        assertEquals(partiallyEmptyPoint,
                processor.convertJDBCValueByType(jdbcValue(partiallyEmptyPoint, "geometry")));
        assertEquals("0102030405", processor.convertJDBCValueByType(jdbcValue("0102030405", "geometry")));
        assertEquals("not-ewkb", processor.convertJDBCValueByType(jdbcValue("not-ewkb", "geometry")));
    }

    @Test
    void fallsBackBeforeDecodingOversizedEwkb() {
        String exactLimitEwkb = "00".repeat(512 * 1024);
        String oversizedEwkb = exactLimitEwkb + "00";
        String prefixedExactLimitEwkb = "\\x" + exactLimitEwkb;
        String prefixedOversizedEwkb = prefixedExactLimitEwkb + "00";

        assertSame(exactLimitEwkb,
                processor.convertJDBCValueByType(jdbcValue(exactLimitEwkb, "geometry")));
        assertSame(oversizedEwkb,
                processor.convertJDBCValueByType(jdbcValue(oversizedEwkb, "geometry")));
        assertSame(prefixedExactLimitEwkb,
                processor.convertJDBCValueByType(jdbcValue(prefixedExactLimitEwkb, "geometry")));
        assertSame(prefixedOversizedEwkb,
                processor.convertJDBCValueByType(jdbcValue(prefixedOversizedEwkb, "geometry")));
    }

    @Test
    void fallsBackWhenWktOutputExceedsDisplayBudget() {
        Coordinate[] coordinates = new Coordinate[2_000];
        for (int i = 0; i < coordinates.length; i++) {
            coordinates[i] = new Coordinate(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        Geometry geometry = new GeometryFactory().createLineString(coordinates);
        geometry.setSRID(4490);
        String ewkb = WKBWriter.toHex(new WKBWriter(2, ByteOrderValues.LITTLE_ENDIAN, true).write(geometry));

        assertSame(ewkb, processor.convertJDBCValueByType(jdbcValue(ewkb, "geometry")));
    }

    @Test
    void parsesLongEditedValuesWithoutRegexBacktracking() {
        String whitespace = " ".repeat(64 * 1024);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertEquals("''::geometry", processor.getSqlValueString(sqlValue(whitespace, "geometry"))));
    }

    @Test
    void convertsWktAtDisplayBudgetWithLongestSridSuffix() {
        String wkt = "P".repeat(1024 * 1024);

        assertEquals("'SRID=-2147483648;" + wkt + "'::geometry",
                processor.getSqlValueString(sqlValue(wkt + " | -2147483648", "geometry")));
    }

    @Test
    void displaysEmptyGeometryWithoutInventingCoordinates() throws Exception {
        assertEquals("POINT EMPTY | 4490",
                processor.convertJDBCValueByType(jdbcValue(ewkb("POINT EMPTY", 4490), "geometry")));
        assertEquals("LINESTRING EMPTY | 4490",
                processor.convertJDBCValueByType(jdbcValue(ewkb("LINESTRING EMPTY", 4490), "geometry")));
        assertEquals("POLYGON EMPTY | 4490",
                processor.convertJDBCValueByType(jdbcValue(ewkb("POLYGON EMPTY", 4490), "geometry")));
    }

    @Test
    void preservesRawEwkbForThreeDimensionalEmptyGeometries() throws Exception {
        String[] emptyGeometries = {
                "POINT Z EMPTY",
                "LINESTRING Z EMPTY",
                "POLYGON Z EMPTY",
                "MULTIPOINT Z EMPTY",
                "MULTILINESTRING Z EMPTY",
                "MULTIPOLYGON Z EMPTY",
                "GEOMETRYCOLLECTION Z EMPTY",
                "GEOMETRYCOLLECTION Z (POINT Z EMPTY, POINT Z (1 2 3))"
        };
        for (String wkt : emptyGeometries) {
            String value = ewkb(wkt, 4326, 3);
            assertEquals(value, processor.convertJDBCValueByType(jdbcValue(value, "geometry")));
        }
    }

    @Test
    void convertsDisplayValuesToTypedPostgisLiterals() {
        assertEquals("'SRID=4490;POINT(1 2)'::geometry",
                processor.getSqlValueString(sqlValue("POINT(1 2) | 4490", "geometry")));
        assertEquals("'SRID=4326;POINT(1 2)'::geography",
                processor.getSqlValueString(sqlValue("POINT(1 2) | 4326", "geography")));
        assertEquals("'POINT(1 2)'::geometry",
                processor.getSqlValueString(sqlValue("POINT(1 2)", "geometry")));
    }

    @Test
    void preservesRawEwkbForJdbcSqlAndEscapesEditedValues() {
        assertEquals("'" + POINT_4490 + "'::geometry",
                processor.convertJDBCValueStrByType(jdbcValue(POINT_4490, "geometry")));
        assertEquals("'" + POINT_4490 + "'::geography",
                processor.convertJDBCValueStrByType(jdbcValue("0x" + POINT_4490, "geography")));
        assertEquals("'POINT(1 2)'' OR true --'::geometry",
                processor.getSqlValueString(sqlValue("POINT(1 2)' OR true --", "geometry")));
    }

    @Test
    void dispatchesGeometryAndGeographyCaseInsensitively() {
        PostgreSQLValueProcessor valueProcessor = new PostgreSQLValueProcessor();
        assertEquals("POINT(103.922910029143 30.5931803818844) | 4490",
                valueProcessor.getJdbcValue(jdbcValue(POINT_4490, "GeOmEtRy")));
        assertEquals("'SRID=4490;POINT(1 2)'::geography",
                valueProcessor.getSqlValueString(sqlValue("POINT(1 2) | 4490", "GeOgRaPhY")));
    }

    @Test
    void factoryUsesOneSpatialProcessorForBothPostgisTypes() {
        Object geometry = PostgreSQLValueProcessorFactory.getValueProcessor("GEOMETRY");
        Object geography = PostgreSQLValueProcessorFactory.getValueProcessor("GEOGRAPHY");
        assertInstanceOf(PostgreSQLGeometryProcessor.class, geometry);
        assertSame(geometry, geography);
    }

    @Test
    void preservesNullSemantics() {
        assertEquals("NULL", processor.getSqlValueString(sqlValue(null, "geometry")));
        assertNull(new PostgreSQLValueProcessor().getJdbcValue(jdbcValue(null, "geometry")));
    }

    private static String ewkb(String wkt, int srid) throws Exception {
        return ewkb(wkt, srid, 2);
    }

    private static String ewkb(String wkt, int srid, int outputDimension) throws Exception {
        Geometry geometry = new WKTReader().read(wkt);
        geometry.setSRID(srid);
        WKBWriter writer = new WKBWriter(outputDimension, ByteOrderValues.LITTLE_ENDIAN, true);
        return WKBWriter.toHex(writer.write(geometry));
    }

    private static SQLDataValue sqlValue(String value, String type) {
        DataType dataType = new DataType();
        dataType.setDataTypeName(type);
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(value);
        return sqlDataValue;
    }

    private static JDBCDataValue jdbcValue(String value, String type) {
        return new JDBCDataValue(null, null, 1, false) {
            @Override
            public Object getObject() {
                return value;
            }

            @Override
            public String getString() {
                return value;
            }

            @Override
            public String getType() {
                return type;
            }
        };
    }
}
