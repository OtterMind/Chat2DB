package ai.chat2db.plugin.hive;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.hive.builder.HiveSqlBuilder;
import ai.chat2db.plugin.hive.enums.type.HiveColumnTypeEnum;
import ai.chat2db.plugin.hive.enums.type.HiveIndexTypeEnum;
import ai.chat2db.plugin.hive.identifier.HiveIdentifierProcessor;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveIdentifierProcessorTest {

    @Test
    void escapeSqlLiteralDoublesBackslashBeforeSingleQuote() {
        assertEquals("a''b\\\\c", HiveIdentifierProcessor.INSTANCE.escapeString("a'b\\c"));
        assertEquals("''", HiveIdentifierProcessor.INSTANCE.escapeString("'"));
        assertEquals("plain", HiveIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(HiveIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        // valid plain identifiers pass through unquoted
        assertEquals("plain", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("snake_case1", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("snake_case1"));
        // null/blank pass through unchanged
        assertNull(HiveIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", HiveIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        // non-plain identifiers are wrapped with embedded-backtick doubling
        assertEquals("`weird``name`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("weird`name"));
        assertEquals("`a``; DROP TABLE b; --`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("a`; DROP TABLE b; --"));
        assertEquals("`SELECT`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("`rLiKe`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("rLiKe"));
        assertEquals("`UTC_TMESTAMP`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("UTC_TMESTAMP"));
        // boundary backticks are raw identifier content
        assertEquals("```quoted```", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("`quoted`"));
    }

    @Test
    void quoteIdentifierVersionedOverloadDelegatesToConditional() {
        assertEquals("plain", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("plain", null, null));
        assertEquals("`weird``name`", HiveIdentifierProcessor.INSTANCE.quoteIdentifier("weird`name", 3, 1));
        assertNull(HiveIdentifierProcessor.INSTANCE.quoteIdentifier(null, null, null));
    }

    @Test
    void quoteIdentifierAlwaysQuotesUnconditionally() {
        assertEquals("`plain`", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("`weird``name`", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways("weird`name"));
        assertEquals("`a``; DROP TABLE b; --`", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways("a`; DROP TABLE b; --"));
        assertEquals("```a``b```", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`a`b`"));
        assertEquals("```quoted```", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`quoted`"));
        assertNull(HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("``", HiveIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
    }

    @Test
    void quoteIdentifierIgnoreCaseUsesConditionalSpiRules() {
        assertEquals("plain", HiveIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
        assertEquals("`weird``name`", HiveIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("weird`name"));
        assertNull(HiveIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
    }

    @Test
    void removeIdentifierQuoteRecognizesBackticks() {
        assertEquals("quoted", HiveIdentifierProcessor.INSTANCE.removeIdentifierQuote("`quoted`"));
        assertEquals("plain", HiveIdentifierProcessor.INSTANCE.removeIdentifierQuote("plain"));
        assertEquals("", HiveIdentifierProcessor.INSTANCE.removeIdentifierQuote(""));
        assertNull(HiveIdentifierProcessor.INSTANCE.removeIdentifierQuote(null));
    }

    @Test
    void alwaysQuoteAndRemoveQuoteRoundTripExactRawIdentifiers() {
        HiveIdentifierProcessor processor = HiveIdentifierProcessor.INSTANCE;
        for (String raw : List.of("plain", "a`b", "`leading", "trailing`", "`both`", "", " ")) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void isQuoteIdentifierRecognizesBackticks() {
        assertTrue(HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier("`quoted`"));
        assertTrue(HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier("\"quoted\""));
        assertTrue(!HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier("plain"));
        assertTrue(!HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier("`"));
        assertTrue(!HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier("\""));
        assertTrue(!HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier(null));
        assertTrue(!HiveIdentifierProcessor.INSTANCE.isQuoteIdentifier(""));
    }

    @Test
    void requireHiveNameRejectsInjection() {
        assertEquals("utf8", HiveSqlGuards.requireHiveName("utf8", "charset"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requireHiveName("InnoDB, COMMENT='x'", "engine"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requireHiveName("utf8;DROP TABLE t", "charset"));
    }

    @Test
    void requireNumericDefaultRejectsNonLiteral() {
        assertEquals("42", HiveSqlGuards.requireNumericDefault("42"));
        assertEquals("-1.5", HiveSqlGuards.requireNumericDefault("-1.5"));
        assertEquals("1e3", HiveSqlGuards.requireNumericDefault("1e3"));
        assertEquals("TRUE", HiveSqlGuards.requireNumericDefault("TRUE"));
        assertThrows(IllegalArgumentException.class, () -> HiveSqlGuards.requireNumericDefault("0);DROP TABLE t"));
        assertThrows(IllegalArgumentException.class, () -> HiveSqlGuards.requireNumericDefault("(uuid())"));
    }

    @Test
    void requireAscOrDescRejectsInjection() {
        assertEquals("ASC", HiveSqlGuards.requireAscOrDesc("asc"));
        assertEquals("DESC", HiveSqlGuards.requireAscOrDesc("DESC"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requireAscOrDesc("DESC, `x` ASC; DROP TABLE t"));
    }

    @Test
    void createColumnSqlQuotesNameAndEscapesComment() {
        TableColumn column = TableColumn.builder()
                .name("a`; DROP TABLE b; --")
                .columnType("VARCHAR")
                .columnSize(255)
                .comment("it's")
                .build();

        String sql = HiveColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertTrue(sql.contains("`a``; DROP TABLE b; --`"), sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), sql);
    }

    @Test
    void modifyColumnQuotesOldAndNewNames() {
        TableColumn column = TableColumn.builder()
                .name("n`b")
                .oldName("o`; DROP TABLE b; --")
                .columnType("INT")
                .editStatus("MODIFY")
                .build();

        String sql = HiveColumnTypeEnum.INT.buildModifyColumn(column);

        assertTrue(sql.startsWith("CHANGE COLUMN `o``; DROP TABLE b; --` `n``b`"), sql);
    }

    @Test
    void dropColumnQuotesName() {
        TableColumn column = TableColumn.builder()
                .name("a`; DROP TABLE b; --")
                .columnType("INT")
                .editStatus("DELETE")
                .build();

        assertEquals("DROP COLUMN `a``; DROP TABLE b; --`", HiveColumnTypeEnum.INT.buildModifyColumn(column));
    }

    @Test
    void createTableQuotesNamesAndEscapesComment() {
        Table table = Table.builder()
                .name("a`; DROP TABLE b; --")
                .databaseName("d`b")
                .comment("it's")
                .columnList(List.of())
                .indexList(List.of())
                .build();

        String sql = new HiveSqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.contains("`d``b`.`a``; DROP TABLE b; --`"), sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), sql);
    }

    @Test
    void createTableRejectsMaliciousEngine() {
        Table table = Table.builder()
                .name("t")
                .engine("InnoDB, COMMENT='x'")
                .columnList(List.of())
                .indexList(List.of())
                .build();

        assertThrows(IllegalArgumentException.class, () -> new HiveSqlBuilder().buildCreateTable(table, null));
    }

    @Test
    void alterTableRenameQuotesNewNameAndEscapesComment() {
        Table oldTable = Table.builder().name("t1").columnList(List.of()).indexList(List.of()).build();
        Table newTable = Table.builder()
                .name("x`; DROP TABLE b; --")
                .comment("it's")
                .columnList(List.of())
                .indexList(List.of())
                .build();

        String sql = new HiveSqlBuilder().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("RENAME TO `x``; DROP TABLE b; --`"), sql);
        assertTrue(sql.contains("'comment' = 'it''s'"), sql);
    }

    @Test
    void createDatabaseQuotesNameAndEscapesComment() {
        Database database = Database.builder()
                .name("a`; DROP TABLE b; --")
                .comment("it's")
                .build();

        String sql = new HiveSqlBuilder().buildCreateDatabase(database);

        assertTrue(sql.startsWith("CREATE DATABASE `a``; DROP TABLE b; --`"), sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), sql);
    }

    @Test
    void indexSqlQuotesNamesAndEscapesComment() {
        TableIndex index = TableIndex.builder()
                .name("i`; DROP TABLE b; --")
                .type("Normal")
                .comment("it's")
                .columnList(List.of(TableIndexColumn.builder().columnName("c`y").ascOrDesc("asc").build()))
                .build();

        String sql = HiveIndexTypeEnum.NORMAL.buildIndexScript(index);

        assertTrue(sql.contains("`i``; DROP TABLE b; --`"), sql);
        assertTrue(sql.contains("(`c``y` ASC)"), sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), sql);
    }

    @Test
    void dropIndexQuotesOldName() {
        TableIndex index = TableIndex.builder()
                .oldName("a`; DROP TABLE b; --")
                .type("Normal")
                .editStatus("DELETE")
                .build();

        assertEquals("DROP INDEX `a``; DROP TABLE b; --`", HiveIndexTypeEnum.NORMAL.buildModifyIndex(index));
    }

    @Test
    void dropTableQuotesIdentifier() {
        HiveDBManager manager = new HiveDBManager();
        assertEquals("drop table if exists `a``; DROP TABLE b; --`",
                manager.dropTable(null, null, null, "a`; DROP TABLE b; --"));
    }

    @Test
    void metaDataFormatAndNameQuoteIdentifiers() {
        assertEquals("`a``b`", HiveMetaData.format("a`b"));
        assertEquals("`a``; DROP TABLE b; --`", HiveMetaData.format("a`; DROP TABLE b; --"));
        assertEquals("`db`.`a``b`", new HiveMetaData().getMetaDataName("ignored", "db", "a`b"));
    }

    @Test
    void complexTypeFallbackPreservesTypeAndQuotesNameAndComment() {
        TableColumn column = TableColumn.builder()
                .name("payload`x")
                .columnType("STRUCT<name:STRING,tags:ARRAY<STRING>>")
                .comment("it's")
                .build();

        assertEquals("`payload``x` STRUCT<name:STRING,tags:ARRAY<STRING>> COMMENT 'it''s'",
                HiveColumnTypeEnum.buildCreateColumnSqlSafely(column));

        column.setColumnType("STRING DEFAULT 'x'");
        assertThrows(IllegalArgumentException.class,
                () -> HiveColumnTypeEnum.buildCreateColumnSqlSafely(column));
        column.setColumnType("STRING, injected STRING");
        assertThrows(IllegalArgumentException.class,
                () -> HiveColumnTypeEnum.buildCreateColumnSqlSafely(column));
        column.setColumnType("STRING UNION SELECT secret");
        assertThrows(IllegalArgumentException.class,
                () -> HiveColumnTypeEnum.buildCreateColumnSqlSafely(column));

        column.setColumnType("ARRAY<STRUCT<code:STRING>>");
        column.setEditStatus("ADD");
        assertEquals("ADD COLUMNS (`payload``x` ARRAY<STRUCT<code:STRING>> COMMENT 'it''s')",
                HiveColumnTypeEnum.buildModifyColumnSafely(column));
    }

    @Test
    void partitionClauseAcceptsTypedColumnsAndRejectsAppendedStatements() {
        assertEquals("PARTITIONED BY (`day` STRING, region STRUCT<code:STRING>)",
                HiveSqlGuards.requirePartitionClause(
                        "partitioned by (`day` STRING, region STRUCT<code:STRING>)"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requirePartitionClause(
                        "PARTITIONED BY (`day` STRING); DROP TABLE users"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requirePartitionClause(
                        "PARTITIONED BY (`day` STRING) STORED AS ORC (`other` STRING)"));
        assertThrows(IllegalArgumentException.class,
                () -> HiveSqlGuards.requirePartitionClause("STORED AS ORC"));
    }

    @Test
    void indexRequiresAtLeastOneNamedColumn() {
        TableIndex index = TableIndex.builder().name("idx").type("Normal").columnList(List.of()).build();
        assertThrows(IllegalArgumentException.class, () -> HiveIndexTypeEnum.NORMAL.buildIndexScript(index));

        index.setColumnList(null);
        assertThrows(IllegalArgumentException.class, () -> HiveIndexTypeEnum.NORMAL.buildIndexScript(index));

        index.setColumnList(List.of(TableIndexColumn.builder().columnName(" ").build()));
        assertThrows(IllegalArgumentException.class, () -> HiveIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void inheritedDmlBuilderPathsQuoteQualifiedNamesAndColumns() {
        HiveSqlBuilder builder = new HiveSqlBuilder();
        assertEquals("SELECT * FROM `db``x`.`sc``x`.`ta``ble`",
                builder.buildSelectTable("db`x", "sc`x", "ta`ble"));

        UpdateSqlRequest request = UpdateSqlRequest.builder()
                .databaseName("db`x")
                .schemaName("sc`x")
                .tableName("ta`ble")
                .row(Map.of("co`l", "1"))
                .primaryKeyMap(Map.of("i`d", "2"))
                .build();
        assertEquals("UPDATE `db``x`.`sc``x`.`ta``ble` SET `co``l` = 1 WHERE `i``d` = 2",
                builder.buildUpdate(request));

        Table table = Table.builder()
                .databaseName("db`x")
                .name("ta`ble")
                .columnList(List.of(TableColumn.builder().name("co`l").columnType("STRING").build()))
                .indexList(List.of())
                .build();
        assertEquals("SELECT `co``l` FROM `db``x`.`ta``ble`", builder.buildTemplate(table, "SELECT"));
    }
}
