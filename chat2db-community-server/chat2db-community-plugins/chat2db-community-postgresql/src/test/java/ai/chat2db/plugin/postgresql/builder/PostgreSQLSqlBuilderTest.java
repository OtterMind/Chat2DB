package ai.chat2db.plugin.postgresql.builder;

import ai.chat2db.community.domain.api.model.view.ModifyView;
import org.junit.jupiter.api.Test;

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

    private static ModifyView createView(String schemaName, String viewName, String comment) {
        ModifyView view = new ModifyView();
        view.setSchemaName(schemaName);
        view.setViewName(viewName);
        view.setViewBody("SELECT 1");
        view.setComment(comment);
        return view;
    }
}
