package ai.chat2db.plugin.snowflake.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnowflakeColumnTypeEnumDecimalTest {

    private String ddl(SnowflakeColumnTypeEnum type, Integer size, Integer digits) {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType(type.getColumnType().getTypeName());
        column.setColumnSize(size);
        column.setDecimalDigits(digits);
        column.setNullable(1);
        return type.buildCreateColumnSql(column).trim().replaceAll("\\s+", " ");
    }

    @Test
    void fixedPointTypesSupportPrecisionOnly() {
        assertEquals("\"amount\" NUMBER(10)", ddl(SnowflakeColumnTypeEnum.NUMBER, 10, null));
        assertEquals("\"amount\" DECIMAL(10)", ddl(SnowflakeColumnTypeEnum.DECIMAL, 10, null));
        assertEquals("\"amount\" NUMERIC(10)", ddl(SnowflakeColumnTypeEnum.NUMERIC, 10, null));
    }

    @Test
    void fixedPointTypesSupportPrecisionAndScale() {
        assertEquals("\"amount\" NUMBER(10,2)", ddl(SnowflakeColumnTypeEnum.NUMBER, 10, 2));
        assertEquals("\"amount\" DECIMAL(10,2)", ddl(SnowflakeColumnTypeEnum.DECIMAL, 10, 2));
        assertEquals("\"amount\" NUMERIC(10,2)", ddl(SnowflakeColumnTypeEnum.NUMERIC, 10, 2));
    }

    @Test
    void fixedPointTypesWithoutPrecisionRemainBare() {
        assertEquals("\"amount\" NUMBER", ddl(SnowflakeColumnTypeEnum.NUMBER, null, null));
        assertEquals("\"amount\" DECIMAL", ddl(SnowflakeColumnTypeEnum.DECIMAL, null, null));
        assertEquals("\"amount\" NUMERIC", ddl(SnowflakeColumnTypeEnum.NUMERIC, null, null));
    }

    @Test
    void integerAndFloatingAliasesIgnorePrecisionAndScale() {
        for (SnowflakeColumnTypeEnum type : List.of(
            SnowflakeColumnTypeEnum.INT,
            SnowflakeColumnTypeEnum.INTEGER,
            SnowflakeColumnTypeEnum.BIGINT,
            SnowflakeColumnTypeEnum.SMALLINT,
            SnowflakeColumnTypeEnum.TINYINT,
            SnowflakeColumnTypeEnum.BYTEINT,
            SnowflakeColumnTypeEnum.FLOAT,
            SnowflakeColumnTypeEnum.DOUBLE
        )) {
            String expected = "\"amount\" " + type.getColumnType().getTypeName();
            assertEquals(expected, ddl(type, 10, null));
            assertEquals(expected, ddl(type, 10, 2));
        }
    }
}
