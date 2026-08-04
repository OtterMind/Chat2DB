package ai.chat2db.plugin.xugudb;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.plugin.xugudb.builder.XUGUDBSqlBuilder;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBColumnTypeEnum;
import ai.chat2db.plugin.xugudb.enums.type.XUGUDBIndexTypeEnum;
import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.MultiInsertSqlRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XugudbIdentifierProcessorTest {

    private final XUGUDBSqlBuilder builder = new XUGUDBSqlBuilder();

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("o''brien", XugudbIdentifierProcessor.INSTANCE.escapeString("o'brien"));
        assertEquals("''", XugudbIdentifierProcessor.INSTANCE.escapeString("'"));
        assertEquals("plain", XugudbIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(XugudbIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void valueProcessorPreservesBackslashesAndEscapesSingleQuotes() {
        SQLDataValue value = new SQLDataValue();
        value.setValue("C:\\tmp\\o'Brien");

        assertEquals("'C:\\tmp\\o''Brien'", new XUGUDBMetaData().getValueProcessor().getSqlValueString(value));
    }

    @Test
    void escapeIdentifierDoublesEveryRawQuote() {
        assertEquals("ta\"\"ble", XugudbIdentifierProcessor.escapeIdentifier("ta\"ble"));
        assertEquals("\"\"foo\"\"", XugudbIdentifierProcessor.escapeIdentifier("\"foo\""));
        assertEquals("\"\"fo\"\"o\"\"", XugudbIdentifierProcessor.escapeIdentifier("\"fo\"o\""));
        assertEquals("plain", XugudbIdentifierProcessor.escapeIdentifier("plain"));
        assertNull(XugudbIdentifierProcessor.escapeIdentifier(null));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        assertEquals("plain", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("\"ta\"\"ble\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier("ta\"ble"));
        assertEquals("\"SELECT\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("\"already\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier("\"already\""));
        assertNull(XugudbIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier(""));
    }

    @Test
    void quoteIdentifierAlwaysRoundTripsEveryNonNullValue() {
        assertEquals("\"plain\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("\"ta\"\"ble\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways("ta\"ble"));
        assertNull(XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("\"\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertEquals("\" \"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(" "));
        assertEquals("\"\"\"abc\"\"\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"abc\""));

        for (String raw : List.of("", " ", "\"abc\"", "A\"B")) {
            assertEquals(raw, XugudbIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                    XugudbIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)));
        }
    }

    @Test
    void reservedWordAndIgnoreCaseQuotingAreLocaleStable() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("\"insert\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifier("insert"));
            assertEquals("plain", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
            assertEquals("\"select\"", XugudbIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("select"));
            assertEquals(XUGUDBColumnTypeEnum.TINYINT, XUGUDBColumnTypeEnum.getByType("tinyint"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void createTableNeutralizesMaliciousSchemaName() {
        Table table = Table.builder()
                .schemaName("evil\";DROP TABLE t;--")
                .name("sample_table")
                .columnList(List.of(column("id", "INTEGER")))
                .indexList(List.of())
                .build();

        String sql = builder.buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("\"evil\"\";DROP TABLE t;--\".\"sample_table\""), sql);
        assertFalse(sql.contains("\"evil\";"), sql);
    }

    @Test
    void createSchemaNeutralizesMaliciousNameAndOwner() {
        Schema schema = new Schema();
        schema.setName("sch\"; DROP TABLE x; --");
        schema.setOwner("own\"; GRANT; --");

        String sql = builder.buildCreateSchema(schema);

        assertTrue(sql.contains("CREATE SCHEMA \"sch\"\"; DROP TABLE x; --\""), sql);
        assertTrue(sql.contains("AUTHORIZATION \"own\"\"; GRANT; --\""), sql);
        assertFalse(sql.contains("\"sch\";"), sql);
        assertFalse(sql.contains("AUTHORIZATION \"own\";"), sql);
    }

    @Test
    void indexScriptNeutralizesMaliciousColumnName() {
        TableIndex tableIndex = TableIndex.builder()
                .schemaName("app")
                .tableName("sample_table")
                .name("idx")
                .type("Normal")
                .columnList(List.of(TableIndexColumn.builder()
                        .columnName("col\"; DROP TABLE t; --")
                        .build()))
                .build();

        String sql = XUGUDBIndexTypeEnum.NORMAL.buildIndexScript(tableIndex);

        assertTrue(sql.contains("(\"col\"\"; DROP TABLE t; --\")"), sql);
        assertFalse(sql.contains("\"col\";"), sql);
    }

    @Test
    void maliciousDefaultValueIsRejected() {
        TableColumn column = column("id", "INTEGER");
        column.setDefaultValue("0; DROP TABLE users; --");

        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(column));
    }

    @Test
    void checkClauseCannotBeSmuggledIntoFallbackColumnType() {
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireColumnTypeExpression("VARCHAR(32) CHECK (1 = 1)"));
    }

    @Test
    void unbalancedQuoteInFunctionDefaultIsRejected() {
        TableColumn c1 = column("id", "INTEGER");
        c1.setDefaultValue("length(')");
        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(c1));

        TableColumn c2 = column("id", "INTEGER");
        c2.setDefaultValue("f(x')");
        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(c2));

        TableColumn c3 = column("id", "INTEGER");
        c3.setDefaultValue("f('ok'");
        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(c3));
    }

    @Test
    void balancedQuotedArgsInFunctionDefaultAreAccepted() {
        TableColumn noArgs = column("created", "TIMESTAMP");
        noArgs.setDefaultValue("now()");
        assertTrue(XUGUDBColumnTypeEnum.TIMESTAMP.buildCreateColumnSql(noArgs).contains("DEFAULT now()"));

        TableColumn quotedArg = column("name_col", "VARCHAR");
        quotedArg.setColumnSize(10);
        quotedArg.setDefaultValue("substr('abc')");
        assertTrue(XUGUDBColumnTypeEnum.VARCHAR.buildCreateColumnSql(quotedArg).contains("DEFAULT substr('abc')"));

        TableColumn escapedQuoteArg = column("name_col", "VARCHAR");
        escapedQuoteArg.setColumnSize(10);
        escapedQuoteArg.setDefaultValue("f('it''s')");
        assertTrue(XUGUDBColumnTypeEnum.VARCHAR.buildCreateColumnSql(escapedQuoteArg).contains("DEFAULT f('it''s')"));
    }

    @Test
    void nestedCastSequenceAndParenthesizedDefaultsAreAccepted() {
        assertEquals("COALESCE(NULLIF(name, ''), 'unknown')",
                XugudbSqlGuards.requireDefaultValue("COALESCE(NULLIF(name, ''), 'unknown')"));
        assertEquals("CAST(1 AS DECIMAL(10,2))",
                XugudbSqlGuards.requireDefaultValue("CAST(1 AS DECIMAL(10,2))"));
        assertEquals("app.seq.NEXTVAL", XugudbSqlGuards.requireDefaultValue("app.seq.NEXTVAL"));
        assertEquals("(0)", XugudbSqlGuards.requireDefaultValue("(0)"));
    }

    @Test
    void defaultScannerRejectsCommentsStatementsAndUnbalancedDelimiters() {
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("f(1--comment)"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("f(1, DROP TABLE users)"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("f(/* comment */1)"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("(0"));
    }

    @Test
    void validDefaultValuesAreAccepted() {
        TableColumn numeric = column("id", "INTEGER");
        numeric.setDefaultValue("0");
        assertTrue(XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(numeric).contains("DEFAULT 0"));

        TableColumn keyword = column("created", "TIMESTAMP");
        keyword.setDefaultValue("CURRENT_TIMESTAMP");
        assertTrue(XUGUDBColumnTypeEnum.TIMESTAMP.buildCreateColumnSql(keyword).contains("DEFAULT CURRENT_TIMESTAMP"));
    }

    @Test
    void maliciousUnitIsRejected() {
        TableColumn column = column("name_col", "VARCHAR");
        column.setColumnSize(10);
        column.setUnit("BYTE); DROP TABLE t; --");

        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));
    }

    @Test
    void maliciousIndexSortOrderIsRejected() {
        TableIndex tableIndex = TableIndex.builder()
                .schemaName("app")
                .tableName("sample_table")
                .name("idx")
                .type("Normal")
                .columnList(List.of(TableIndexColumn.builder()
                        .columnName("id")
                        .ascOrDesc("DESC; DROP TABLE t; --")
                        .build()))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBIndexTypeEnum.NORMAL.buildIndexScript(tableIndex));
    }

    @Test
    void selectTableNeutralizesMaliciousSchemaName() {
        String sql = builder.dql().buildSelectTable(null, "evil\";DROP TABLE t;--", "sample_table");

        assertEquals("SELECT * FROM \"evil\"\";DROP TABLE t;--\".\"sample_table\"", sql);
    }

    @Test
    void insertNeutralizesMaliciousTableAndColumnNames() {
        SingleInsertSqlRequest request = SingleInsertSqlRequest.builder()
                .schemaName("app\";DROP TABLE t;--")
                .tableName("tab\";DROP TABLE t;--")
                .columnList(List.of("col\"; DROP TABLE t; --"))
                .valueList(List.of("1"))
                .build();

        String sql = builder.dml().buildInsert(request);

        assertTrue(sql.contains("INSERT INTO \"app\"\";DROP TABLE t;--\".\"tab\"\";DROP TABLE t;--\""), sql);
        assertTrue(sql.contains("(\"col\"\"; DROP TABLE t; --\")"), sql);
        assertFalse(sql.contains("INTO \"app\";"), sql);
    }

    @Test
    void inheritedBuildersAlwaysQuoteIdentifiers() {
        assertEquals("SELECT * FROM \"App\".\"MixedTable\"",
                builder.dql().buildSelectTable(null, "App", "MixedTable"));
        assertEquals("SELECT COUNT(1) FROM \"App\".\"MixedTable\"",
                builder.dql().buildSelectCount(null, "App", "MixedTable"));

        SingleInsertSqlRequest insert = SingleInsertSqlRequest.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .columnList(List.of("MixedColumn"))
                .valueList(List.of("1"))
                .build();
        assertEquals("INSERT INTO \"App\".\"MixedTable\" (\"MixedColumn\")  VALUES (1)",
                builder.dml().buildInsert(insert));

        MultiInsertSqlRequest batchInsert = MultiInsertSqlRequest.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .columnList(List.of("MixedColumn"))
                .valueLists(List.of(List.of("1"), List.of("2")))
                .build();
        String batchSql = builder.dml().buildBatchInsert(batchInsert);
        assertTrue(batchSql.startsWith("INSERT INTO \"App\".\"MixedTable\" (\"MixedColumn\")  VALUES "), batchSql);

        assertEquals("DROP TABLE \"App\".\"MixedTable\"",
                builder.ddl().table().buildDropTable(new DropTableRequest(null, "App", "MixedTable")));
        assertEquals("TRUNCATE TABLE \"App\".\"MixedTable\"",
                builder.ddl().table().buildTruncateTable(new TruncateTableRequest(null, "App", "MixedTable")));

        Database database = new Database();
        database.setName("MixedDatabase");
        assertEquals("CREATE DATABASE \"MixedDatabase\"", builder.ddl().database().buildCreateDatabase(database));
    }

    @Test
    void updateQuotesSetAndPrimaryKeyColumns() {
        UpdateSqlRequest request = UpdateSqlRequest.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .row(Map.of("set\"; DROP TABLE t; --", "1"))
                .primaryKeyMap(Map.of("pk\"; DROP TABLE t; --", "2"))
                .build();

        String sql = builder.dml().buildUpdate(request);

        assertEquals("UPDATE \"App\".\"MixedTable\" SET \"set\"\"; DROP TABLE t; --\" = 1"
                + " WHERE \"pk\"\"; DROP TABLE t; --\" = 2", sql);
    }

    @Test
    void metadataAndManagerRespectRawVersusPrequotedNames() throws Exception {
        XUGUDBMetaData metaData = new XUGUDBMetaData();
        assertEquals("\"App\".\"MixedTable\"", metaData.getMetaDataName("ignored", "App", "MixedTable"));

        XUGUDBManager manager = new XUGUDBManager();
        assertEquals("DROP TABLE IF EXISTS \"ta\"\"ble\"",
                manager.dropTable(null, null, null, "ta\"ble"));
        assertEquals("TRUNCATE TABLE \"App\".\"MixedTable\"",
                manager.truncateTable(null, null, null, "\"App\".\"MixedTable\""));
    }

    @Test
    void columnCommentLiteralIsEscapedEndToEnd() {
        TableColumn col = column("id", "INTEGER");
        col.setComment("x'; DROP TABLE t; --");
        Table table = Table.builder()
                .schemaName("app")
                .name("sample_table")
                .columnList(List.of(col))
                .indexList(List.of())
                .build();

        String sql = builder.buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("IS 'x''; DROP TABLE t; --'"), sql);
        assertFalse(sql.contains("IS 'x';"), sql);
    }

    @Test
    void fallbackColumnEscapesNameAndRejectsMaliciousType() {
        TableColumn weirdName = column("na\"me", "FOOTYPE");
        assertTrue(XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(weirdName).startsWith("\"na\"\"me\" FOOTYPE"));

        TableColumn maliciousType = column("id", "INT); DROP TABLE t; --");
        assertThrows(IllegalArgumentException.class,
                () -> XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(maliciousType));
    }

    @Test
    void createTableUsesSafeFallbackForUnknownTypes() {
        Table valid = Table.builder()
                .schemaName("App")
                .name("CustomTable")
                .columnList(List.of(column("custom_col", "types.CustomType(10,2)")))
                .indexList(List.of())
                .build();
        String sql = builder.buildCreateTable(valid, TableBuilderConfig.defaultConfig());
        assertTrue(sql.contains("\"custom_col\" types.CustomType(10,2)"), sql);

        Table invalid = Table.builder()
                .schemaName("App")
                .name("CustomTable")
                .columnList(List.of(column("custom_col", "CustomType); DROP TABLE t; --")))
                .indexList(List.of())
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildCreateTable(invalid, TableBuilderConfig.defaultConfig()));
    }

    @Test
    void caseOnlyTableAndColumnRenamesAreGenerated() {
        Table oldTable = Table.builder()
                .schemaName("App")
                .name("MixedTable")
                .columnList(List.of())
                .indexList(List.of())
                .build();
        Table newTable = Table.builder()
                .schemaName("App")
                .name("mixedTable")
                .columnList(List.of())
                .indexList(List.of())
                .build();
        assertTrue(builder.buildAlterTable(oldTable, newTable)
                .contains("RENAME TO \"mixedTable\""));

        TableColumn oldColumn = column("MixedColumn", "INTEGER");
        TableColumn renamed = column("mixedColumn", "INTEGER");
        renamed.setOldName("MixedColumn");
        renamed.setOldColumn(oldColumn);
        renamed.setEditStatus("MODIFY");
        assertTrue(XUGUDBColumnTypeEnum.INTEGER.buildModifyColumn(renamed)
                .contains("RENAME COLUMN \"MixedColumn\" TO \"mixedColumn\""));
    }

    @Test
    void primaryDropIsCaseInsensitiveAndSortDirectionIsCanonical() {
        TableIndex primary = TableIndex.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .type("primary")
                .editStatus("DELETE")
                .build();
        assertTrue(XUGUDBIndexTypeEnum.getByType(primary.getType()).buildModifyIndex(primary)
                .contains("DROP PRIMARY KEY"));

        TableIndex normal = TableIndex.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .name("idx")
                .type("Normal")
                .columnList(List.of(TableIndexColumn.builder()
                        .columnName("id")
                        .ascOrDesc(" desc ")
                        .build()))
                .build();
        String sql = XUGUDBIndexTypeEnum.NORMAL.buildIndexScript(normal);
        assertTrue(sql.contains("\"id\" DESC"), sql);
        assertFalse(sql.contains(" desc "), sql);
    }

    @Test
    void validatorsReturnTrimmedValues() {
        TableColumn numeric = column("id", "INTEGER");
        numeric.setDefaultValue("  0  ");
        String columnSql = XUGUDBColumnTypeEnum.INTEGER.buildCreateColumnSql(numeric);
        assertTrue(columnSql.contains("DEFAULT 0 "), columnSql);
        assertFalse(columnSql.contains("DEFAULT  0"), columnSql);

        TableColumn varchar = column("name_col", "VARCHAR");
        varchar.setColumnSize(10);
        varchar.setUnit(" BYTE ");
        String varcharSql = XUGUDBColumnTypeEnum.VARCHAR.buildCreateColumnSql(varchar);
        assertTrue(varcharSql.contains("(10 BYTE)"), varcharSql);
    }

    @Test
    void numericTypePreservesPrecisionAndScale() {
        TableColumn decimal = column("amount", "NUMERIC");
        decimal.setColumnSize(10);
        decimal.setDecimalDigits(2);
        assertTrue(XUGUDBColumnTypeEnum.NUMERIC.buildCreateColumnSql(decimal).contains("NUMERIC(10,2)"));

        TableColumn precisionOnly = column("amount", "NUMERIC");
        precisionOnly.setColumnSize(18);
        assertTrue(XUGUDBColumnTypeEnum.NUMERIC.buildCreateColumnSql(precisionOnly).contains("NUMERIC(18)"));
    }

    @Test
    void requireDefaultValueAcceptsValidExpressionsAndRejectsInjection() {
        assertEquals("0", XugudbSqlGuards.requireDefaultValue("0"));
        assertEquals("-1.5", XugudbSqlGuards.requireDefaultValue("-1.5"));
        assertEquals("CURRENT_TIMESTAMP", XugudbSqlGuards.requireDefaultValue("CURRENT_TIMESTAMP"));
        assertEquals("now()", XugudbSqlGuards.requireDefaultValue("now()"));
        assertEquals("f('it''s')", XugudbSqlGuards.requireDefaultValue("f('it''s')"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("0; DROP TABLE users; --"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireDefaultValue("length(')"));
    }

    @Test
    void requireUnitAcceptsLettersAndRejectsInjection() {
        assertEquals("BYTE", XugudbSqlGuards.requireUnit(" BYTE "));
        assertEquals("CHAR", XugudbSqlGuards.requireUnit("CHAR"));
        assertThrows(IllegalArgumentException.class,
                () -> XugudbSqlGuards.requireUnit("BYTE); DROP TABLE t; --"));
    }

    private static TableColumn column(String name, String type) {
        return TableColumn.builder()
                .schemaName("app")
                .tableName("sample_table")
                .name(name)
                .columnType(type)
                .nullable(1)
                .build();
    }
}
