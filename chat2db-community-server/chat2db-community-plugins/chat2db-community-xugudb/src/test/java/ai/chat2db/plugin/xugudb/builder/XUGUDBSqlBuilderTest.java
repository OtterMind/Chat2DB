package ai.chat2db.plugin.xugudb.builder;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XUGUDBSqlBuilderTest {

    private static final String SCHEMA = "app";
    private static final String TABLE = "sample_table";

    private final XUGUDBSqlBuilder builder = new XUGUDBSqlBuilder();

    @Test
    void shouldSkipUnknownColumnAndIndexTypesWhenCreatingTable() {
        Table table = Table.builder()
                .schemaName(SCHEMA)
                .name(TABLE)
                .columnList(List.of(
                        column("known_column", "INTEGER", null),
                        column("unknown_column", "UNSUPPORTED_COLUMN_TYPE", null)))
                .indexList(List.of(
                        index("known_index", "Normal", null, "known_column"),
                        index("unknown_index", "UNSUPPORTED_INDEX_TYPE", null, "unknown_column")))
                .build();

        String sql = assertDoesNotThrow(
                () -> builder.buildCreateTable(table, TableBuilderConfig.defaultConfig()));

        assertTrue(sql.contains("\"known_column\" INTEGER"), sql);
        assertTrue(sql.contains("\"known_index\""), sql);
        assertFalse(sql.contains("unknown_column"), sql);
        assertFalse(sql.contains("unknown_index"), sql);
    }

    @Test
    void shouldSkipUnknownColumnAndIndexTypesWhenAlteringTable() {
        Table oldTable = table(List.of(), List.of());
        Table newTable = table(
                List.of(
                        column("known_column", "INTEGER", EditStatusEnum.ADD.name()),
                        column("unknown_column", "UNSUPPORTED_COLUMN_TYPE", EditStatusEnum.ADD.name())),
                List.of(
                        index("known_index", "Normal", EditStatusEnum.ADD.name(), "known_column"),
                        index("unknown_index", "UNSUPPORTED_INDEX_TYPE", EditStatusEnum.ADD.name(), "unknown_column")));

        String sql = assertDoesNotThrow(() -> builder.buildAlterTable(oldTable, newTable));

        assertTrue(sql.contains("ADD (\"known_column\" INTEGER"), sql);
        assertTrue(sql.contains("\"known_index\""), sql);
        assertFalse(sql.contains("unknown_column"), sql);
        assertFalse(sql.contains("unknown_index"), sql);
    }

    private static Table table(List<TableColumn> columns, List<TableIndex> indexes) {
        return Table.builder()
                .schemaName(SCHEMA)
                .name(TABLE)
                .columnList(columns)
                .indexList(indexes)
                .build();
    }

    private static TableColumn column(String name, String type, String editStatus) {
        return TableColumn.builder()
                .schemaName(SCHEMA)
                .tableName(TABLE)
                .name(name)
                .columnType(type)
                .nullable(1)
                .editStatus(editStatus)
                .build();
    }

    private static TableIndex index(String name, String type, String editStatus, String columnName) {
        return TableIndex.builder()
                .schemaName(SCHEMA)
                .tableName(TABLE)
                .name(name)
                .type(type)
                .editStatus(editStatus)
                .columnList(List.of(TableIndexColumn.builder()
                        .columnName(columnName)
                        .build()))
                .build();
    }
}
