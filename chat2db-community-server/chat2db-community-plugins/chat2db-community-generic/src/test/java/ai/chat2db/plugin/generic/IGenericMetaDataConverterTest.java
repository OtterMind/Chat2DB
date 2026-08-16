package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.Type;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the JDBC-metadata fallback used when generic.json declares no columnTypes
 * (ELASTICSEARCH, TDENGINE).
 */
class IGenericMetaDataConverterTest {

    @Test
    void mapsCapabilitiesReportedDirectlyByJdbcTypeInfo() {
        Type type = Type.builder()
                .typeName("DECIMAL")
                .createParams(" precision, SCALE ")
                .nullable((short) DatabaseMetaData.typeNullable)
                .autoIncrement(Boolean.TRUE)
                .build();

        ColumnType columnType = IGenericMetaDataConverter.INSTANCE.type2columnType(type);

        assertEquals("DECIMAL", columnType.getTypeName());
        assertTrue(columnType.isSupportLength());
        assertTrue(columnType.isSupportScale());
        assertTrue(columnType.isSupportNullable());
        assertTrue(columnType.isSupportAutoIncrement());
        assertFalse(columnType.isSupportDefaultValue());
    }

    @Test
    void doesNotInferLengthOrDefaultSupportFromTypeNameAndPrecision() {
        Type type = Type.builder()
                .typeName("VARCHAR")
                .precision(255)
                .nullable((short) DatabaseMetaData.typeNullable)
                .build();

        ColumnType columnType = IGenericMetaDataConverter.INSTANCE.type2columnType(type);

        assertFalse(columnType.isSupportLength());
        assertFalse(columnType.isSupportDefaultValue());
    }

    @Test
    void commaSeparatedNonScaleParametersDoNotEnableScale() {
        Type type = Type.builder()
                .typeName("GEOMETRY")
                .createParams("TYPE,SRID")
                .build();

        ColumnType columnType = IGenericMetaDataConverter.INSTANCE.type2columnType(type);

        assertFalse(columnType.isSupportScale());
    }

    @Test
    void nullableUnknownRemainsDisabled() {
        Type unknown = Type.builder()
                .typeName("UNKNOWN_NULLABILITY")
                .nullable((short) DatabaseMetaData.typeNullableUnknown)
                .build();
        Type noNulls = Type.builder()
                .typeName("NO_NULLS")
                .nullable((short) DatabaseMetaData.typeNoNulls)
                .build();

        List<ColumnType> columnTypes = IGenericMetaDataConverter.INSTANCE
                .type2columnType(List.of(unknown, noNulls));

        assertFalse(columnTypes.get(0).isSupportNullable());
        assertFalse(columnTypes.get(1).isSupportNullable());
    }

    @Test
    void listMappingAppliesFlagsToEachElement() {
        Type withLength = Type.builder()
                .typeName("VARCHAR")
                .createParams("length")
                .nullable((short) DatabaseMetaData.typeNullable)
                .build();
        Type plain = Type.builder()
                .typeName("BOOLEAN")
                .nullable((short) DatabaseMetaData.typeNullable)
                .build();

        List<ColumnType> columnTypes = IGenericMetaDataConverter.INSTANCE
                .type2columnType(List.of(withLength, plain));

        assertEquals(2, columnTypes.size());
        assertTrue(columnTypes.get(0).isSupportLength());
        assertTrue(columnTypes.get(0).isSupportNullable());
        assertFalse(columnTypes.get(1).isSupportLength());
        assertTrue(columnTypes.get(1).isSupportNullable());
    }

    @Test
    void convertsRealH2TypeInfoWithoutTreatingSyntaxCommasAsScale() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:generic-type-info")) {
            List<Type> types = DefaultSQLExecutor.getInstance().types(connection);
            Map<String, ColumnType> columnTypes = IGenericMetaDataConverter.INSTANCE.type2columnType(types).stream()
                    .collect(Collectors.toMap(ColumnType::getTypeName, Function.identity()));

            assertTrue(columnTypes.get("NUMERIC").isSupportLength());
            assertTrue(columnTypes.get("NUMERIC").isSupportScale());
            assertTrue(columnTypes.get("CHARACTER VARYING").isSupportLength());
            assertFalse(columnTypes.get("CHARACTER VARYING").isSupportScale());
            assertFalse(columnTypes.get("INTEGER").isSupportLength());
            assertFalse(columnTypes.get("GEOMETRY").isSupportScale());
            assertFalse(columnTypes.get("ENUM").isSupportScale());
            assertTrue(columnTypes.values().stream().noneMatch(ColumnType::isSupportDefaultValue));

            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class,
                        () -> statement.execute("CREATE TABLE invalid_scale(value GEOMETRY(10, 2))"));
                assertThrows(SQLException.class,
                        () -> statement.execute("CREATE TABLE invalid_default(value GEOMETRY DEFAULT NULL)"));
            }
        }
    }
}
