package ai.chat2db.plugin.postgresql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSQLColumnTypeEnumNumericTest {

    @Test
    void numericWithoutPrecisionIgnoresOrphanScale() {
        assertEquals("\"amount\" NUMERIC NULL", ddl(PostgreSQLColumnTypeEnum.NUMERIC, null, 2));
        assertEquals("\"amount\" DECIMAL NULL", ddl(PostgreSQLColumnTypeEnum.DECIMAL, null, 2));
    }

    @Test
    void numericSupportsPrecisionWithOptionalScale() {
        assertEquals("\"amount\" NUMERIC(15) NULL", ddl(PostgreSQLColumnTypeEnum.NUMERIC, 15, null));
        assertEquals("\"amount\" NUMERIC(15,2) NULL", ddl(PostgreSQLColumnTypeEnum.NUMERIC, 15, 2));
    }

    private static String ddl(PostgreSQLColumnTypeEnum type, Integer precision, Integer scale) {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType(type.getColumnType().getTypeName());
        column.setColumnSize(precision);
        column.setDecimalDigits(scale);
        column.setNullable(1);
        return type.buildCreateColumnSql(column).trim().replaceAll("\\s+", " ");
    }
}
