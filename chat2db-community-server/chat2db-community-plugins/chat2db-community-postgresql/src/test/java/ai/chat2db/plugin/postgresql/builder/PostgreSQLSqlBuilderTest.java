package ai.chat2db.plugin.postgresql.builder;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgreSQLSqlBuilderTest {

    @Test
    void shouldUseCtidSubQueryWhenLimitingSingleRowDeleteAndUpdate() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        String where = " where \"a\" = 1 and \"b\" = 2";

        assertEquals("DELETE FROM \"t\" where ctid in (select ctid from \"t\"" + where + " limit 1)",
                builder.appendSingleRowLimit("DELETE", "\"t\"", where, "DELETE FROM \"t\"" + where));
        assertEquals("UPDATE \"t\" set \"a\" = 1 where ctid in (select ctid from \"t\"" + where + " limit 1)",
                builder.appendSingleRowLimit("UPDATE", "\"t\"", where, "UPDATE \"t\" set \"a\" = 1" + where));
    }

    @Test
    void shouldQuoteQualifiedViewNameAndEscapeComment() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        ModifyView view = createView("report\"ing", "sales\"view", "owner's view");

        assertEquals("CREATE VIEW \"report\"\"ing\".\"sales\"\"view\"\n"
                        + "AS \n"
                        + "SELECT 1 ;\n"
                        + "comment on view \"report\"\"ing\".\"sales\"\"view\" is 'owner''s view';",
                builder.buildCreateView(view));
    }

    @Test
    void shouldOmitBlankSchemaFromCreateAndCommentViewNames() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        ModifyView view = createView("   ", "sales_view", "daily sales");

        assertEquals("CREATE VIEW \"sales_view\"\n"
                        + "AS \n"
                        + "SELECT 1 ;\n"
                        + "comment on view \"sales_view\" is 'daily sales';",
                builder.buildCreateView(view));
    }

    @Test
    void shouldEscapeInheritedBuilderPathsAndIgnoreDatabaseQualifier() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        String schema = "analytics\"x";
        String table = "orders\"x";

        assertEquals("SELECT COUNT(1) FROM \"analytics\"\"x\".\"orders\"\"x\"",
                builder.buildSelectCount(null, schema, table));
        assertEquals("SELECT COUNT(1) FROM \"analytics\"\"x\".\"orders\"\"x\"",
                builder.buildSelectCount("ignored_database", schema, table));
        assertEquals("SELECT * FROM \"analytics\"\"x\".\"orders\"\"x\"",
                builder.buildSelectTable("ignored_database", schema, table));
        assertEquals("DROP TABLE \"analytics\"\"x\".\"orders\"\"x\"",
                builder.buildDropTable(new DropTableRequest("ignored_database", schema, table)));
        assertEquals("TRUNCATE TABLE \"analytics\"\"x\".\"orders\"\"x\"",
                builder.buildTruncateTable(new TruncateTableRequest("ignored_database", schema, table)));
    }

    @Test
    void shouldEscapeInheritedUpdateAndTemplateIdentifiers() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        UpdateSqlRequest update = UpdateSqlRequest.builder()
                .databaseName("ignored_database")
                .schemaName("sales\"schema")
                .tableName("orders\"table")
                .row(Map.of("total\"value", "42"))
                .primaryKeyMap(Map.of("order\"id", "7"))
                .build();

        assertEquals("UPDATE \"sales\"\"schema\".\"orders\"\"table\" SET \"total\"\"value\" = 42"
                        + " WHERE \"order\"\"id\" = 7",
                builder.buildUpdate(update));

        Table table = Table.builder()
                .schemaName("sales\"schema")
                .name("orders\"table")
                .columnList(List.of(TableColumn.builder().name("total\"value").build()))
                .build();
        assertEquals("SELECT \"total\"\"value\" FROM \"sales\"\"schema\".\"orders\"\"table\"",
                builder.buildTemplate(table, "SELECT"));
    }

    @Test
    void shouldPreserveCaseOnlyTableRename() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        Table oldTable = Table.builder().schemaName("sales").name("orders")
                .columnList(List.of()).indexList(List.of()).build();
        Table newTable = Table.builder().schemaName("sales").name("Orders")
                .columnList(List.of()).indexList(List.of()).build();

        assertEquals("ALTER TABLE \"sales\".\"orders\"\tRENAME TO \"Orders\";\n",
                builder.buildAlterTable(oldTable, newTable));
    }

    private static ModifyView createView(String schemaName, String viewName, String comment) {
        ModifyView view = new ModifyView();
        view.setSchemaName(schemaName);
        view.setViewName(viewName);
        view.setViewBody("SELECT 1");
        view.setComment(comment);
        return view;
    }
}
