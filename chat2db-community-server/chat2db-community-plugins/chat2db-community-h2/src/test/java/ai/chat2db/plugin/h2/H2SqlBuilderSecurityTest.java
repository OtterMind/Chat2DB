package ai.chat2db.plugin.h2;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.h2.builder.H2SqlBuilder;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SqlBuilderSecurityTest {

    private static final String EVIL = "T\"; DROP TABLE U; --";
    private static final String EVIL_QUOTED = "\"T\"\"; DROP TABLE U; --\"";
    private static final String COMMENT_ATTACK = "x'); DROP TABLE U; --";

    private final H2SqlBuilder builder = new H2SqlBuilder();

    @Test
    void buildCreateTableEscapesNamesCommentsAndIndexes() {
        Table table = new Table();
        table.setName(EVIL);
        TableColumn column = new TableColumn();
        column.setName("C\"; X");
        column.setTableName(EVIL);
        column.setColumnType("INTEGER");
        column.setNullable(0);
        column.setComment(COMMENT_ATTACK);
        table.setColumnList(List.of(column));
        TableIndex index = new TableIndex();
        index.setName("I\"; X");
        index.setTableName(EVIL);
        index.setType("Normal");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C\"; X");
        index.setColumnList(List.of(indexColumn));
        table.setIndexList(List.of(index));

        String sql = builder.buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertEquals("CREATE TABLE " + EVIL_QUOTED + " (\n"
                + "\t \"C\"\"; X\" INTEGER NOT NULL\n"
                + ");\n"
                + "COMMENT ON COLUMN " + EVIL_QUOTED + ".\"C\"\"; X\" IS 'x''); DROP TABLE U; --';\n"
                + "CREATE INDEX \"I\"\"; X\" ON " + EVIL_QUOTED + " (\"C\"\"; X\");\n",
            sql);
        assertFalse(sql.contains("ON T\";"), "index table name must stay inside the quoted identifier");
    }

    @Test
    void buildAlterTableEscapesRenameCommentsColumnsAndIndexes() {
        Table oldTable = new Table();
        oldTable.setName("O\"; X");
        Table newTable = new Table();
        newTable.setName("N\"; X");
        newTable.setComment(COMMENT_ATTACK);

        TableColumn add = column("A\"; X", "ADD");
        TableColumn delete = column("D\"; X", "DELETE");
        TableColumn modify = column("M\"; X", "MODIFY");
        TableColumn commentOnly = column("K\"; X", "COMMENTED");
        commentOnly.setComment(COMMENT_ATTACK);
        newTable.setColumnList(List.of(add, delete, modify, commentOnly));

        TableIndex dropIndex = index("I\"; X", "DELETE");
        TableIndex addIndex = index("J\"; X", "ADD");
        newTable.setIndexList(List.of(dropIndex, addIndex));

        String sql = builder.buildAlterTable(oldTable, newTable);

        assertEquals("ALTER TABLE \"O\"\"; X\" RENAME TO \"N\"\"; X\";\n"
                + "COMMENT ON TABLE \"N\"\"; X\" IS 'x''); DROP TABLE U; --';\n"
                + "ALTER TABLE " + EVIL_QUOTED + " ADD COLUMN \"A\"\"; X\" INTEGER;\n"
                + "ALTER TABLE " + EVIL_QUOTED + " DROP COLUMN \"D\"\"; X\";\n"
                + "ALTER TABLE " + EVIL_QUOTED + " MODIFY COLUMN \"M\"\"; X\" INTEGER;\n"
                + "COMMENT ON COLUMN " + EVIL_QUOTED + ".\"K\"\"; X\" IS 'x''); DROP TABLE U; --';\n\n"
                + "DROP INDEX \"I\"\"; X\";\n"
                + "CREATE INDEX \"J\"\"; X\" ON " + EVIL_QUOTED + " (\"C\"\"; X\");\n",
            sql);
    }

    private static TableColumn column(String name, String editStatus) {
        TableColumn column = new TableColumn();
        column.setName(name);
        column.setTableName(EVIL);
        column.setColumnType("INTEGER");
        column.setEditStatus(editStatus);
        return column;
    }

    private static TableIndex index(String name, String editStatus) {
        TableIndex index = new TableIndex();
        index.setName(name);
        index.setTableName(EVIL);
        index.setType("Normal");
        index.setEditStatus(editStatus);
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C\"; X");
        index.setColumnList(List.of(indexColumn));
        return index;
    }

    @Test
    void buildTemplateEscapesTableAndColumnNames() {
        Table table = new Table();
        table.setName(EVIL);
        TableColumn column = new TableColumn();
        column.setName("C\"; X");
        table.setColumnList(List.of(column));

        assertEquals("INSERT INTO " + EVIL_QUOTED + " (\"C\"\"; X\") VALUES ( )",
            builder.buildTemplate(table, "INSERT"));
        assertEquals("UPDATE " + EVIL_QUOTED + " set \"C\"\"; X\" =   where ",
            builder.buildTemplate(table, "UPDATE"));
        assertEquals("DELETE FROM " + EVIL_QUOTED + " where ",
            builder.buildTemplate(table, "DELETE"));
        assertEquals("SELECT \"C\"\"; X\" FROM where" + EVIL_QUOTED,
            builder.buildTemplate(table, "SELECT"));
    }

    @Test
    void buildUpdateEscapesTableAndColumnKeys() {
        UpdateSqlRequest request = new UpdateSqlRequest();
        request.setDatabaseName("D\"; X");
        request.setSchemaName("S\"; X");
        request.setTableName(EVIL);
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put("K\"; X", "1");
        request.setRow(row);
        LinkedHashMap<String, String> primaryKey = new LinkedHashMap<>();
        primaryKey.put("P\"; X", "'v'");
        request.setPrimaryKeyMap(primaryKey);

        assertEquals("UPDATE \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED
                + " SET \"K\"\"; X\" = 1 WHERE \"P\"\"; X\" = 'v'",
            builder.buildUpdate(request));
    }

    @Test
    void databaseAndSchemaDdlEscapesNames() {
        Database database = new Database();
        database.setName("DB\"; X");
        assertEquals("CREATE DATABASE \"DB\"\"; X\"", builder.buildCreateDatabase(database));
        assertEquals("DROP DATABASE \"DB\"\"; X\"", builder.buildDropDatabase("DB\"; X"));
        assertEquals("USE \"DB\"\"; X\"", builder.buildUseDatabase("DB\"; X"));
        assertEquals("DROP SCHEMA \"S\"\"; X\"", builder.buildDropSchema("S\"; X"));
    }

    @Test
    void qualifiedIdentifierPathsEscapeEveryPart() {
        assertEquals("SELECT COUNT(1) FROM \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED,
            builder.buildSelectCount("D\"; X", "S\"; X", EVIL));
        DropTableRequest dropTable = new DropTableRequest();
        dropTable.setDatabaseName("D\"; X");
        dropTable.setSchemaName("S\"; X");
        dropTable.setTableName(EVIL);
        assertEquals("DROP TABLE \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED,
            builder.buildDropTable(dropTable));
        TruncateTableRequest truncate = new TruncateTableRequest();
        truncate.setDatabaseName("D\"; X");
        truncate.setSchemaName("S\"; X");
        truncate.setTableName(EVIL);
        assertEquals("TRUNCATE TABLE \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED,
            builder.buildTruncateTable(truncate));
        assertEquals("SELECT * FROM \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED,
            builder.buildSelectTable("D\"; X", "S\"; X", EVIL));
    }

    @Test
    void buildCreateTableRejectsHostileColumnType() {
        Table table = new Table();
        table.setName("T");
        TableColumn column = new TableColumn();
        column.setName("C");
        column.setColumnType("INTEGER); DROP TABLE U; --");
        column.setNullable(0);
        table.setColumnList(List.of(column));

        assertThrows(IllegalArgumentException.class,
            () -> builder.buildCreateTable(table, TableBuilderConfig.defaultConfig()));
    }

    @Test
    void buildAlterTableRejectsHostileColumnType() {
        Table oldTable = new Table();
        oldTable.setName("T");
        Table newTable = new Table();
        newTable.setName("T");
        newTable.setColumnList(List.of(column("C", "ADD")));
        newTable.getColumnList().get(0).setColumnType("INTEGER; DROP TABLE U; --");
        newTable.setIndexList(List.of());

        assertThrows(IllegalArgumentException.class, () -> builder.buildAlterTable(oldTable, newTable));
    }

    @Test
    void buildInsertEscapesTableAndColumnNames() {
        SingleInsertSqlRequest request = new SingleInsertSqlRequest();
        request.setDatabaseName("D\"; X");
        request.setSchemaName("S\"; X");
        request.setTableName(EVIL);
        request.setColumnList(List.of("C\"; X"));
        request.setValueList(List.of("'v'"));

        assertEquals("INSERT INTO \"D\"\"; X\".\"S\"\"; X\"." + EVIL_QUOTED
                + " (\"C\"\"; X\")  VALUES ('v')",
            builder.buildInsert(request));
    }

    @Test
    void generatedDdlAndLegalDefaultExpressionsExecuteOnH2() throws Exception {
        Table table = new Table();
        table.setName("SAFE_TABLE");
        TableColumn column = new TableColumn();
        column.setName("VALUE");
        column.setColumnType("INTEGER");
        column.setNullable(0);
        table.setColumnList(List.of(column));

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:escaping_" + System.nanoTime());
             Statement statement = connection.createStatement()) {
            statement.execute(builder.buildCreateTable(table, TableBuilderConfig.defaultConfig()));

            String timestampDefault = H2SqlGuards.escapeColumnDefault("CURRENT_TIMESTAMP");
            String stringDefault = H2SqlGuards.escapeColumnDefault("'O''Brien'");
            statement.execute("CREATE TABLE \"DEFAULTS\" (\"CREATED_AT\" TIMESTAMP DEFAULT "
                + timestampDefault + ", \"LABEL\" VARCHAR(64) DEFAULT " + stringDefault + ")");
            statement.executeUpdate("INSERT INTO \"DEFAULTS\" DEFAULT VALUES");

            try (ResultSet resultSet = statement.executeQuery("SELECT \"CREATED_AT\", \"LABEL\" FROM \"DEFAULTS\"")) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getTimestamp(1) != null);
                assertEquals("O'Brien", resultSet.getString(2));
            }

            assertThrows(IllegalArgumentException.class,
                () -> H2SqlGuards.escapeColumnDefault("0, INJECTED INTEGER"));
            try (ResultSet injected = connection.getMetaData().getColumns(null, null, "SAFE_TABLE", "INJECTED")) {
                assertFalse(injected.next());
            }
        }
    }
}
