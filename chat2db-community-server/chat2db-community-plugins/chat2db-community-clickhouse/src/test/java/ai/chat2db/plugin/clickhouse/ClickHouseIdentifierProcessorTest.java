package ai.chat2db.plugin.clickhouse;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.clickhouse.builder.ClickHouseSqlBuilder;
import ai.chat2db.plugin.clickhouse.enums.type.ClickHouseColumnTypeEnum;
import ai.chat2db.plugin.clickhouse.identifier.ClickHouseIdentifierProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseIdentifierProcessorTest {

    @Test
    void shouldDoubleSingleQuotesInLiterals() {
        assertEquals("owner''s", ClickHouseIdentifierProcessor.INSTANCE.escapeString("owner's"));
        assertNull(ClickHouseIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void shouldEscapeBackslashesInLiterals() {
        assertEquals("a\\\\b''c", ClickHouseIdentifierProcessor.INSTANCE.escapeString("a\\b'c"));
    }

    @Test
    void shouldPassThroughNullAndBlankIdentifiers() {
        assertEquals(null, ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals("  ", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("  "));
    }

    @Test
    void shouldLeavePlainIdentifiersUnquoted() {
        assertEquals("plain", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("table_1", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("table_1"));
        assertEquals("plain", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("plain", 24, 3));
    }

    @Test
    void shouldQuoteReservedKeywords() {
        assertEquals("`select`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("select"));
        assertEquals("`SELECT`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("`order`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("order", null, null));
    }

    @Test
    void shouldDoubleBackticksInIdentifiers() {
        assertEquals("`a``b`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("a`b"));
        assertEquals("`with space`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("with space"));
    }

    @Test
    void shouldKeepConditionalSemanticsInIgnoreCaseVariant() {
        assertEquals("plain", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
        assertEquals("`a``b`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("a`b"));
        assertEquals("`select`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("select"));
        assertEquals("", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(""));
        assertEquals(null, ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
    }

    @Test
    void shouldAlwaysQuoteWithoutDiscardingRawBoundaryBackticks() {
        assertEquals("`plain`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("`a``b`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("a`b"));
        assertEquals("```quoted```", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`quoted`"));
        assertEquals("```a``b```", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`a`b`"));
        assertEquals(null, ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
    }

    @Test
    void shouldTreatQuotedLookingInputAsRawIdentifierContent() {
        assertEquals("```a``b```", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("`a`b`"));
        assertEquals("```a``b```", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`a`b`"));
    }

    @Test
    void shouldRecognizeAndRemoveBacktickQuotes() {
        assertTrue(ClickHouseIdentifierProcessor.INSTANCE.isQuoteIdentifier("`name`"));
        assertTrue(ClickHouseIdentifierProcessor.INSTANCE.isQuoteIdentifier("\"name\""));
        assertFalse(ClickHouseIdentifierProcessor.INSTANCE.isQuoteIdentifier("name"));
        assertFalse(ClickHouseIdentifierProcessor.INSTANCE.isQuoteIdentifier(null));
        assertEquals("name", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("`name`"));
        assertEquals("name", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("\"name\""));
        assertEquals("name", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("name"));
        assertEquals(null, ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote(null));
        assertEquals("a`b", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("`a``b`"));
        assertEquals("db.ta`ble", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("`db`.`ta``ble`"));
        assertEquals("prefix`a`suffix",
                ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("prefix`a`suffix"));
        assertEquals("`mixed\"",
                ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("`mixed\""));
    }

    @Test
    void shouldRoundTripThroughAlwaysQuoteAndRemove() {
        for (String raw : List.of("plain", "a`b", "`quoted`", "`a`b`", "db.table")) {
            assertEquals(raw, ClickHouseIdentifierProcessor.INSTANCE
                    .removeIdentifierQuote(ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)));
        }
        assertEquals("db.table", ClickHouseIdentifierProcessor.INSTANCE.removeIdentifierQuote("`db`.`table`"));
    }

    @Test
    void shouldCheckReservedKeywordsWithLocaleIndependentCaseFolding() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("`in`", ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifier("in"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void shouldNeutralizeMaliciousTableNameInCreateTable() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Table table = new Table();
        table.setName("evil` , (id Int32) ENGINE=Memory; -- ");
        table.setDatabaseName("db`x");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());

        String sql = builder.buildCreateTable(table, null);

        assertTrue(sql.contains("`db``x`.`evil`` , (id Int32) ENGINE=Memory; -- `"),
                "identifier backticks must be doubled: " + sql);
    }

    @Test
    void shouldEscapeCommentLiteralInCreateTable() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Table table = new Table();
        table.setName("t");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());
        table.setComment("x' OR '1'='1");

        String sql = builder.buildCreateTable(table, null);

        assertTrue(sql.contains("COMMENT 'x'' OR ''1''=''1'"), "comment quote must be doubled: " + sql);
    }

    @Test
    void shouldEscapeColumnNameAndCommentInCreateColumn() {
        TableColumn column = new TableColumn();
        column.setName("col`drop");
        column.setColumnType("STRING");
        column.setComment("it's");

        String sql = ClickHouseColumnTypeEnum.String.buildCreateColumnSql(column);

        assertTrue(sql.startsWith("`col``drop` "), "column identifier backticks must be doubled: " + sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), "column comment quote must be doubled: " + sql);
    }

    @Test
    void shouldRejectMaliciousEngine() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Table table = new Table();
        table.setName("t");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());
        table.setEngine("Memory; DROP TABLE users");

        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTable(table, null));
    }

    @Test
    void shouldAcceptKnownEngine() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Table table = new Table();
        table.setName("t");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());
        table.setEngine("MergeTree");

        String sql = builder.buildCreateTable(table, null);

        assertTrue(sql.contains("ENGINE=MergeTree"), sql);
    }

    @Test
    void shouldNeutralizeMaliciousDatabaseNameInCreateDatabase() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Database database = new Database();
        database.setName("db`; DROP TABLE x; --");
        database.setComment("c' OR '1'='1");

        String sql = builder.buildCreateDatabase(database);

        assertTrue(sql.contains("CREATE DATABASE `db``; DROP TABLE x; --`"), sql);
        assertTrue(sql.contains("COMMENT 'c'' OR ''1''=''1'"), sql);
    }

    @Test
    void shouldQuoteMetadataNameParts() {
        ClickHouseMetaData metaData = new ClickHouseMetaData();

        assertEquals("`db`.`ta``ble`", metaData.getMetaDataName("db", "ta`ble"));
    }

    @Test
    void shouldAcceptNumericAndFunctionDefaults() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT32");
        column.setDefaultValue("-1");

        String sql = ClickHouseColumnTypeEnum.Int32.buildCreateColumnSql(column);

        assertTrue(sql.contains("DEFAULT -1"), sql);
    }

    @Test
    void shouldAcceptQuotedStringDefaults() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("String");
        column.setDefaultValue("'abc'");

        String sql = ClickHouseColumnTypeEnum.String.buildCreateColumnSql(column);

        assertTrue(sql.contains("DEFAULT 'abc'"), sql);
    }

    @Test
    void shouldRejectMalformedQuotedDefaultExpression() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("String");
        column.setDefaultValue("'a');DROP TABLE t;--'");

        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.String.buildCreateColumnSql(column));
    }

    @Test
    void shouldPreserveSerializedStringDefaultExactly() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("String");
        column.setDefaultValue("'O''Brien'");

        String sql = ClickHouseColumnTypeEnum.String.buildCreateColumnSql(column);

        assertTrue(sql.contains("DEFAULT 'O''Brien'"), sql);
        assertEquals("'a\\\\b'", ClickHouseSqlGuards.escapeDefaultExpression("'a\\\\b'"));
    }

    @Test
    void shouldResolveMixedCaseAndDigitTypeNames() {
        TableColumn mixed = new TableColumn();
        mixed.setName("c1");
        mixed.setColumnType("String");
        assertTrue(ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(mixed).startsWith("`c1` String"),
                "mixed-case type must resolve to the enum");

        TableColumn digits = new TableColumn();
        digits.setName("c2");
        digits.setColumnType("Int32");
        assertTrue(ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(digits).startsWith("`c2` Int32"),
                "digit-containing type must resolve to the enum");
    }

    @Test
    void shouldEmitValidatedFallbackForUnknownType() {
        TableColumn nested = new TableColumn();
        nested.setName("c");
        nested.setColumnType("Array(Nullable(String))");
        String sql = ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(nested);
        assertTrue(sql.startsWith("`c` Array(Nullable(String))"), sql);

        TableColumn malicious = new TableColumn();
        malicious.setName("c");
        malicious.setColumnType("Int32); DROP TABLE x; --");
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(malicious));

        TableColumn breakout = new TableColumn();
        breakout.setName("c");
        breakout.setColumnType("Int32, injected Int32");
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(breakout));
    }

    @Test
    void shouldRejectEngineAndDefaultParenBreakout() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        Table table = new Table();
        table.setName("t");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());
        table.setEngine("Memory() ORDER BY tuple() -- x");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateTable(table, null));

        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT32");
        column.setDefaultValue("f(1)) ENGINE=Memory -- x");
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.Int32.buildCreateColumnSql(column));
    }

    @Test
    void shouldEscapeQuotedDefaultLiteral() {
        TableColumn column = new TableColumn();
        column.setName("d");
        column.setColumnType("DATE");
        column.setDefaultValue("2024-01-01' OR '1'='1");

        String sql = ClickHouseColumnTypeEnum.Date.buildCreateColumnSql(column);

        assertTrue(sql.contains("DEFAULT '2024-01-01'' OR ''1''=''1'"), sql);
    }

    @Test
    void shouldAllowEqualsInsideEnumTypeArguments() {
        TableColumn column = new TableColumn();
        column.setName("e");
        column.setColumnType("Enum8('a'=1,'b'=2)");

        String sql = ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column);

        assertTrue(sql.startsWith("`e` Enum8('a'=1,'b'=2)"), sql);
    }

    @Test
    void shouldRejectEqualsOutsideTypeArguments() {
        TableColumn topLevel = new TableColumn();
        topLevel.setName("e");
        topLevel.setColumnType("Int32=1");
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(topLevel));

        TableColumn afterClose = new TableColumn();
        afterClose.setName("e");
        afterClose.setColumnType("Enum8('a'=1)=2");
        assertThrows(IllegalArgumentException.class,
                () -> ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(afterClose));
    }

    @Test
    void shouldPreserveNullableAndDefaultInValidatedFallback() {
        TableColumn column = new TableColumn();
        column.setName("d");
        column.setColumnType("Decimal(10,2)");
        column.setNullable(1);
        column.setDefaultValue("1.5");

        String sql = ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column);

        assertTrue(sql.startsWith("`d` Nullable(Decimal(10,2))"), sql);
        assertTrue(sql.contains("DEFAULT 1.5"), sql);
    }

    @Test
    void shouldNotWrapNonNullableCapableTypesInFallback() {
        TableColumn column = new TableColumn();
        column.setName("a");
        column.setColumnType("Array(Nullable(String))");
        column.setNullable(1);

        String sql = ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column);

        assertTrue(sql.startsWith("`a` Array(Nullable(String))"), sql);
    }

    @Test
    void shouldAcceptNegativeEnumValues() {
        TableColumn column = new TableColumn();
        column.setName("e");
        column.setColumnType("Enum8('a' = -1)");

        assertTrue(ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column)
                .startsWith("`e` Enum8('a' = -1)"));
    }

    @Test
    void shouldNotWrapAggregateFunctionInFallback() {
        TableColumn column = new TableColumn();
        column.setName("agg");
        column.setColumnType("AggregateFunction(uniq, String)");
        column.setNullable(1);

        String sql = ClickHouseColumnTypeEnum.buildCreateColumnSqlSafely(column);

        assertTrue(sql.startsWith("`agg` AggregateFunction(uniq, String)"), sql);
    }

    @Test
    void shouldAcceptLegalQuotedAndNestedTypeSyntax() {
        for (String type : List.of(
                "Enum16('min' = -32768, 'max' = 32767)",
                "DateTime64(3, 'Asia/Shanghai')",
                "Tuple(`a-b` String, inner UInt8)",
                "Enum8(')' = 1)",
                "Map(String, Array(Tuple(x UInt8, y DateTime64(3, 'UTC'))))")) {
            assertEquals(type, ClickHouseSqlGuards.requireColumnTypeExpression(type));
        }
    }

    @Test
    void shouldAcceptNestedEngineAndDefaultExpressions() {
        assertEquals("S3('https://bucket/path(test).csv', 'CSV')",
                ClickHouseSqlGuards.requireEngine("S3('https://bucket/path(test).csv', 'CSV')"));
        assertEquals("Distributed(cluster, db, table, cityHash64(id))",
                ClickHouseSqlGuards.requireEngine("Distributed(cluster, db, table, cityHash64(id))"));
        assertEquals("toDateTime64(now(), 3)",
                ClickHouseSqlGuards.escapeDefaultExpression("toDateTime64(now(), 3)"));
        assertEquals("if(length(')') > 0, 1, 0)",
                ClickHouseSqlGuards.escapeDefaultExpression("if(length(')') > 0, 1, 0)"));
    }

    @Test
    void shouldEscapeInheritedBuilderIdentifierPaths() {
        ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();
        String schema = "analytics`x";
        String table = "orders`x";

        assertEquals("SELECT COUNT(1) FROM `analytics``x`.`orders``x`",
                builder.buildSelectCount(null, schema, table));
        assertEquals("SELECT COUNT(1) FROM `analytics``x`.`orders``x`",
                builder.buildSelectCount("ignored_catalog", schema, table));
        assertEquals("SELECT * FROM `analytics``x`.`orders``x`",
                builder.buildSelectTable(null, schema, table));
        assertEquals("DROP TABLE `analytics``x`.`orders``x`",
                builder.buildDropTable(new DropTableRequest(null, schema, table)));
        assertEquals("TRUNCATE TABLE `analytics``x`.`orders``x`",
                builder.buildTruncateTable(new TruncateTableRequest(null, schema, table)));

        Schema schemaModel = new Schema();
        schemaModel.setName(schema);
        assertEquals("CREATE DATABASE `analytics``x`", builder.buildCreateSchema(schemaModel));
        assertEquals("DROP DATABASE `analytics``x`", builder.buildDropSchema(schema));
    }

    @Test
    void shouldBuildSchemaQualifiedManagerStatementsOnce() throws Exception {
        ClickHouseDBManager manager = new ClickHouseDBManager();
        String source = ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("ord`ers");
        String target = ClickHouseIdentifierProcessor.INSTANCE.quoteIdentifierAlways("ord`ers_copy");

        assertEquals("DROP TABLE IF EXISTS `analytics`.`ord``ers`",
                manager.dropTable(null, null, "analytics", "ord`ers"));
        assertEquals("DROP TABLE IF EXISTS ```analytics```.```orders```",
                manager.dropTable(null, null, "`analytics`", "`orders`"));
        assertEquals("TRUNCATE TABLE `analytics`.`ord``ers`",
                manager.truncateTable(null, null, "analytics", source));
        assertEquals(List.of(
                        "CREATE TABLE `analytics`.`ord``ers_copy` AS `analytics`.`ord``ers`",
                        "INSERT INTO `analytics`.`ord``ers_copy` SELECT * FROM `analytics`.`ord``ers`"),
                ClickHouseDBManager.buildCopyTableStatements(null, "analytics", source, target, true));
    }

    @Test
    void shouldBuildAlterCommentWithValidSpacingAndEscaping() {
        Table oldTable = new Table();
        oldTable.setName("events");
        oldTable.setComment("old");

        Table newTable = new Table();
        newTable.setName("events");
        newTable.setComment("owner's");

        assertEquals("ALTER TABLE `events`\n\tMODIFY COMMENT 'owner''s';",
                new ClickHouseSqlBuilder().buildAlterTable(oldTable, newTable));
    }

    @Test
    void shouldAlterParameterizedTypeAndRenameWithoutMalformedFragment() {
        Table oldTable = new Table();
        oldTable.setSchemaName("analytics");
        oldTable.setName("events");
        oldTable.setColumnList(List.of());
        oldTable.setIndexList(List.of());

        TableColumn column = new TableColumn();
        column.setOldName("old");
        column.setName("new");
        column.setColumnType("Decimal(10,2)");
        column.setEditStatus(EditStatusEnum.MODIFY.name());

        Table newTable = new Table();
        newTable.setSchemaName("analytics");
        newTable.setName("events");
        newTable.setColumnList(List.of(column));
        newTable.setIndexList(List.of());

        assertEquals("ALTER TABLE `analytics`.`events`\n"
                        + "\tRENAME COLUMN `old` TO `new`,\n"
                        + "\tMODIFY COLUMN `new` Decimal(10,2);",
                new ClickHouseSqlBuilder().buildAlterTable(oldTable, newTable));
    }
}
