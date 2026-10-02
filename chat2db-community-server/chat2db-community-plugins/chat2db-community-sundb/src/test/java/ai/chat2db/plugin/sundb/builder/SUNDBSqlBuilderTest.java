package ai.chat2db.plugin.sundb.builder;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers comment handling in {@link SUNDBSqlBuilder#buildAlterTable(Table, Table)}.
 * Columns added from the table editor never carry an oldColumn snapshot
 * (DbTableServiceImpl.initOldTable skips ADD columns), so the comment diff
 * must tolerate a null oldColumn instead of throwing a NullPointerException.
 */
class SUNDBSqlBuilderTest {

    @Test
    void buildAlterTableAddedColumnWithCommentDoesNotThrowAndEmitsComment() {
        Table oldTable = table();
        Table newTable = table();
        TableColumn added = column(EditStatusEnum.ADD.name(), "user id");
        added.setColumnSize(20);
        newTable.setColumnList(List.of(added));

        String sql = assertDoesNotThrow(() -> new SUNDBSqlBuilder().buildAlterTable(oldTable, newTable));

        assertTrue(sql.contains("ALTER TABLE \"SCH\".\"T1\" ADD (\"C1\" VARCHAR(20)"), sql);
        assertTrue(sql.contains("COMMENT ON COLUMN \"SCH\".\"T1\".\"C1\" IS 'user id'"), sql);
    }

    @Test
    void buildAlterTableModifiedColumnStillEmitsCommentWhenItChanges() {
        Table oldTable = table();
        Table newTable = table();
        TableColumn modified = column(EditStatusEnum.MODIFY.name(), "new comment");
        modified.setOldName("C1");
        modified.setOldColumn(column(null, "old comment"));
        newTable.setColumnList(List.of(modified));

        String sql = new SUNDBSqlBuilder().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("COMMENT ON COLUMN \"SCH\".\"T1\".\"C1\" IS 'new comment'"), sql);
    }

    @Test
    void buildAlterTableModifiedColumnWithUnchangedCommentEmitsNoComment() {
        Table oldTable = table();
        Table newTable = table();
        TableColumn modified = column(EditStatusEnum.MODIFY.name(), "same comment");
        modified.setOldName("C1");
        modified.setOldColumn(column(null, "same comment"));
        newTable.setColumnList(List.of(modified));

        String sql = new SUNDBSqlBuilder().buildAlterTable(oldTable, newTable);

        assertFalse(sql.contains("COMMENT ON COLUMN"), sql);
    }

    private static Table table() {
        Table table = new Table();
        table.setSchemaName("SCH");
        table.setName("T1");
        table.setColumnList(List.of());
        table.setIndexList(List.of());
        return table;
    }

    private static TableColumn column(String editStatus, String comment) {
        TableColumn column = new TableColumn();
        column.setSchemaName("SCH");
        column.setTableName("T1");
        column.setName("C1");
        column.setColumnType("VARCHAR");
        column.setEditStatus(editStatus);
        column.setComment(comment);
        return column;
    }
}
