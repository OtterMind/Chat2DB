package ai.chat2db.plugin.sqlite;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.sqlite.builder.SqliteBuilder;
import ai.chat2db.plugin.sqlite.constant.SqliteMetaDataConstants;
import ai.chat2db.plugin.sqlite.enums.type.SqliteColumnTypeEnum;
import ai.chat2db.plugin.sqlite.enums.type.SqliteIndexTypeEnum;
import ai.chat2db.plugin.sqlite.identifier.SqliteIdentifierProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteIdentifierProcessorTest {

    @Test
    void escapeStringDoublesSingleQuotes() {
        assertEquals("O''Brien", SqliteIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertEquals("''", SqliteIdentifierProcessor.INSTANCE.escapeString("'"));
        assertEquals("plain", SqliteIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(SqliteIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void escapeIdentifierDoublesDoubleQuotesAndStripsWrappingQuotes() {
        assertEquals("WE\"\"IRD", SqliteIdentifierProcessor.escapeIdentifier("WE\"IRD"));
        assertEquals("\"\"ALREADY\"\"", SqliteIdentifierProcessor.escapeIdentifier("\"ALREADY\""));
        assertNull(SqliteIdentifierProcessor.escapeIdentifier(null));
        assertEquals("\"\"\"\"", SqliteIdentifierProcessor.escapeIdentifier("\"\""));
        assertEquals("\"A\"\"B\"", SqliteIdentifierProcessor.INSTANCE.quoteIdentifier("A\"B"));
        assertEquals("\"\"\"\"", SqliteIdentifierProcessor.INSTANCE.quoteIdentifier("\""));
    }

    @Test
    void alwaysQuoteRoundTripsEveryRawIdentifierShape() {
        for (String raw : List.of("plain", "a\"b", "\"already\"", "\"", "a.b", "", " ")) {
            assertEquals(raw, SqliteIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                    SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)), raw);
        }
        assertNull(SqliteIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
    }

    @Test
    void conditionalQuoteHandlesReservedAndAlreadyQuotedIdentifiers() {
        assertEquals("plain_name", SqliteIdentifierProcessor.INSTANCE.quoteIdentifier("plain_name"));
        assertEquals("\"SELECT\"", SqliteIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("\"a\"\"b\"", SqliteIdentifierProcessor.INSTANCE.quoteIdentifier("\"a\"\"b\""));
    }

    @Test
    void metadataSqlTemplatesNeutralizeLiteralInjection() {
        String payload = "v' OR '1'='1";
        String sql = String.format(SqliteMetaDataConstants.VIEW_DDL_SQL, SqliteIdentifierProcessor.INSTANCE.escapeString(payload));
        assertTrue(sql.contains("name='v'' OR ''1''=''1';"), sql);
        assertFalse(sql.contains("name='v' OR"), sql);
    }

    @Test
    void getMetaDataNameNeutralizesEmbeddedQuotes() {
        SqliteMetaData metaData = new SqliteMetaData();
        String result = metaData.getMetaDataName("main", "A\".\"B");
        assertEquals("\"main\".\"A\"\".\"\"B\"", result);
        assertFalse(result.contains("A\".\"B\"."), "injection payload must not break out of the quoted identifier");
    }

    @Test
    void identifierProcessorDoublesEmbeddedQuotes() {
        SqliteIdentifierProcessor processor = new SqliteIdentifierProcessor();
        assertEquals("plain_name", processor.quoteIdentifier("plain_name"));
        assertEquals("\"a\"\"b\"", processor.quoteIdentifier("a\"b"));
        assertEquals("\"a\"\"; DROP TABLE t; --\"", processor.quoteIdentifier("a\"; DROP TABLE t; --"));
    }

    @Test
    void createTableEscapesNamesAndFlattensComments() {
        Table table = Table.builder()
                .databaseName("main")
                .name("t\"; DROP TABLE u; --")
                .columnList(List.of(TableColumn.builder()
                        .name("c\"d")
                        .columnType("TEXT")
                        .comment("x\n); DROP TABLE u; --")
                        .build()))
                .indexList(List.of())
                .build();

        String sql = new SqliteBuilder().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("\"t\"\"; DROP TABLE u; --\""), sql);
        assertTrue(sql.contains("\"c\"\"d\""), sql);
        assertFalse(sql.contains("x\n"), "comment must not break out of the -- line comment");
    }

    @Test
    void createTableRejectsHostileFreeTextColumnType() {
        Table table = Table.builder()
                .name("t")
                .columnList(List.of(TableColumn.builder()
                        .name("c")
                        .columnType("TEXT); DROP TABLE u; --")
                        .build()))
                .indexList(List.of())
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> new SqliteBuilder().buildCreateTable(table, TableBuilderConfig.defaultConfig()));
    }

    @Test
    void alterTableEscapesRenameTarget() {
        Table oldTable = Table.builder().databaseName("main").name("users").columnList(List.of()).indexList(List.of()).build();
        Table newTable = Table.builder().databaseName("main").name("u\"; DROP TABLE t; --").columnList(List.of()).indexList(List.of()).build();

        String sql = new SqliteBuilder().buildAlterTable(oldTable, newTable);

        assertTrue(sql.contains("RENAME TO \"u\"\"; DROP TABLE t; --\""), sql);
    }

    @Test
    void alterTableOmitsBlankDatabaseInsteadOfRenderingNullIdentifier() {
        Table oldTable = Table.builder().name("users").columnList(List.of()).indexList(List.of()).build();
        Table newTable = Table.builder().name("people").columnList(List.of()).indexList(List.of()).build();

        String sql = new SqliteBuilder().buildAlterTable(oldTable, newTable);

        assertTrue(sql.startsWith("ALTER TABLE \"users\""), sql);
        assertFalse(sql.contains("\"null\""), sql);
    }

    @Test
    void indexScriptQuotesIndexTableAndColumnNames() {
        TableIndex tableIndex = TableIndex.builder()
                .name("i\"x")
                .type("Normal")
                .tableName("t\"y")
                .columnList(List.of(TableIndexColumn.builder().columnName("c\"z").build()))
                .build();

        String sql = SqliteIndexTypeEnum.NORMAL.buildIndexScript(tableIndex);

        assertTrue(sql.contains("INDEX \"i\"\"x\" ON \"t\"\"y\" (\"c\"\"z\")"), sql);
    }

    @Test
    void createColumnSqlQuotesNameAndAcceptsBuiltinCollation() {
        TableColumn column = TableColumn.builder()
                .name("c\"d")
                .columnType("TEXT")
                .collationName("NOCASE")
                .build();

        String sql = SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(column);

        assertTrue(sql.contains("\"c\"\"d\""), sql);
        assertTrue(sql.contains("COLLATE NOCASE"), sql);
    }

    @Test
    void createColumnSqlRejectsHostileCollation() {
        TableColumn column = TableColumn.builder()
                .name("c")
                .columnType("TEXT")
                .collationName("NOCASE; DROP TABLE t; --")
                .build();

        assertThrows(IllegalArgumentException.class, () -> SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(column));
    }

    @Test
    void createColumnSqlPreservesLegalDefaultAndRejectsConstraintBreakout() {
        TableColumn legal = TableColumn.builder()
                .name("created_at")
                .columnType("TEXT")
                .defaultValue("strftime('%Y-%m-%d','now')")
                .build();
        assertTrue(SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(legal)
                .contains("DEFAULT strftime('%Y-%m-%d','now')"));

        TableColumn hostile = TableColumn.builder()
                .name("created_at")
                .columnType("TEXT")
                .defaultValue("CURRENT_TIMESTAMP UNIQUE")
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> SqliteColumnTypeEnum.TEXT.buildCreateColumnSql(hostile));
    }

    @Test
    void requireSafeTypeNameAcceptsRealTypesAndRejectsInjection() {
        assertEquals("VARCHAR(255)", SqliteSqlGuards.requireSafeTypeName("VARCHAR(255)"));
        assertEquals("NUMERIC(10,2)", SqliteSqlGuards.requireSafeTypeName("NUMERIC(10,2)"));
        assertEquals("DOUBLE PRECISION", SqliteSqlGuards.requireSafeTypeName("DOUBLE PRECISION"));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteSqlGuards.requireSafeTypeName("TEXT); DROP TABLE u; --"));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteSqlGuards.requireSafeTypeName("TEXT\")"));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteSqlGuards.requireSafeTypeName("TEXT NOT NULL"));
        assertThrows(IllegalArgumentException.class,
                () -> SqliteSqlGuards.requireSafeTypeName("NUMERIC(10,2"));
        assertNull(SqliteSqlGuards.requireSafeTypeName(null));
    }

    @Test
    void escapeColumnDefaultKeepsQuotedLiteralsAndExpressions() {
        assertEquals("'O''Brien'", SqliteSqlGuards.escapeColumnDefault("'O''Brien'"));
        assertEquals("''", SqliteSqlGuards.escapeColumnDefault("''"));
        assertEquals("42", SqliteSqlGuards.escapeColumnDefault("42"));
        assertEquals("-1.5", SqliteSqlGuards.escapeColumnDefault("-1.5"));
        assertEquals("CURRENT_TIMESTAMP", SqliteSqlGuards.escapeColumnDefault("CURRENT_TIMESTAMP"));
        assertEquals("(1+2)", SqliteSqlGuards.escapeColumnDefault("(1+2)"));
        assertEquals("strftime('%Y-%m-%d','now')",
                SqliteSqlGuards.escapeColumnDefault("strftime('%Y-%m-%d','now')"));
        assertEquals("(json_extract('{\"a\":1}', '$.a'))",
                SqliteSqlGuards.escapeColumnDefault("(json_extract('{\"a\":1}', '$.a'))"));
        assertEquals("", SqliteSqlGuards.escapeColumnDefault(null));
    }

    @Test
    void escapeColumnDefaultRejectsStatementAndConstraintBreakout() {
        for (String payload : List.of(
                "'x'); DROP TABLE u; --'",
                "0; DROP TABLE u; --",
                "0 NOT NULL",
                "NULL REFERENCES victims",
                "CURRENT_TIMESTAMP UNIQUE",
                "f(1",
                "1, other")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqliteSqlGuards.escapeColumnDefault(payload), payload);
        }
    }

    @Test
    void sanitizeLineCommentFlattensLineBreaks() {
        assertEquals("x ); DROP TABLE u; --", SqliteSqlGuards.sanitizeLineComment("x\n); DROP TABLE u; --"));
        assertEquals("a  b", SqliteSqlGuards.sanitizeLineComment("a\r\nb"));
        assertEquals("", SqliteSqlGuards.sanitizeLineComment(null));
    }

    @Test
    void inheritedBuilderPathsAlwaysQuoteIdentifiers() {
        SqliteBuilder builder = new SqliteBuilder();
        String database = "ma\"in";
        String table = "ta\"ble";
        String column = "co\"l";

        assertTrue(builder.buildSelectTable(database, null, table)
                .contains("\"ma\"\"in\".\"ta\"\"ble\""));
        assertTrue(builder.buildSelectCount(database, null, table)
                .contains("\"ma\"\"in\".\"ta\"\"ble\""));
        assertTrue(builder.buildDropTable(new DropTableRequest(database, null, table))
                .endsWith("\"ma\"\"in\".\"ta\"\"ble\""));

        String insert = builder.buildInsert(SingleInsertSqlRequest.builder()
                .databaseName(database)
                .tableName(table)
                .columnList(List.of(column))
                .valueList(List.of("1"))
                .build());
        assertTrue(insert.contains("\"ma\"\"in\".\"ta\"\"ble\" (\"co\"\"l\")"), insert);

        String update = builder.buildUpdate(UpdateSqlRequest.builder()
                .databaseName(database)
                .tableName(table)
                .row(Map.of(column, "1"))
                .primaryKeyMap(Map.of("id\"x", "2"))
                .build());
        assertTrue(update.contains("UPDATE \"ma\"\"in\".\"ta\"\"ble\" SET \"co\"\"l\" = 1"), update);
        assertTrue(update.contains("WHERE \"id\"\"x\" = 2"), update);
    }

    @Test
    void templatesAndDropIndexAlwaysQuoteIdentifiers() {
        Table table = Table.builder()
                .name("ta\"ble")
                .columnList(List.of(TableColumn.builder().name("co\"l").build()))
                .build();
        String template = new SqliteBuilder().buildTemplate(table, "SELECT");
        assertEquals("SELECT \"co\"\"l\" FROM \"ta\"\"ble\"", template);

        TableIndex index = TableIndex.builder()
                .type("Normal")
                .oldName("old\"index")
                .editStatus("DELETE")
                .build();
        assertEquals("DROP INDEX \"old\"\"index\"", SqliteIndexTypeEnum.NORMAL.buildModifyIndex(index));
    }
}
