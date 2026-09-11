package ai.chat2db.plugin.sqlserver.builder;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.plugin.sqlserver.SqlServerPlugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
    void shouldAddOuterOrderByWhenOnlyWindowFunctionIsOrdered() {
        String sql = "SELECT ROW_NUMBER() OVER (ORDER BY id) AS row_no, id FROM users";

        assertEquals(sql + "\n ORDER BY (SELECT NULL)\n OFFSET 0 ROWS  FETCH NEXT 10 ROWS ONLY",
                buildPageLimit(sql));
    }

    @Test
    void shouldAddOuterOrderByWhenOnlyNestedQueryIsOrdered() {
        String sql = "SELECT id FROM (SELECT TOP 10 id FROM users ORDER BY id) recent_users";

        assertEquals(sql + "\n ORDER BY (SELECT NULL)\n OFFSET 0 ROWS  FETCH NEXT 10 ROWS ONLY",
                buildPageLimit(sql));
    }

    @Test
    void shouldIgnoreOrderByInStringAndCommentWhenPaginating() {
        String sql = "SELECT 'order by' AS label FROM users -- order by is not an outer clause";

        assertEquals(sql + "\n ORDER BY (SELECT NULL)\n OFFSET 0 ROWS  FETCH NEXT 10 ROWS ONLY",
                buildPageLimit(sql));
    }

    @Test
    void shouldKeepExistingOuterOrderByWhenPaginating() {
        String sql = "SELECT id FROM users ORDER /* keep deterministic */\n BY id";

        assertEquals(sql + "\n OFFSET 0 ROWS  FETCH NEXT 10 ROWS ONLY", buildPageLimit(sql));
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

    @Test
    void shouldUseExactEqualityForWildcardsInCopiedWhereClause() {
        assertEquals("WHERE value = N'50%_off'", buildCopyWhere("VARCHAR", "50%_off"));
    }

    @Test
    void shouldEscapeSingleQuoteInExactCopiedWhereClause() {
        assertEquals("WHERE value = N'O''Brien'", buildCopyWhere("VARCHAR", "O'Brien"));
    }

    @Test
    void shouldUseExactEqualityAndUnicodeLiteralForNTypes() {
        for (String columnType : List.of("NCHAR", "NVARCHAR")) {
            assertEquals("WHERE value = N'\u6587\u5b57_100%'", buildCopyWhere(columnType, "\u6587\u5b57_100%"), columnType);
        }
    }

    @Test
    void shouldCastLegacyTextTypesBeforeExactComparison() {
        assertEquals("WHERE CAST(value AS VARCHAR(MAX)) = N'50%_off'",
                buildCopyWhere("TEXT", "50%_off"));
        assertEquals("WHERE CAST(value AS NVARCHAR(MAX)) = N'\u6587\u5b57_100%'",
                buildCopyWhere("NTEXT", "\u6587\u5b57_100%"));
    }

    @Test
    void shouldCastLegacyTextForSameColumnInClause() {
        assertEquals("WHERE CAST(value AS VARCHAR(MAX)) IN (N'first', N'second')",
                buildCopyWhere("TEXT", List.of("first", "second")));
    }

    @Test
    void shouldKeepNullPredicateOnTheUncastColumn() {
        assertEquals("WHERE value IS NULL", buildCopyWhere("TEXT", (String) null));
        assertEquals("WHERE value IS NULL OR CAST(value AS NVARCHAR(MAX)) IN (N'next')",
                buildCopyWhere("NTEXT", Arrays.asList(null, "next")));
    }

    @Test
    void shouldCastLegacyTextOnlyForNonNullCompositeComparisons() {
        ResultOperation operation = new ResultOperation();
        operation.setType(SQLConstants.WHERE_KEYWORD);
        operation.setDataList(Arrays.asList("50%_off", null));
        operation.setSelectCols(List.of(0, 1));

        assertEquals("WHERE CAST(legacy_text AS VARCHAR(MAX)) = N'50%_off' AND label IS NULL"
                        + SQLConstants.LINE_SEPARATOR,
                buildCopyWhere(List.of(
                        Header.builder().name("legacy_text").columnType("TEXT").build(),
                        Header.builder().name("label").columnType("NVARCHAR").build()),
                        List.of(operation)));
    }

    private static String buildCopyWhere(String columnType, String value) {
        return buildCopyWhere(columnType, Collections.singletonList(value));
    }

    private static String buildPageLimit(String sql) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("SQLSERVER");
        connectInfo.setDbVersion("15.0");
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
        try {
            return new SqlServerSqlBuilder().buildPageLimit(PageLimitRequest.builder()
                    .sql(sql)
                    .offset(0)
                    .pageNo(1)
                    .pageSize(10)
                    .build());
        } finally {
            Chat2DBContext.removeContext();
        }
    }

    private static String buildCopyWhere(String columnType, List<String> values) {
        List<ResultOperation> operations = values.stream().map(value -> {
            ResultOperation operation = new ResultOperation();
            operation.setType(SQLConstants.WHERE_KEYWORD);
            operation.setDataList(Collections.singletonList(value));
            operation.setSelectCols(List.of(0));
            return operation;
        }).toList();
        return buildCopyWhere(List.of(Header.builder()
                .name("value")
                .columnType(columnType)
                .build()), operations);
    }

    private static String buildCopyWhere(List<Header> headers, List<ResultOperation> operations) {
        IPlugin previousPlugin = Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", new SqlServerPlugin());
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("SQLSERVER");
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);

        try {
            QueryResponse queryResponse = new QueryResponse();
            queryResponse.setHeaderList(headers);
            queryResponse.setOperations(operations);
            return new SqlServerSqlBuilder().buildCopyByQueryResult(queryResponse);
        } finally {
            Chat2DBContext.removeContext();
            if (previousPlugin == null) {
                Chat2DBContext.PLUGIN_MAP.remove("SQLSERVER");
            } else {
                Chat2DBContext.PLUGIN_MAP.put("SQLSERVER", previousPlugin);
            }
        }
    }

    private static final class ExposedSqlServerSqlBuilder extends SqlServerSqlBuilder {
        private String tableName(String databaseName, String schemaName, String tableName) {
            StringBuilder script = new StringBuilder();
            buildTableName(databaseName, schemaName, tableName, script);
            return script.toString();
        }
    }
}
