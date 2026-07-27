package ai.chat2db.plugin.sqlserver.builder;

import ai.chat2db.community.domain.api.model.view.ModifyView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlServerSqlBuilderTest {

    @Test
    void shouldKeepGoDelimiterForShowplanXmlBatch() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();

        String sql = "SELECT * FROM uf_wtbhb WHERE lcid=1208045;";

        assertEquals("SET SHOWPLAN_XML ON;\nGO\n"
                + "SELECT * FROM uf_wtbhb WHERE lcid=1208045;\n"
                + "GO\nSET SHOWPLAN_XML OFF;", builder.dql().buildExplain(sql));
    }

    @Test
    void shouldUseTopWhenLimitingSingleRowDeleteAndUpdate() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        String where = " where [a] = 1 and [b] = 2";

        assertEquals("DELETE TOP (1) FROM [t]" + where,
                builder.appendSingleRowLimit("DELETE", "[t]", where, "DELETE FROM [t]" + where));
        assertEquals("UPDATE TOP (1) [t] set [a] = 1" + where,
                builder.appendSingleRowLimit("UPDATE", "[t]", where, "UPDATE [t] set [a] = 1" + where));
    }

    @Test
    void shouldIncludeSchemaWhenDatabaseNameIsBlank() {
        ExposedSqlServerSqlBuilder builder = new ExposedSqlServerSqlBuilder();

        assertEquals("[dbo].[orders]", builder.tableName(null, "dbo", "orders"));
        assertEquals("[analytics].[dbo].[orders]", builder.tableName("analytics", "dbo", "orders"));
    }

    @Test
    void shouldQuoteAndEscapeQualifiedViewName() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        ModifyView view = new ModifyView();
        view.setSchemaName("order] schema");
        view.setViewName("select] view");
        view.setViewBody("SELECT 1");
        view.setComment("owner's view");

        assertEquals("CREATE VIEW [order]] schema].[select]] view]\n"
                        + "AS \n"
                        + "SELECT 1 ;\n"
                        + "exec sp_addextendedproperty 'MS_Description', 'owner''s view', 'SCHEMA', "
                        + "'order] schema', 'VIEW', 'select] view'",
                builder.buildCreateView(view));
    }

    private static final class ExposedSqlServerSqlBuilder extends SqlServerSqlBuilder {
        private String tableName(String databaseName, String schemaName, String tableName) {
            StringBuilder script = new StringBuilder();
            buildTableName(databaseName, schemaName, tableName, script);
            return script.toString();
        }
    }
}
