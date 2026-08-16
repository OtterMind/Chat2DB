package ai.chat2db.plugin.kingbase;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.kingbase.builder.KingBaseSqlBuilder;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseColumnTypeEnum;
import ai.chat2db.plugin.kingbase.enums.type.KingBaseIndexTypeEnum;
import ai.chat2db.plugin.kingbase.identifier.KingBaseSQLIdentifierProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingBaseSQLIdentifierProcessorTest {

    @Test
    void escapeStringDoublesSingleQuotes() {
        assertNull(KingBaseSQLIdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("plain", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertEquals("O''Brien", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertEquals("a''; DROP TABLE t; --", KingBaseSQLIdentifierProcessor.INSTANCE.escapeString("a'; DROP TABLE t; --"));
    }

    @Test
    void escapeIdentifierTreatsBoundaryQuotesAsRawContent() {
        assertNull(KingBaseSQLIdentifierProcessor.escapeIdentifier(null));
        assertEquals("plain", KingBaseSQLIdentifierProcessor.escapeIdentifier("plain"));
        assertEquals("we\"\"name", KingBaseSQLIdentifierProcessor.escapeIdentifier("we\"name"));
        assertEquals("\"\"quoted\"\"", KingBaseSQLIdentifierProcessor.escapeIdentifier("\"quoted\""));
    }

    @Test
    void quoteIdentifierConditionallyQuotes() {
        KingBaseSQLIdentifierProcessor processor = KingBaseSQLIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifier(null));
        assertEquals("", processor.quoteIdentifier(""));
        assertEquals("plain", processor.quoteIdentifier("plain"));
        assertEquals("\"UPPER\"", processor.quoteIdentifier("UPPER"));
        assertEquals("\"select\"", processor.quoteIdentifier("select"));
        assertEquals("plain", processor.quoteIdentifier("plain", null, null));
        assertEquals("\"we\"\"name\"", processor.quoteIdentifier("we\"name"));
        assertEquals("\"evil\"\"; DROP TABLE t; --\"",
                processor.quoteIdentifier("evil\"; DROP TABLE t; --"));
    }

    @Test
    void quoteIdentifierIgnoreCaseRemainsConditionalAndPreservesCase() {
        KingBaseSQLIdentifierProcessor processor = KingBaseSQLIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierIgnoreCase(null));
        assertEquals("plain", processor.quoteIdentifierIgnoreCase("plain"));
        assertEquals("\"MixedCase\"", processor.quoteIdentifierIgnoreCase("MixedCase"));
        assertEquals("\"we\"\"name\"", processor.quoteIdentifierIgnoreCase("we\"name"));
    }

    @Test
    void quoteIdentifierAlwaysWrapsAndDoublesEmbeddedQuotes() {
        KingBaseSQLIdentifierProcessor processor = KingBaseSQLIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierAlways(null));
        assertEquals("\"\"", processor.quoteIdentifierAlways(""));
        assertEquals("\"plain\"", processor.quoteIdentifierAlways("plain"));
        assertEquals("\"UPPER\"", processor.quoteIdentifierAlways("UPPER"));
        assertEquals("\"we\"\"name\"", processor.quoteIdentifierAlways("we\"name"));
        assertEquals("\"\"\"quoted\"\"\"", processor.quoteIdentifierAlways("\"quoted\""));
        assertEquals("\"evil\"\"; DROP TABLE t; --\"",
                processor.quoteIdentifierAlways("evil\"; DROP TABLE t; --"));
    }

    @Test
    void alwaysQuoteAndRemoveQuoteRoundTripExactRawIdentifiers() {
        KingBaseSQLIdentifierProcessor processor = KingBaseSQLIdentifierProcessor.INSTANCE;
        for (String raw : List.of("plain", "a\"b", "\"leading", "trailing\"", "\"both\"", "")) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void createTableNeutralizesMaliciousNamesAndComments() {
        Table table = new Table();
        table.setName("evil\"; DROP TABLE t; --");
        table.setComment("x'; DROP TABLE t; --");

        TableColumn column = new TableColumn();
        column.setName("c1");
        column.setColumnType("VARCHAR");
        table.setColumnList(List.of(column));

        TableIndex index = new TableIndex();
        index.setName("idx\"evil");
        index.setType("Normal");
        index.setTableName("evil\"; DROP TABLE t; --");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("c1");
        index.setColumnList(List.of(indexColumn));
        table.setIndexList(List.of(index));

        String script = new KingBaseSqlBuilder().buildCreateTable(table, null);
        assertTrue(script.contains("CREATE TABLE \"evil\"\"; DROP TABLE t; --\""), script);
        assertTrue(script.contains("IS 'x''; DROP TABLE t; --'"), script);
        assertTrue(script.contains("\"idx\"\"evil\""), script);
        assertTrue(script.contains("ON \"evil\"\"; DROP TABLE t; --\""), script);
    }

    @Test
    void alterTableRenameNeutralizesMaliciousNames() {
        Table oldTable = new Table();
        oldTable.setName("old_t");
        oldTable.setColumnList(List.of());
        oldTable.setIndexList(List.of());
        Table newTable = new Table();
        newTable.setName("new\"; DROP TABLE t; --");
        newTable.setColumnList(List.of());
        newTable.setIndexList(List.of());

        String script = new KingBaseSqlBuilder().buildAlterTable(oldTable, newTable);
        assertTrue(script.contains("ALTER TABLE \"old_t\""), script);
        assertTrue(script.contains("RENAME TO \"new\"\"; DROP TABLE t; --\""), script);
    }

    @Test
    void createDatabaseEscapesAndValidates() {
        Database database = new Database();
        database.setName("db\"; DROP TABLE t; --");
        database.setCharset("UTF8");
        database.setComment("c'; DROP TABLE t; --");

        String script = new KingBaseSqlBuilder().buildCreateDatabase(database);
        assertTrue(script.contains("CREATE DATABASE \"db\"\"; DROP TABLE t; --\""), script);
        assertTrue(script.contains("IS 'c''; DROP TABLE t; --'"), script);

        Database hostileCharset = new Database();
        hostileCharset.setName("db2");
        hostileCharset.setCharset("UTF8'; DROP TABLE t; --");
        String hostileCharsetSql = new KingBaseSqlBuilder().buildCreateDatabase(hostileCharset);
        assertTrue(hostileCharsetSql.contains("ENCODING  'UTF8''; DROP TABLE t; --'"), hostileCharsetSql);

        Database quotedCharset = new Database();
        quotedCharset.setName("db3");
        quotedCharset.setCharset("UTF8");
        String ok = new KingBaseSqlBuilder().buildCreateDatabase(quotedCharset);
        assertTrue(ok.contains("ENCODING  'UTF8'"), ok);
    }

    @Test
    void createSchemaNeutralizesMaliciousNames() {
        Schema schema = new Schema();
        schema.setName("sch\"; x; --");
        String script = new KingBaseSqlBuilder().buildCreateSchema(schema);
        assertTrue(script.contains("CREATE SCHEMA \"sch\"\"; x; --\""), script);
        assertTrue(script.contains("AUTHORIZATION \"SYSTEM\""), script);
    }

    @Test
    void columnTypeEnumEscapesNamesCommentsAndDefaults() {
        TableColumn column = new TableColumn();
        column.setName("c\"; x--");
        column.setColumnType("VARCHAR");
        column.setDefaultValue("O'Brien");
        String createColumn = KingBaseColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);
        assertTrue(createColumn.startsWith("\"c\"\"; x--\" VARCHAR"), createColumn);
        assertTrue(createColumn.contains("DEFAULT 'O''Brien'"), createColumn);

        TableColumn commentColumn = new TableColumn();
        commentColumn.setName("c1");
        commentColumn.setTableName("t1");
        commentColumn.setComment("y'; DROP TABLE t; --");
        String comment = KingBaseColumnTypeEnum.VARCHAR.buildComment(commentColumn, KingBaseColumnTypeEnum.VARCHAR);
        assertEquals("COMMENT ON COLUMN \"t1\".\"c1\" IS 'y''; DROP TABLE t; --';", comment);

        TableColumn badDefault = new TableColumn();
        badDefault.setName("n");
        badDefault.setColumnType("INTEGER");
        badDefault.setDefaultValue("1; DROP TABLE t--");
        assertThrows(IllegalArgumentException.class,
                () -> KingBaseColumnTypeEnum.INTEGER.buildCreateColumnSql(badDefault));

        TableColumn okDefault = new TableColumn();
        okDefault.setName("n");
        okDefault.setColumnType("INTEGER");
        okDefault.setDefaultValue("-1");
        String ok = KingBaseColumnTypeEnum.INTEGER.buildCreateColumnSql(okDefault);
        assertTrue(ok.contains("DEFAULT -1"), ok);

        TableColumn quotedDefault = new TableColumn();
        quotedDefault.setName("n");
        quotedDefault.setColumnType("TEXT");
        quotedDefault.setDefaultValue("'quoted string'");
        String okQuoted = KingBaseColumnTypeEnum.TEXT.buildCreateColumnSql(quotedDefault);
        assertTrue(okQuoted.contains("DEFAULT 'quoted string'"), okQuoted);
    }

    @Test
    void indexTypeEnumEscapesNamesAndComments() {
        TableIndex index = new TableIndex();
        index.setName("i\"; x--");
        index.setComment("y'; z--");
        String comment = KingBaseIndexTypeEnum.NORMAL.buildIndexComment(index);
        assertEquals("COMMENT ON INDEX \"i\"\"; x--\" IS 'y''; z--';", comment);

        TableIndex fk = new TableIndex();
        fk.setName("fk1");
        fk.setForeignSchemaName("s\"; x--");
        fk.setForeignTableName("ft");
        fk.setForeignColumnNamelist(List.of("c1"));
        TableIndexColumn fkColumn = new TableIndexColumn();
        fkColumn.setColumnName("c1");
        fk.setColumnList(List.of(fkColumn));
        String fkScript = KingBaseIndexTypeEnum.FOREIGN.buildIndexScript(fk);
        assertTrue(fkScript.contains("REFERENCES \"s\"\"; x--\".\"ft\" (\"c1\")"), fkScript);

        TableIndex drop = new TableIndex();
        drop.setOldName("o\"; x--");
        drop.setEditStatus(EditStatusEnum.DELETE.name());
        String dropScript = KingBaseIndexTypeEnum.NORMAL.buildModifyIndex(drop);
        assertEquals("DROP INDEX \"o\"\"; x--\"", dropScript);

        TableIndex method = new TableIndex();
        method.setName("idx");
        method.setTableName("orders");
        method.setMethod("btree); DROP TABLE t;--");
        method.setColumnList(List.of(fkColumn));
        String methodScript = KingBaseIndexTypeEnum.NORMAL.buildIndexScript(method);
        assertTrue(methodScript.contains("USING \"btree); DROP TABLE t;--\""), methodScript);
    }

    @Test
    void dbManagerQuotesObjectNames() {
        String sql = new KingBaseDBManager().dropTable(null, null, "s\"x", "t\"; x--");
        assertEquals("DROP TABLE IF EXISTS \"s\"\"x\".\"t\"\"; x--\"", sql);
        assertEquals("CREATE TABLE \"sales\".\"orders_copy\" AS TABLE \"sales\".\"orders\" WITH DATA",
                KingBaseDBManager.buildCopyTableSql("sales", "orders", "orders_copy", true));
    }

    @Test
    void metaDataNameDoublesEmbeddedQuotes() {
        String name = new KingBaseMetaData().getMetaDataName("s", "we\"ird");
        assertEquals("\"s\".\"we\"\"ird\"", name);
    }

    @Test
    void spiProcessorIsConditionalForCompletionConsumers() {
        KingBaseSQLIdentifierProcessor processor = KingBaseSQLIdentifierProcessor.INSTANCE;
        assertEquals("plain", processor.quoteIdentifier("plain"));
        assertEquals("\"UPPER\"", processor.quoteIdentifier("UPPER"));
        assertEquals("\"select\"", processor.quoteIdentifier("select"));
        assertEquals("\"we\"\"name\"", processor.quoteIdentifier("we\"name"));
        assertEquals("plain", processor.removeIdentifierQuote("\"plain\""));
        assertFalse(processor.isQuoteIdentifier("plain"));
        assertTrue(processor.isQuoteIdentifier("\"plain\""));
    }

    @Test
    void defaultAndTypeGuardsPreserveLegalSyntaxAndRejectDdlReshape() {
        for (String value : List.of("now()", "nextval('audit.event_id_seq'::regclass)",
                "timezone('UTC'::text, now())", "ARRAY[]::integer[]",
                "$tag$comma, -- and ; stay literal$tag$")) {
            assertEquals(value, KingBaseSqlGuards.requireDefaultExpression(value));
        }
        assertEquals("numeric(10,2)", KingBaseSqlGuards.requireColumnTypeExpression("numeric(10,2)"));
        assertEquals("\"Tenant\".\"InvoiceType\"[]",
                KingBaseSqlGuards.requireColumnTypeExpression("\"Tenant\".\"InvoiceType\"[]"));
        for (String value : List.of("1, injected integer", "0 NOT NULL", "0 CHECK (false)",
                "now()); DROP TABLE x", "'a'; DROP TABLE x--")) {
            assertThrows(IllegalArgumentException.class,
                    () -> KingBaseSqlGuards.requireDefaultExpression(value), value);
        }
        for (String value : List.of("text, injected integer", "text DEFAULT 0", "text); DROP TABLE t;--")) {
            assertThrows(IllegalArgumentException.class,
                    () -> KingBaseSqlGuards.requireColumnTypeExpression(value), value);
        }
    }

    @Test
    void fallbackColumnTypesAreValidatedInsteadOfDropped() {
        TableColumn column = new TableColumn();
        column.setName("state\"value");
        column.setColumnType("public.invoice_state");
        column.setNullable(0);
        column.setDefaultValue("'OPEN'::public.invoice_state");
        assertEquals("\"state\"\"value\" public.invoice_state NOT NULL DEFAULT 'OPEN'::public.invoice_state",
                KingBaseColumnTypeEnum.buildCreateColumnSqlSafely(column));

        column.setColumnType("text DEFAULT 0");
        assertThrows(IllegalArgumentException.class,
                () -> KingBaseColumnTypeEnum.buildCreateColumnSqlSafely(column));
    }

    @Test
    void inheritedBuilderPathsQuoteKingBaseIdentifiersAndIgnoreDatabaseQualifier() {
        KingBaseSqlBuilder builder = new KingBaseSqlBuilder();
        assertEquals("SELECT COUNT(1) FROM \"sales\"\"x\".\"orders\"\"x\"",
                builder.buildSelectCount("ignored_database", "sales\"x", "orders\"x"));
        assertEquals("DROP TABLE \"sales\"\"x\".\"orders\"\"x\"",
                builder.buildDropTable(new DropTableRequest("ignored_database", "sales\"x", "orders\"x")));

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
    }

    @Test
    void createTableAndCaseOnlyRenameUseQualifiedNames() {
        Table table = new Table();
        table.setSchemaName("sales\"schema");
        table.setName("orders\"table");
        table.setColumnList(List.of(TableColumn.builder().name("id\"value").columnType("INTEGER").build()));
        table.setIndexList(List.of());
        TableBuilderConfig config = TableBuilderConfig.defaultConfig();
        config.setNeedFullTableName(true);
        String createSql = new KingBaseSqlBuilder().buildCreateTable(table, config);
        assertTrue(createSql.startsWith("CREATE TABLE \"sales\"\"schema\".\"orders\"\"table\""), createSql);

        Table renamed = Table.builder().schemaName("sales").name("Orders")
                .columnList(List.of()).indexList(List.of()).build();
        Table original = Table.builder().schemaName("sales").name("orders")
                .columnList(List.of()).indexList(List.of()).build();
        assertEquals("ALTER TABLE \"sales\".\"orders\"\tRENAME TO \"Orders\";\n",
                new KingBaseSqlBuilder().buildAlterTable(original, renamed));
    }
}
