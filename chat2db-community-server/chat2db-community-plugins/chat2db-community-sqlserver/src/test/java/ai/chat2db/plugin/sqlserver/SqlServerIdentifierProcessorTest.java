package ai.chat2db.plugin.sqlserver;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.plugin.sqlserver.builder.SqlServerSqlBuilder;
import ai.chat2db.plugin.sqlserver.constant.SQLConstant;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerColumnTypeEnum;
import ai.chat2db.plugin.sqlserver.enums.type.SqlServerIndexTypeEnum;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlServerIdentifierProcessorTest {

    @Test
    void shouldDoubleSingleQuotesInStringLiterals() {
        assertEquals("O''Brien", SqlServerIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertNull(SqlServerIdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("plain", SqlServerIdentifierProcessor.INSTANCE.escapeString("plain"));
    }

    @Test
    void shouldDoubleClosingBracketsInQuotedIdentifiers() {
        assertEquals("[weird]]name]", SqlServerIdentifierProcessor.INSTANCE.quoteIdentifier("weird]name"));
        assertEquals("a]]b", SqlServerIdentifierProcessor.escapeIdentifier("a]b"));
        assertEquals("", SqlServerIdentifierProcessor.escapeIdentifier(null));
    }

    @Test
    void shouldPassThroughNullBlankAndPlainIdentifiersConditionally() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifier(null));
        assertEquals("", processor.quoteIdentifier(""));
        assertEquals(" ", processor.quoteIdentifier(" "));
        assertEquals("users", processor.quoteIdentifier("users"));
        assertEquals("dbo", processor.quoteIdentifier("dbo"));
        assertEquals("order_details2", processor.quoteIdentifier("order_details2"));
    }

    @Test
    void shouldQuoteReservedKeywordsAndInvalidIdentifiersConditionally() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        assertEquals("[SELECT]", processor.quoteIdentifier("SELECT"));
        assertEquals("[select]", processor.quoteIdentifier("select"));
        assertEquals("[USER]", processor.quoteIdentifier("USER"));
        assertEquals("[weird name]", processor.quoteIdentifier("weird name"));
        assertEquals("[[users]]]", processor.quoteIdentifier("[users]"));
        assertEquals("[a]]b]", processor.quoteIdentifier("a]b"));
    }

    @Test
    void shouldDelegateVersionedOverloadToConditionalQuote() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifier(null, null, null));
        assertEquals("users", processor.quoteIdentifier("users", 15, 0));
        assertEquals("[weird name]", processor.quoteIdentifier("weird name", 15, 0));
    }

    @Test
    void shouldAlwaysQuoteWithQuoteIdentifierAlways() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierAlways(null));
        assertEquals("[users]", processor.quoteIdentifierAlways("users"));
        assertEquals("[a]]b]", processor.quoteIdentifierAlways("a]b"));
        assertEquals("[[users]]]", processor.quoteIdentifierAlways("[users]"));
        assertEquals("[]", processor.quoteIdentifierAlways(""));
    }

    @Test
    void shouldKeepConditionalSemanticsWithQuoteIdentifierIgnoreCase() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierIgnoreCase(null));
        assertEquals("users", processor.quoteIdentifierIgnoreCase("users"));
        assertEquals("MixedCase", processor.quoteIdentifierIgnoreCase("MixedCase"));
        assertEquals("[select]", processor.quoteIdentifierIgnoreCase("select"));
        assertEquals("[a]]b]", processor.quoteIdentifierIgnoreCase("a]b"));
    }

    @Test
    void shouldNeutralizeMaliciousCommentAndNamesInCreateTable() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        Table table = new Table();
        table.setSchemaName("dbo");
        table.setName("users];DROP TABLE t;--");
        table.setComment("x'; DROP TABLE users;--");
        TableColumn column = new TableColumn();
        column.setName("id");
        column.setColumnType("INT");
        column.setNullable(1);
        table.setColumnList(List.of(column));
        table.setIndexList(List.of());

        String script = builder.buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(script.contains("CREATE TABLE [dbo].[users]];DROP TABLE t;--] ("), script);
        assertTrue(script.contains("'x''; DROP TABLE users;--'"), script);
        assertFalse(script.contains("'x'; DROP"), script);
    }

    @Test
    void shouldNeutralizeMaliciousNamesInIndexScriptAndComment() {
        TableIndex tableIndex = new TableIndex();
        tableIndex.setSchemaName("s];DROP");
        tableIndex.setTableName("t");
        tableIndex.setName("ix");
        tableIndex.setType("NONCLUSTERED");
        tableIndex.setComment("c';DROP--");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("id");
        indexColumn.setAscOrDesc("ASC");
        tableIndex.setColumnList(List.of(indexColumn));

        String script = SqlServerIndexTypeEnum.NONCLUSTERED.buildIndexScript(tableIndex);
        assertTrue(script.contains("ON [s]];DROP].[t]"), script);

        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        Table table = new Table();
        table.setSchemaName("dbo");
        table.setName("t");
        TableColumn column = new TableColumn();
        column.setName("id");
        column.setColumnType("INT");
        column.setNullable(1);
        table.setColumnList(List.of(column));
        table.setIndexList(new ArrayList<>(List.of(tableIndex)));

        String createScript = builder.buildCreateTable(table, TableBuilderConfig.defaultConfig());
        assertTrue(createScript.contains("'c'';DROP--'"), createScript);
        assertFalse(createScript.contains("'c';DROP--'"), createScript);
    }

    @Test
    void shouldRejectInvalidIndexColumnSortOrder() {
        TableIndex tableIndex = new TableIndex();
        tableIndex.setSchemaName("dbo");
        tableIndex.setTableName("t");
        tableIndex.setName("ix");
        tableIndex.setType("NONCLUSTERED");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("id");
        indexColumn.setAscOrDesc("ASC; DROP TABLE t;--");
        tableIndex.setColumnList(List.of(indexColumn));

        assertThrows(IllegalArgumentException.class,
                () -> SqlServerIndexTypeEnum.NONCLUSTERED.buildIndexScript(tableIndex));
    }

    @Test
    void shouldEscapeDatabaseNameAndCommentInCreateDatabase() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        Database database = new Database();
        database.setName("db];DROP");
        database.setCollation("SQL_Latin1_General_CP1_CI_AS");
        database.setComment("it's");

        String script = builder.buildCreateDatabase(database);

        assertTrue(script.contains("CREATE DATABASE [db]];DROP]"), script);
        assertTrue(script.contains("COLLATE SQL_Latin1_General_CP1_CI_AS"), script);
        assertTrue(script.contains("exec [db]];DROP].sys."), script);
        assertTrue(script.contains("'it''s'"), script);
    }

    @Test
    void shouldAcceptLegitCollationAndRejectInjection() {
        assertEquals("Latin1_General_100_CI_AS_KS_WS_SC", SqlServerSqlGuards.validateCollation("Latin1_General_100_CI_AS_KS_WS_SC"));

        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        Database database = new Database();
        database.setName("db");
        database.setCollation("Latin1; DROP TABLE t;--");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateDatabase(database));
    }

    @Test
    void shouldKeepQuotedStringAndExpressionDefaultsUnchanged() {
        TableColumn quotedDefault = new TableColumn();
        quotedDefault.setName("c");
        quotedDefault.setColumnType("VARCHAR");
        quotedDefault.setColumnSize(50);
        quotedDefault.setNullable(1);
        quotedDefault.setDefaultValue("'O''Brien'");
        assertTrue(SqlServerColumnTypeEnum.VARCHAR.buildCreateColumnSql(quotedDefault)
                .contains("DEFAULT 'O''Brien'"));

        TableColumn expressionDefault = new TableColumn();
        expressionDefault.setName("d");
        expressionDefault.setColumnType("DATETIME2");
        expressionDefault.setNullable(1);
        expressionDefault.setDefaultValue("(getdate())");
        assertTrue(SqlServerColumnTypeEnum.DATETIME2.buildCreateColumnSql(expressionDefault)
                .contains("DEFAULT (getdate())"));
    }

    @Test
    void shouldWhitelistViewAttributes() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        ModifyView view = new ModifyView();
        view.setSchemaName("dbo");
        view.setViewName("v");
        view.setViewBody("SELECT 1");
        view.setViewAttributes(List.of("SCHEMABINDING"));
        assertTrue(builder.buildCreateView(view).contains("WITH SCHEMABINDING"));

        ModifyView malicious = new ModifyView();
        malicious.setSchemaName("dbo");
        malicious.setViewName("v");
        malicious.setViewBody("SELECT 1");
        malicious.setViewAttributes(List.of("SCHEMABINDING OPTION(RECOMPILE); DROP TABLE t;--"));
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateView(malicious));
    }

    @Test
    void shouldEscapeNamesInMetadataCommentBuilders() {
        String script = SQLConstant.buildTableComment("c", "s'x", "t");
        assertTrue(script.contains("N's''x'"), script);

        String indexScript = SQLConstant.buildIndexComment("c", "dbo", "t", "i'x");
        assertTrue(indexScript.contains("N'i''x'"), indexScript);
    }

    @Test
    void shouldRequoteAndEscapeTableNames() {
        ExposedBuilder builder = new ExposedBuilder();
        assertEquals("[db].[dbo].[users]", builder.tableName("db", "dbo", "users"));
        assertEquals("[db].[dbo].[users]", builder.tableName("db", "dbo", "[users]"));
        assertEquals("[us]]ers]", builder.tableName(null, null, "us]ers"));
        assertEquals("[us]]ers]", builder.tableName(null, null, "[us]]ers]"));
    }

    @Test
    void shouldRoundTripRawBoundaryBracketsAndQualifiedQuotes() {
        SqlServerIdentifierProcessor processor = SqlServerIdentifierProcessor.INSTANCE;
        for (String raw : List.of("plain", "a]b", "]prefix", "suffix]", "[quoted]", "[a]b]", "db.table")) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)));
        }
        assertEquals("db.ta]ble", processor.removeIdentifierQuote("[db].[ta]]ble]"));
        assertEquals("db.table", processor.removeIdentifierQuote("\"db\".\"table\""));
        assertEquals("prefix[a]suffix", processor.removeIdentifierQuote("prefix[a]suffix"));
        assertEquals("[unclosed", processor.removeIdentifierQuote("[unclosed"));
        assertTrue(processor.isQuoteIdentifier("[db].[table]"));
        assertFalse(processor.isQuoteIdentifier("prefix[a]suffix"));
    }

    @Test
    void shouldMatchReservedWordsIndependentlyOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(SqlServerIdentifierProcessor.INSTANCE.isReservedKeyword("insert", null, null));
            assertEquals("[insert]", SqlServerIdentifierProcessor.INSTANCE.quoteIdentifier("insert"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldAcceptLegalTypeAndDefaultExpressionsWithoutRewritingThem() {
        assertEquals("decimal(18, 2)", SqlServerSqlGuards.requireColumnTypeExpression("decimal(18, 2)"));
        assertEquals("[types].[Phone Number]",
                SqlServerSqlGuards.requireColumnTypeExpression("[types].[Phone Number]"));
        assertEquals("xml(CONTENT [dbo].[SchemaCollection])",
                SqlServerSqlGuards.requireColumnTypeExpression("xml(CONTENT [dbo].[SchemaCollection])"));
        assertEquals("N'O''Brien'", SqlServerSqlGuards.requireDefaultExpression("N'O''Brien'"));
        assertEquals("NEXT VALUE FOR [dbo].[seq]",
                SqlServerSqlGuards.requireDefaultExpression("NEXT VALUE FOR [dbo].[seq]"));
        assertEquals("CONVERT(datetime2, sysdatetime())",
                SqlServerSqlGuards.requireDefaultExpression("CONVERT(datetime2, sysdatetime())"));
        assertEquals("N'value' COLLATE Latin1_General_100_CI_AS",
                SqlServerSqlGuards.requireDefaultExpression(
                        "N'value' COLLATE Latin1_General_100_CI_AS"));
        assertEquals("'semi;colon'", SqlServerSqlGuards.requireDefaultExpression("'semi;colon'"));
    }

    @Test
    void shouldRejectStatementBoundariesAndUnbalancedExpressions() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireColumnTypeExpression("int); DROP TABLE t;--"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireColumnTypeExpression("varchar(20"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireColumnTypeExpression("int DROP TABLE"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0; DROP TABLE t"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0 -- comment"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("getdate("));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0, 1"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0 CONSTRAINT injected UNIQUE"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0 NOT NULL"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlServerSqlGuards.requireDefaultExpression("0 WITH VALUES"));
    }

    @Test
    void shouldPreserveValidatedParameterizedAndUserDefinedTypes() {
        TableColumn decimal = new TableColumn();
        decimal.setName("amount");
        decimal.setColumnType("decimal(18, 2)");
        decimal.setNullable(1);
        decimal.setDefaultValue("CONVERT(decimal(18, 2), (1.25))");
        String decimalSql = SqlServerColumnTypeEnum.getByType(decimal.getColumnType())
                .buildCreateColumnSql(decimal);
        assertTrue(decimalSql.contains("[amount] decimal(18, 2)"), decimalSql);
        assertTrue(decimalSql.contains("DEFAULT CONVERT(decimal(18, 2), (1.25))"), decimalSql);

        TableColumn custom = new TableColumn();
        custom.setName("phone");
        custom.setColumnType("[types].[Phone Number]");
        custom.setNullable(1);
        String customSql = SqlServerColumnTypeEnum.getByType(custom.getColumnType())
                .buildCreateColumnSql(custom);
        assertTrue(customSql.contains("[phone] [types].[Phone Number]"), customSql);
    }

    @Test
    void shouldQuoteInheritedBuilderAndManagerPaths() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        String database = "catalog]x";
        String schema = "sales]x";
        String table = "orders]x";
        String qualified = "[catalog]]x].[sales]]x].[orders]]x]";

        assertEquals("SELECT COUNT(1) FROM " + qualified,
                builder.buildSelectCount(database, schema, table));
        assertEquals("SELECT * FROM " + qualified,
                builder.buildSelectTable(database, schema, table));
        assertEquals("DROP TABLE " + qualified,
                builder.buildDropTable(new DropTableRequest(database, schema, table)));
        assertEquals("TRUNCATE TABLE " + qualified,
                builder.buildTruncateTable(new TruncateTableRequest(database, schema, table)));

        Schema schemaModel = new Schema();
        schemaModel.setName(schema);
        assertEquals("CREATE SCHEMA [sales]]x]\ngo\n", builder.buildCreateSchema(schemaModel));
        assertEquals("DROP SCHEMA [sales]]x]", builder.buildDropSchema(schema));
        assertEquals("TRUNCATE TABLE " + qualified,
                new SqlServerDBManager().truncateTable(null, database, schema, table));
    }

    @Test
    void shouldQuoteInheritedUpdateAndTemplateColumns() {
        SqlServerSqlBuilder builder = new SqlServerSqlBuilder();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("display]name", "'value'");
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("id]key", "1");
        UpdateSqlRequest request = UpdateSqlRequest.builder()
                .schemaName("dbo")
                .tableName("users]archive")
                .row(row)
                .primaryKeyMap(keys)
                .build();
        String update = builder.buildUpdate(request);
        assertTrue(update.contains("UPDATE [dbo].[users]]archive] SET [display]]name] = 'value'"), update);
        assertTrue(update.contains("WHERE [id]]key] = 1"), update);

        Table table = new Table();
        table.setSchemaName("dbo");
        table.setName("users]archive");
        TableColumn column = new TableColumn();
        column.setName("display]name");
        table.setColumnList(List.of(column));
        assertTrue(builder.buildTemplate(table, "UPDATE")
                .contains("UPDATE [dbo].[users]]archive] SET [display]]name] = "));
    }

    @Test
    void shouldUseOneSqlServerLiteralEscaperForComments() {
        String script = SQLConstant.buildTableComment("C:\\tmp\\O'Brien", "dbo", "orders");
        assertTrue(script.contains("N'C:\\tmp\\O''Brien'"), script);
        assertFalse(script.contains("C:\\\\tmp"), script);
    }

    private static final class ExposedBuilder extends SqlServerSqlBuilder {
        private String tableName(String databaseName, String schemaName, String tableName) {
            StringBuilder script = new StringBuilder();
            buildTableName(databaseName, schemaName, tableName, script);
            return script.toString();
        }
    }
}
