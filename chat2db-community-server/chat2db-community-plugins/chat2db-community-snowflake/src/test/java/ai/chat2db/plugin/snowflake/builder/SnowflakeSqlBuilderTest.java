package ai.chat2db.plugin.snowflake.builder;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeSqlBuilderTest {

    private final SnowflakeSqlBuilder builder = new SnowflakeSqlBuilder();

    @Test
    void buildCreateTableRejectsUnknownColumnTypeWithoutDroppingColumn() {
        Table table = tableWithColumn("UNKNOWN_TYPE");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateTable(table, new TableBuilderConfig()));

        assertTrue(error.getMessage().contains("UNKNOWN_TYPE"));
    }

    @Test
    void buildCreateTableRejectsUnknownIndexTypeWithoutDroppingIndex() {
        Table table = tableWithColumn("VARCHAR");
        TableIndex index = new TableIndex();
        index.setName("idx_users_name");
        index.setType("UNKNOWN_INDEX");
        table.setIndexList(Collections.singletonList(index));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateTable(table, new TableBuilderConfig()));

        assertTrue(error.getMessage().contains("UNKNOWN_INDEX"));
    }

    @Test
    void buildAlterTableDoesNotAlterEqualBoxedIncrementValues() {
        Table oldTable = tableWithColumn("VARCHAR");
        Table newTable = tableWithColumn("VARCHAR");
        oldTable.setIncrementValue(Long.valueOf(1000));
        newTable.setIncrementValue(Long.valueOf(1000));

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertFalse(sql.contains("AUTOINCREMENT"));
    }

    @Test
    void buildCreateTableQuotesSchemaAndTableWithEmbeddedDelimiters() {
        Table table = tableWithColumn("VARCHAR");
        table.setSchemaName("Sales\"Ops");
        table.setName("Order\"Items");

        String sql = builder.buildCreateTable(table, new TableBuilderConfig());

        assertTrue(sql.startsWith("CREATE TABLE \"Sales\"\"Ops\".\"Order\"\"Items\" (\n"), sql);
    }

    @Test
    void buildAlterTableQuotesDroppedColumnWithEmbeddedDelimiter() {
        Table oldTable = tableWithColumn("VARCHAR");
        Table newTable = tableWithColumn("VARCHAR");
        TableColumn droppedColumn = newTable.getColumnList().get(0);
        droppedColumn.setName("Mixed\"Case");
        droppedColumn.setEditStatus(EditStatusEnum.DELETE.name());

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE \"users\"\n\tDROP COLUMN \"Mixed\"\"Case\";", sql);
    }

    @Test
    void buildAlterTableUsesSnowflakeRenameSyntaxAndQuotesBothNames() {
        Table oldTable = tableWithColumn("VARCHAR");
        Table newTable = tableWithColumn("VARCHAR");
        TableColumn renamedColumn = newTable.getColumnList().get(0);
        renamedColumn.setOldName("Old\"Name");
        renamedColumn.setName("New\"Name");
        renamedColumn.setEditStatus(EditStatusEnum.MODIFY.name());

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE \"users\"\n\tRENAME COLUMN \"Old\"\"Name\" TO \"New\"\"Name\";", sql);
    }

    private Table tableWithColumn(String columnType) {
        TableColumn column = new TableColumn();
        column.setName("name");
        column.setColumnType(columnType);

        Table table = new Table();
        table.setName("users");
        table.setColumnList(Collections.singletonList(column));
        table.setIndexList(Collections.emptyList());
        return table;
    }
}
