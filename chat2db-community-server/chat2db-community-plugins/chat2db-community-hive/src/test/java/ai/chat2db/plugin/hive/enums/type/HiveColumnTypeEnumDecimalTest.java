package ai.chat2db.plugin.hive.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiveColumnTypeEnumDecimalTest {

    private String ddl(HiveColumnTypeEnum type, Integer size, Integer digits) {
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
        assertEquals("`amount` DECIMAL(10)", ddl(HiveColumnTypeEnum.DECIMAL, 10, null));
    }

    @Test
    void decimalWithPrecisionAndScale() {
        assertEquals("`amount` DECIMAL(10,2)", ddl(HiveColumnTypeEnum.DECIMAL, 10, 2));
    }

    @Test
    void decimalWithBothNull() {
        assertEquals("`amount` DECIMAL", ddl(HiveColumnTypeEnum.DECIMAL, null, null));
    }

    @Test
    void nonDecimalNumericTypesIgnorePrecisionAndScale() {
        assertEquals("`amount` FLOAT", ddl(HiveColumnTypeEnum.FLOAT, 10, null));
        assertEquals("`amount` FLOAT", ddl(HiveColumnTypeEnum.FLOAT, 10, 2));
        assertEquals("`amount` DOUBLE", ddl(HiveColumnTypeEnum.DOUBLE, 10, null));
        assertEquals("`amount` DOUBLE", ddl(HiveColumnTypeEnum.DOUBLE, 10, 2));
        assertEquals("`amount` TINYINT", ddl(HiveColumnTypeEnum.TINYINT, 10, null));
        assertEquals("`amount` TINYINT", ddl(HiveColumnTypeEnum.TINYINT, 10, 2));
    }
}
