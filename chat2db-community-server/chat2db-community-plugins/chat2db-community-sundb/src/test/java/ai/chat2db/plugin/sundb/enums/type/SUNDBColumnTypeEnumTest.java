package ai.chat2db.plugin.sundb.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression coverage for null {@code Integer} column metadata during MODIFY
 * column-type generation. {@code TableColumn.columnSize} and {@code nullable}
 * are boxed {@code Integer} and can be null when the driver does not populate
 * them; comparing them with {@code .equals()} dereferences a possibly-null
 * value (see #1968 / {@code DBStructUtils}).
 */
class SUNDBColumnTypeEnumTest {

    @Test
    void buildCreateColumnSqlToleratesNullIntegerMetadataOnModify() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setColumnType("NUMBER");
        oldColumn.setNullable(1);
        // columnSize intentionally left null (missing metadata)

        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setOldName("amount");
        column.setColumnType("NUMBER");
        column.setEditStatus(EditStatusEnum.MODIFY.name());
        column.setOldColumn(oldColumn);
        // current column columnSize / nullable intentionally left null

        String sql = assertDoesNotThrow(() -> SUNDBColumnTypeEnum.NUMBER.buildCreateColumnSql(column));
        assertNotNull(sql);
        assertFalse(sql.contains("NOT NULL"), "missing nullable metadata must not change nullability");
    }
}
