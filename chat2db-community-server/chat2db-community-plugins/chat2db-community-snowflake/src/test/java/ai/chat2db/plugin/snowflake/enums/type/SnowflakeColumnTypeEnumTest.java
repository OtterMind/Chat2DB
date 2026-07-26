package ai.chat2db.plugin.snowflake.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression coverage for null {@code Integer} column metadata during MODIFY
 * column-type generation. {@code TableColumn.columnSize}, {@code decimalDigits},
 * and {@code nullable} are boxed {@code Integer} and can be null when the driver
 * does not populate them; comparing them with {@code .equals()} dereferences a
 * possibly-null value (see #1968 / {@code DBStructUtils}).
 */
class SnowflakeColumnTypeEnumTest {

    @Test
    void buildCreateColumnSqlToleratesNullIntegerMetadataOnModify() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setTableName("users");
        // columnSize / decimalDigits / nullable intentionally left null (missing metadata)

        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setOldName("amount");
        column.setTableName("users");
        column.setColumnType("NUMBER");
        column.setEditStatus(EditStatusEnum.MODIFY.name());
        column.setOldColumn(oldColumn);

        String sql = assertDoesNotThrow(() -> SnowflakeColumnTypeEnum.NUMBER.buildCreateColumnSql(column));
        assertNotNull(sql);
    }

    @Test
    void buildCreateColumnSqlToleratesNullNullableMetadataOnModify() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setTableName("users");
        oldColumn.setNullable(1);

        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setOldName("amount");
        column.setTableName("users");
        column.setColumnType("NUMBER");
        column.setEditStatus(EditStatusEnum.MODIFY.name());
        column.setOldColumn(oldColumn);
        // current column nullable intentionally left null (missing metadata)

        String sql = assertDoesNotThrow(() -> SnowflakeColumnTypeEnum.NUMBER.buildCreateColumnSql(column));
        assertNotNull(sql);
        assertFalse(sql.contains("NOT NULL"), "missing nullable metadata must not change nullability");
    }
}
