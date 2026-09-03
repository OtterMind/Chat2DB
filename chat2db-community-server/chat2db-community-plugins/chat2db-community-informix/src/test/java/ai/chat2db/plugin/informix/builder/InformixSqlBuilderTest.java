package ai.chat2db.plugin.informix.builder;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.informix.InformixMetaData;
import ai.chat2db.spi.ISqlBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class InformixSqlBuilderTest {

    @Test
    void metadataReturnsInformixSqlBuilder() {
        ISqlBuilder builder = new InformixMetaData().getSqlBuilder();

        assertInstanceOf(InformixSqlBuilder.class, builder);
    }

    @Test
    void buildAlterTableUsesRenameTableSyntaxWhenNameChanges() {
        InformixSqlBuilder builder = new InformixSqlBuilder();
        Table oldTable = table("orders", List.of());
        Table newTable = table("orders_archive", List.of());

        assertEquals("RENAME TABLE orders TO orders_archive;\n", builder.buildAlterTable(oldTable, newTable));
    }

    @Test
    void buildAlterTableUsesModifyParenthesesWhenColumnChanges() {
        InformixSqlBuilder builder = new InformixSqlBuilder();
        TableColumn column = new TableColumn();
        column.setTableName("orders");
        column.setName("status");
        column.setColumnType("VARCHAR(32)");
        column.setEditStatus(EditStatusEnum.MODIFY.name());

        assertEquals("ALTER TABLE orders MODIFY (status VARCHAR(32));\n",
                builder.buildAlterTable(table("orders", List.of()), table("orders", List.of(column))));
    }

    @Test
    void buildExplainEnablesInformixExplainBeforeSql() {
        InformixSqlBuilder builder = new InformixSqlBuilder();

        assertEquals("SET EXPLAIN ON; SELECT * FROM orders", builder.buildExplain("SELECT * FROM orders"));
    }

    private static Table table(String name, List<TableColumn> columns) {
        return Table.builder()
                .name(name)
                .columnList(columns)
                .indexList(List.of())
                .build();
    }
}
