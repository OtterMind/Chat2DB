package ai.chat2db.plugin.sqlserver.enums.type;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerColumnTypeEnumTest {

    @Test
    void shouldNotAppendPrecisionToTimestamp() {
        TableColumn column = new TableColumn();
        column.setName("row_version");
        column.setColumnSize(8);

        String sql = SqlServerColumnTypeEnum.TIMESTAMP.buildCreateColumnSql(column);

        assertTrue(sql.contains("TIMESTAMP"));
        assertFalse(sql.contains("TIMESTAMP("));
    }

    @Test
    void shouldQuoteQualifiedOldColumnAndEscapeNewNameWhenRenaming() {
        TableColumn oldColumn = new TableColumn();
        TableColumn column = modifiedColumn(oldColumn);
        column.setSchemaName("sales] ops");
        column.setTableName("order] details");
        column.setOldName("old] name");
        column.setName("new'name");

        String sql = SqlServerColumnTypeEnum.INT.buildModifyColumn(column);

        assertTrue(sql.startsWith(
                "exec sp_rename '[sales]] ops].[order]] details].[old]] name]','new''name','COLUMN' \ngo\n"));
        assertTrue(sql.contains("ALTER TABLE [sales]] ops].[order]] details] ALTER COLUMN [new'name]"));
    }

    @Test
    void shouldKeepGoSeparatorOnItsOwnLineWhenCommentScriptIsConcatenated() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setComment("old comment");
        TableColumn column = modifiedColumn(oldColumn);
        column.setComment("new comment");

        String sql = SqlServerColumnTypeEnum.INT.buildModifyColumn(column) + "ALTER TABLE [dbo].[next_table]";

        assertTrue(sql.contains("\n go\nALTER TABLE [dbo].[next_table]"));
        assertFalse(sql.contains("goALTER TABLE"));
    }

    @Test
    void shouldEscapeCommentProcedureStringArguments() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setComment("old comment");
        TableColumn column = modifiedColumn(oldColumn);
        column.setSchemaName("sales' ops");
        column.setTableName("order' details");
        column.setName("status' code");
        column.setComment("owner's note");

        String sql = SqlServerColumnTypeEnum.INT.buildModifyColumn(column);

        assertTrue(sql.contains("'SCHEMA', N'sales'' ops'"));
        assertTrue(sql.contains("'TABLE', N'order'' details'"));
        assertTrue(sql.contains("'COLUMN', N'status'' code'"));
        assertTrue(sql.contains("'MS_Description', N'owner''s note'"));
    }

    private static TableColumn modifiedColumn(TableColumn oldColumn) {
        TableColumn column = new TableColumn();
        column.setOldColumn(oldColumn);
        column.setSchemaName("dbo");
        column.setTableName("orders");
        column.setOldName("status");
        column.setName("status");
        column.setColumnType("INT");
        column.setEditStatus(EditStatusEnum.MODIFY.name());
        return column;
    }
}
