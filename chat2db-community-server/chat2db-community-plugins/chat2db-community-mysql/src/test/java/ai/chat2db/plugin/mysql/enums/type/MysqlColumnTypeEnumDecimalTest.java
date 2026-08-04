package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlColumnTypeEnumDecimalTest {

    private String ddl(MysqlColumnTypeEnum type, Integer size, Integer digits) {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType(type.getColumnType().getTypeName());
        column.setColumnSize(size);
        column.setDecimalDigits(digits);
        column.setNullable(1);
        return type.buildCreateColumnSql(column).trim().replaceAll("\\s+", " ");
    }

    @Test
    void decimalWithPrecisionOnly() {
        assertEquals("`amount` DECIMAL(15) NULL", ddl(MysqlColumnTypeEnum.DECIMAL, 15, null));
        assertEquals("`amount` DECIMAL(15) UNSIGNED NULL", ddl(MysqlColumnTypeEnum.DECIMAL_UNSIGNED, 15, null));
    }

    @Test
    void decimalWithPrecisionAndScale() {
        assertEquals("`amount` DECIMAL(15,2) NULL", ddl(MysqlColumnTypeEnum.DECIMAL, 15, 2));
        assertEquals("`amount` DECIMAL(15,2) UNSIGNED NULL", ddl(MysqlColumnTypeEnum.DECIMAL_UNSIGNED, 15, 2));
    }

    @Test
    void decimalWithBothNull() {
        assertEquals("`amount` DECIMAL NULL", ddl(MysqlColumnTypeEnum.DECIMAL, null, null));
    }

    @Test
    void floatingPointTypesDoNotGainPrecisionOnlySyntax() {
        assertEquals("`amount` FLOAT NULL", ddl(MysqlColumnTypeEnum.FLOAT, 10, null));
        assertEquals("`amount` DOUBLE NULL", ddl(MysqlColumnTypeEnum.DOUBLE, 10, null));
        assertEquals("`amount` FLOAT UNSIGNED NULL", ddl(MysqlColumnTypeEnum.FLOAT_UNSIGNED, 10, null));
        assertEquals("`amount` DOUBLE UNSIGNED NULL", ddl(MysqlColumnTypeEnum.DOUBLE_UNSIGNED, 10, null));
    }
}
