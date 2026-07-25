package ai.chat2db.plugin.sqlite.builder;

import ai.chat2db.community.domain.api.model.metadata.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteBuilderTest {

    @Test
    void shouldReturnEmptySqlWhenTableHasNoChanges() {
        Table oldTable = unchangedTable();
        Table newTable = unchangedTable();

        assertEquals("", new SqliteBuilder().buildAlterTable(oldTable, newTable));
    }

    private static Table unchangedTable() {
        Table table = new Table();
        table.setDatabaseName("main");
        table.setName("users");
        table.setColumnList(List.of());
        table.setIndexList(List.of());
        return table;
    }
}
