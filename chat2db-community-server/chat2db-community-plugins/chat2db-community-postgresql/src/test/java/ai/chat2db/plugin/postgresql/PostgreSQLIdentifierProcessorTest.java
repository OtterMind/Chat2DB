package ai.chat2db.plugin.postgresql;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.plugin.postgresql.builder.PostgreSQLSqlBuilder;
import ai.chat2db.plugin.postgresql.enums.PostgreSQLViewCheckOptionEnum;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLColumnTypeEnum;
import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLIndexTypeEnum;
import ai.chat2db.plugin.postgresql.identifier.PostgreSQLIdentifierProcessor;
import ai.chat2db.plugin.postgresql.value.template.PostgreSQLDmlValueTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSQLIdentifierProcessorTest {

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("a''b", PostgreSQLIdentifierProcessor.INSTANCE.escapeString("a'b"));
        assertEquals("''", PostgreSQLIdentifierProcessor.INSTANCE.escapeString("'"));
        assertEquals("plain", PostgreSQLIdentifierProcessor.INSTANCE.escapeString("plain"));
        // backslash is NOT an escape character under standard_conforming_strings=on
        assertEquals("a\\b", PostgreSQLIdentifierProcessor.INSTANCE.escapeString("a\\b"));
        assertNull(PostgreSQLIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        PostgreSQLIdentifierProcessor processor = PostgreSQLIdentifierProcessor.INSTANCE;
        // null/blank pass through
        assertNull(processor.quoteIdentifier(null));
        assertEquals("", processor.quoteIdentifier(""));
        assertEquals("   ", processor.quoteIdentifier("   "));
        // valid plain identifiers that are not reserved keywords stay unquoted
        assertEquals("plain", processor.quoteIdentifier("plain"));
        assertEquals("my_table", processor.quoteIdentifier("my_table"));
        // reserved keywords are quoted
        assertEquals("\"select\"", processor.quoteIdentifier("select"));
        assertEquals("\"USER\"", processor.quoteIdentifier("USER"));
        // anything else is wrapped with embedded-quote doubling
        assertEquals("\"weird\"\"name\"", processor.quoteIdentifier("weird\"name"));
        assertEquals("\"a\"\"; DROP TABLE b; --\"", processor.quoteIdentifier("a\"; DROP TABLE b; --"));
        // boundary quotes are raw identifier content and are doubled like embedded quotes
        assertEquals("\"\"\"a\"\"b\"\"\"", processor.quoteIdentifier("\"a\"b\""));
        assertEquals("\"\"\"quoted\"\"\"", processor.quoteIdentifier("\"quoted\""));
        // the versioned overload delegates to the same conditional behavior
        assertEquals("plain", processor.quoteIdentifier("plain", 15, 0));
        assertEquals("\"select\"", processor.quoteIdentifier("select", 15, 0));
    }

    @Test
    void quoteIdentifierIgnoreCaseRemainsConditionalAndPreservesCase() {
        PostgreSQLIdentifierProcessor processor = PostgreSQLIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierIgnoreCase(null));
        assertEquals("plain", processor.quoteIdentifierIgnoreCase("plain"));
        assertEquals("\"MyTable\"", processor.quoteIdentifierIgnoreCase("MyTable"));
        assertEquals("\"weird\"\"name\"", processor.quoteIdentifierIgnoreCase("weird\"name"));
    }

    @Test
    void quoteIdentifierAlwaysWrapsUnconditionally() {
        PostgreSQLIdentifierProcessor processor = PostgreSQLIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierAlways(null));
        assertEquals("\"\"", processor.quoteIdentifierAlways(""));
        assertEquals("\"plain\"", processor.quoteIdentifierAlways("plain"));
        assertEquals("\"my_table\"", processor.quoteIdentifierAlways("my_table"));
        assertEquals("\"weird\"\"name\"", processor.quoteIdentifierAlways("weird\"name"));
        assertEquals("\"a\"\"; DROP TABLE b; --\"", processor.quoteIdentifierAlways("a\"; DROP TABLE b; --"));
        assertEquals("\"\"\"a\"\"b\"\"\"", processor.quoteIdentifierAlways("\"a\"b\""));
        assertEquals("\"\"\"quoted\"\"\"", processor.quoteIdentifierAlways("\"quoted\""));
    }

    @Test
    void metadataNameUsesPostgresqlSchemaQualification() {
        PostgreSQLMetaData metaData = new PostgreSQLMetaData();
        assertEquals("\"orders\"", metaData.getMetaDataName("orders"));
        assertEquals("\"sales\".\"orders\"",
                metaData.getMetaDataName("ignored_database", "sales", "orders"));
    }

    @Test
    void alwaysQuoteAndRemoveQuoteRoundTripExactRawIdentifiers() {
        PostgreSQLIdentifierProcessor processor = PostgreSQLIdentifierProcessor.INSTANCE;
        for (String raw : List.of("plain", "a\"b", "\"leading", "trailing\"", "\"both\"", "")) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void requirePgNameRejectsInjection() {
        assertEquals("btree", PostgreSqlGuards.requirePgName("btree", "index method"));
        assertEquals("en_US", PostgreSqlGuards.requirePgName("en_US", "role"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgreSqlGuards.requirePgName("btree; DROP TABLE t", "index method"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgreSqlGuards.requirePgName("alice\" ", "schema owner"));
    }

    @Test
    void requireDefaultExpressionAcceptsLegitDefaults() {
        String[] valid = {"0", "-1", "1.5", "+2", "true", "FALSE", "NULL", "CURRENT_TIMESTAMP", "now",
                "now()", "gen_random_uuid()", "nextval('audit.event_id_seq'::regclass)",
                "timezone('UTC'::text, now())", "'{}'::jsonb", "ARRAY[]::integer[]",
                "CURRENT_DATE + 1", "NOT FALSE", "'a'||'b'", "'Y'", "'0'", "'O''Brien'",
                "E'line\\nfeed'", "$tag$comma, -- and ; stay literal$tag$", "''"};
        for (String value : valid) {
            assertEquals(value, PostgreSqlGuards.requireDefaultExpression(value), "should accept: " + value);
        }
    }

    @Test
    void requireDefaultExpressionRejectsDdlReshapePayloads() {
        String[] payloads = {
                "0) --", "0 --", "1, x INT", "0 NULL", "0 NOT NULL", "0 CHECK (false)",
                "0 UNIQUE", "0 DEFAULT 1",
                "'abc", "'a'--", "now()); DROP TABLE x", "'a'; DROP TABLE x--",
                "0); DROP TABLE t--"
        };
        for (String payload : payloads) {
            assertThrows(IllegalArgumentException.class,
                    () -> PostgreSqlGuards.requireDefaultExpression(payload), "should reject: " + payload);
        }
    }

    @Test
    void requireColumnTypeExpressionAcceptsPostgresqlTypesAndRejectsBreakout() {
        for (String type : List.of("numeric(10,2)", "timestamp(3) with time zone",
                "public.invoice_state", "\"Tenant\".\"InvoiceType\"", "integer[]")) {
            assertEquals(type, PostgreSqlGuards.requireColumnTypeExpression(type));
        }
        for (String type : List.of("text, injected integer", "text DEFAULT 0", "text); DROP TABLE t;--")) {
            assertThrows(IllegalArgumentException.class,
                    () -> PostgreSqlGuards.requireColumnTypeExpression(type), type);
        }
    }

    @Test
    void requireBitAndHexLiteralsValidateContent() {
        assertEquals("0101", PostgreSqlGuards.requireBitLiteral("0101"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlGuards.requireBitLiteral("2"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlGuards.requireBitLiteral("1' OR '1'='1"));
        assertEquals("deadBEEF", PostgreSqlGuards.requireHexLiteral("deadBEEF"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlGuards.requireHexLiteral("zz'; DROP TABLE t;--"));
    }

    @Test
    void requireEnumConstantRejectsUnknownOption() {
        assertEquals("CASCADED", PostgreSqlGuards.requireEnumConstant(
                "cascaded", PostgreSQLViewCheckOptionEnum.values(), "view check option"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSqlGuards.requireEnumConstant(
                "CASCADED; DROP TABLE t", PostgreSQLViewCheckOptionEnum.values(), "view check option"));
    }

    @Test
    void createTableQuotesNamesAndEscapesComment() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        Table table = Table.builder()
                .schemaName("s\"x")
                .name("a\"; DROP TABLE b; --")
                .columnList(List.of())
                .indexList(List.of())
                .comment("x'; DROP TABLE u;--")
                .build();
        TableBuilderConfig config = TableBuilderConfig.defaultConfig();
        config.setNeedFullTableName(true);

        String sql = builder.buildCreateTable(table, config);

        assertTrue(sql.contains("\"s\"\"x\".\"a\"\"; DROP TABLE b; --\""), sql);
        assertTrue(sql.contains("COMMENT ON TABLE \"s\"\"x\".\"a\"\"; DROP TABLE b; --\" "
                + "IS 'x''; DROP TABLE u;--';"), sql);
    }

    @Test
    void createDatabaseQuotesNameAndEscapesComment() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        Database database = new Database();
        database.setName("db\"x");
        database.setComment("c'd");

        String sql = builder.buildCreateDatabase(database);

        assertTrue(sql.contains("CREATE DATABASE \"db\"\"x\""), sql);
        assertTrue(sql.contains("COMMENT ON DATABASE \"db\"\"x\" IS 'c''d';"), sql);
    }

    @Test
    void createSchemaQuotesNameAndOwner() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        Schema benign = new Schema();
        benign.setName("s\"x");
        benign.setOwner("postgres");
        assertTrue(builder.buildCreateSchema(benign).contains("CREATE SCHEMA \"s\"\"x\" AUTHORIZATION \"postgres\""),
                builder.buildCreateSchema(benign));

        Schema malicious = new Schema();
        malicious.setName("s");
        malicious.setOwner("alice; DROP TABLE t");
        assertTrue(builder.buildCreateSchema(malicious)
                .contains("AUTHORIZATION \"alice; DROP TABLE t\""), builder.buildCreateSchema(malicious));
    }

    @Test
    void createViewRejectsCheckOptionInjectionAndEscapesComment() {
        PostgreSQLSqlBuilder builder = new PostgreSQLSqlBuilder();
        ModifyView malicious = new ModifyView();
        malicious.setViewName("v");
        malicious.setViewBody("select 1");
        malicious.setCheckOption("CASCADED; DROP TABLE t");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateView(malicious));

        ModifyView maliciousStorage = new ModifyView();
        maliciousStorage.setViewName("v");
        maliciousStorage.setViewBody("select 1");
        maliciousStorage.setStorageClause("TEMP; DROP TABLE t");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateView(maliciousStorage));

        ModifyView benign = new ModifyView();
        benign.setViewName("v\"x");
        benign.setViewBody("select 1");
        benign.setCheckOption("local");
        benign.setComment("c'd");
        String sql = builder.buildCreateView(benign);
        assertTrue(sql.contains("VIEW \"v\"\"x\""), sql);
        assertTrue(sql.contains("WITH LOCAL CHECK OPTION"), sql);
        assertTrue(sql.contains("is 'c''d';"), sql);
    }

    @Test
    void createColumnSqlQuotesNameAndEscapesStringDefault() {
        TableColumn column = TableColumn.builder()
                .name("a\"b")
                .columnType("VARCHAR")
                .columnSize(255)
                .defaultValue("O'Brien")
                .build();

        String sql = PostgreSQLColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertTrue(sql.contains("\"a\"\"b\""), sql);
        assertTrue(sql.contains("DEFAULT 'O''Brien'"), sql);
    }

    @Test
    void createColumnSqlRejectsRawDefaultInjection() {
        TableColumn column = TableColumn.builder()
                .name("n")
                .columnType("INT4")
                .defaultValue("0);DROP TABLE t")
                .build();

        assertThrows(IllegalArgumentException.class, () -> PostgreSQLColumnTypeEnum.INT4.buildCreateColumnSql(column));
    }

    @Test
    void createColumnSqlPreservesFunctionDefaultAndSafeFallbackType() {
        TableColumn timestamp = TableColumn.builder()
                .name("createdAt")
                .columnType("TIMESTAMP")
                .defaultValue("now()")
                .build();
        assertTrue(PostgreSQLColumnTypeEnum.TIMESTAMP.buildCreateColumnSql(timestamp)
                .contains("DEFAULT now()"));

        TableColumn castText = TableColumn.builder()
                .name("payload")
                .columnType("VARCHAR")
                .defaultValue("'{}'::text")
                .build();
        assertTrue(PostgreSQLColumnTypeEnum.VARCHAR.buildCreateColumnSql(castText)
                .contains("DEFAULT '{}'::text"));

        TableColumn custom = TableColumn.builder()
                .name("amount\"raw")
                .columnType("numeric(12,2)")
                .nullable(1)
                .defaultValue("0::numeric")
                .build();
        assertEquals("\"amount\"\"raw\" numeric(12,2) NULL DEFAULT 0::numeric",
                PostgreSQLColumnTypeEnum.buildCreateColumnSqlSafely(custom));

        TableColumn modified = TableColumn.builder()
                .name("amount\"raw")
                .columnType("numeric(12,2)")
                .editStatus("MODIFY")
                .oldColumn(TableColumn.builder().columnType("numeric(10,2)").build())
                .build();
        assertEquals("ALTER COLUMN \"amount\"\"raw\" TYPE numeric(12,2) USING "
                        + "\"amount\"\"raw\"::numeric(12,2)",
                PostgreSQLColumnTypeEnum.buildModifyColumnSafely(modified));
    }

    @Test
    void columnCommentQuotesNamesAndEscapesComment() {
        TableColumn column = TableColumn.builder()
                .schemaName("s\"x")
                .tableName("t\"x")
                .name("c")
                .columnType("TEXT")
                .comment("it's")
                .build();

        String sql = PostgreSQLColumnTypeEnum.TEXT.buildComment(column, PostgreSQLColumnTypeEnum.TEXT);

        assertEquals("COMMENT ON COLUMN \"s\"\"x\".\"t\"\"x\".\"c\" IS 'it''s';", sql);
    }

    @Test
    void modifyColumnDeleteQuotesName() {
        TableColumn column = TableColumn.builder()
                .name("a\"b")
                .columnType("TEXT")
                .editStatus("DELETE")
                .build();

        assertEquals("DROP COLUMN \"a\"\"b\"", PostgreSQLColumnTypeEnum.TEXT.buildModifyColumn(column));
    }

    @Test
    void indexScriptQuotesNamesAndMethod() {
        TableIndex tableIndex = TableIndex.builder()
                .schemaName("s\"x")
                .name("i\"x")
                .type("Normal")
                .tableName("t\"b")
                .method("btree")
                .columnList(List.of(TableIndexColumn.builder().columnName("c\"d").build()))
                .build();

        String sql = PostgreSQLIndexTypeEnum.NORMAL.buildIndexScript(tableIndex);

        assertTrue(sql.contains("\"i\"\"x\""), sql);
        assertTrue(sql.contains("ON \"s\"\"x\".\"t\"\"b\""), sql);
        assertTrue(sql.contains("USING \"btree\""), sql);
        assertTrue(sql.contains("(\"c\"\"d\")"), sql);

        TableIndex evilMethod = TableIndex.builder()
                .name("i")
                .type("Normal")
                .tableName("t")
                .method("btree; DROP TABLE t")
                .columnList(List.of(TableIndexColumn.builder().columnName("c").build()))
                .build();
        assertTrue(PostgreSQLIndexTypeEnum.NORMAL.buildIndexScript(evilMethod)
                .contains("USING \"btree; DROP TABLE t\""));
    }

    @Test
    void indexCommentAndDropQuoteNamesAndEscapeComment() {
        TableIndex tableIndex = TableIndex.builder()
                .schemaName("s\"x")
                .name("i\"x")
                .type("Normal")
                .comment("c'd")
                .build();
        assertEquals("COMMENT ON INDEX \"s\"\"x\".\"i\"\"x\" IS 'c''d';",
                PostgreSQLIndexTypeEnum.NORMAL.buildIndexComment(tableIndex));

        TableIndex dropped = TableIndex.builder()
                .schemaName("s\"x")
                .name("i")
                .oldName("i\"x")
                .type("Normal")
                .editStatus("DELETE")
                .build();
        assertEquals("DROP INDEX \"s\"\"x\".\"i\"\"x\"",
                PostgreSQLIndexTypeEnum.NORMAL.buildModifyIndex(dropped));

        TableIndex constraint = TableIndex.builder()
                .schemaName("s\"x")
                .tableName("t\"x")
                .name("pk\"x")
                .type("Primary")
                .comment("c'd")
                .build();
        assertEquals("COMMENT ON CONSTRAINT \"pk\"\"x\" ON \"s\"\"x\".\"t\"\"x\" IS 'c''d';",
                PostgreSQLIndexTypeEnum.PRIMARY.buildIndexComment(constraint));
    }

    @Test
    void dmlValueTemplatesEscapeOrValidate() {
        assertEquals("B'0101'", PostgreSQLDmlValueTemplate.wrapBit("0101"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSQLDmlValueTemplate.wrapBit("1' OR '1'='1"));
        assertEquals("E'\\\\xdeadbeef'::bytea", PostgreSQLDmlValueTemplate.wrapBytea("deadbeef"));
        assertThrows(IllegalArgumentException.class, () -> PostgreSQLDmlValueTemplate.wrapBytea("zz'; DROP TABLE t;--"));
        assertEquals("'{\"a\":\"b\"}'::json", PostgreSQLDmlValueTemplate.wrapJson("{\"a\":\"b\"}"));
        assertEquals("'x''y'::jsonb", PostgreSQLDmlValueTemplate.wrapJsonb("x'y"));
    }

    @Test
    void conditionalQuoteKeepsMixedCaseQuoted() {
        PostgreSQLIdentifierProcessor processor = new PostgreSQLIdentifierProcessor();
        // PostgreSQL folds unquoted identifiers to lowercase: mixed-case names must stay quoted.
        assertEquals("\"MyTable\"", processor.quoteIdentifier("MyTable"));
        assertEquals("mytable", processor.quoteIdentifier("mytable"));
        assertEquals("plain_name", processor.quoteIdentifier("plain_name"));
        org.junit.jupiter.api.Assertions.assertNull(processor.quoteIdentifier(null));
        assertEquals("\"SELECT\"", processor.quoteIdentifier("SELECT"));
    }
}
