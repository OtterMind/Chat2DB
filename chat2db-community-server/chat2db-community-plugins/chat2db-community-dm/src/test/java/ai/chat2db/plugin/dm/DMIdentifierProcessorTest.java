package ai.chat2db.plugin.dm;

import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.enums.plugin.DmlTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.plugin.dm.builder.DMSqlBuilder;
import ai.chat2db.plugin.dm.enums.type.DMColumnTypeEnum;
import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.plugin.dm.identifier.DMIdentifierProcessor;
import ai.chat2db.plugin.dm.value.DMValueProcessor;
import ai.chat2db.plugin.dm.value.sub.DMBitProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
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

class DMIdentifierProcessorTest {

    @Test
    void quoteIdentifierPassesThroughNullAndBlank() {
        assertNull(DMIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertNull(DMIdentifierProcessor.INSTANCE.quoteIdentifier(null, null, null));
        assertNull(DMIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
        assertEquals("", DMIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals(" ", DMIdentifierProcessor.INSTANCE.quoteIdentifier(" "));
        assertEquals(" ", DMIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(" "));
    }

    @Test
    void quoteIdentifierPreservesDmCaseSemantics() {
        DMIdentifierProcessor processor = DMIdentifierProcessor.INSTANCE;
        assertEquals("EMPLOYEES", processor.quoteIdentifier("EMPLOYEES"));
        assertEquals("\"employees\"", processor.quoteIdentifier("employees"));
        assertEquals("\"MixedCase\"", processor.quoteIdentifier("MixedCase"));
        assertEquals("\"SELECT\"", processor.quoteIdentifier("SELECT"));
        assertEquals("\"select\"", processor.quoteIdentifier("select", null, null));
        assertEquals("\"A\"\"B\"", processor.quoteIdentifier("A\"B"));
        assertEquals("\"ALREADY\"", processor.quoteIdentifier("\"ALREADY\""));
    }

    @Test
    void quoteIdentifierIgnoreCaseIsConditional() {
        DMIdentifierProcessor processor = DMIdentifierProcessor.INSTANCE;
        assertEquals("employees", processor.quoteIdentifierIgnoreCase("employees"));
        assertEquals("EMPLOYEES", processor.quoteIdentifierIgnoreCase("EMPLOYEES"));
        assertEquals("\"select\"", processor.quoteIdentifierIgnoreCase("select"));
        assertEquals("\"A\"\"B\"", processor.quoteIdentifierIgnoreCase("A\"B"));
    }

    @Test
    void quoteIdentifierAlwaysRoundTripsEveryRawName() {
        DMIdentifierProcessor processor = DMIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierAlways(null));
        String[] rawIdentifiers = {"", "plain", "SELECT", "MixedCase", "A\"B", "\"ALREADY\"",
                "\"A", "A\"", "\"\"", "A\"\"B"};
        for (String raw : rawIdentifiers) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)),
                    "always-quote round trip must preserve the raw identifier");
        }
    }

    @Test
    void stringAndIdentifierContentEscapersEncodeEveryDelimiter() {
        assertEquals("O''Brien", DMIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertEquals("'C:\\tmp\\O''Brien'", DMIdentifierProcessor.INSTANCE.quoteStringLiteral("C:\\tmp\\O'Brien"));
        assertNull(DMIdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("a\"\"b", DMIdentifierProcessor.escapeIdentifier("a\"b"));
        assertEquals("\"\"already\"\"", DMIdentifierProcessor.escapeIdentifier("\"already\""));
    }

    @Test
    void reservedWordsAndCaseConversionAreLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(DMIdentifierProcessor.INSTANCE.isReservedKeyword("insert", null, null));
            assertEquals("ID", DMIdentifierProcessor.INSTANCE.convertIdentifierCase("id"));
            assertEquals("\"insert\"", DMIdentifierProcessor.INSTANCE.quoteIdentifier("insert"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void createTableEscapesNamesAndComments() {
        Table table = table("S\"CHEMA", "T\"ABLE", "VARCHAR");
        table.setComment("x'); DROP TABLE USERS; --");
        TableColumn column = table.getColumnList().get(0);
        column.setName("C\"OL");
        column.setComment("c'); DROP TABLE U; --");

        String sql = new DMSqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.startsWith("CREATE TABLE \"S\"\"CHEMA\".\"T\"\"ABLE\" ("), sql);
        assertTrue(sql.contains("COMMENT ON COLUMN \"S\"\"CHEMA\".\"T\"\"ABLE\".\"C\"\"OL\" IS 'c''); DROP TABLE U; --'"), sql);
        assertTrue(sql.contains("COMMENT ON TABLE \"S\"\"CHEMA\".\"T\"\"ABLE\" IS 'x''); DROP TABLE USERS; --'"), sql);
    }

    @Test
    void createTableOmitsBlankQualifierInsteadOfRenderingNullSchema() {
        Table table = table(null, "ORDERS", "INT");

        String sql = new DMSqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.startsWith("CREATE TABLE \"ORDERS\" ("), sql);
        assertFalse(sql.contains("\"null\""), sql);
    }

    @Test
    void managerUsesSchemaQualifiedNames() throws Exception {
        DMDBManager manager = new DMDBManager();
        assertEquals("DROP TABLE IF EXISTS \"SA\"\"LES\".\"ORDERS\"",
                manager.dropTable(null, "ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("TRUNCATE TABLE \"SA\"\"LES\".\"OR\"\"DERS\"",
                manager.truncateTable(null, "ignored_database", "SA\"LES", "\"OR\"\"DERS\""));
        assertEquals("DROP TABLE IF EXISTS \"T\"\"; DROP TABLE U; --\"",
                manager.dropTable(null, null, null, "T\"; DROP TABLE U; --"));
    }

    @Test
    void inheritedBuilderPathsUseDmQualification() {
        DMSqlBuilder builder = new DMSqlBuilder();
        assertEquals("SELECT * FROM \"SA\"\"LES\".\"ORDERS\"",
                builder.buildSelectTable("ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("SELECT COUNT(1) FROM \"SA\"\"LES\".\"ORDERS\"",
                builder.buildSelectCount("ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("DROP TABLE \"SA\"\"LES\".\"ORDERS\"",
                builder.buildDropTable(new DropTableRequest("ignored_database", "SA\"LES", "ORDERS")));
        assertEquals("TRUNCATE TABLE \"SA\"\"LES\".\"ORDERS\"",
                builder.buildTruncateTable(new TruncateTableRequest("ignored_database", "SA\"LES", "ORDERS")));
        assertEquals("INSERT INTO \"SA\"\"LES\".\"ORDERS\" (\"C\"\"OL\") VALUES (1)",
                builder.buildInsert(SingleInsertSqlRequest.builder()
                        .databaseName("ignored_database")
                        .schemaName("SA\"LES")
                        .tableName("ORDERS")
                        .columnList(List.of("C\"OL"))
                        .valueList(List.of("1"))
                        .build()));
        assertEquals("UPDATE \"SA\"\"LES\".\"ORDERS\" SET \"C\"\"OL\" = 1 WHERE \"I\"\"D\" = 2",
                builder.buildUpdate(UpdateSqlRequest.builder()
                        .databaseName("ignored_database")
                        .schemaName("SA\"LES")
                        .tableName("ORDERS")
                        .row(Map.of("C\"OL", "1"))
                        .primaryKeyMap(Map.of("I\"D", "2"))
                        .build()));
    }

    @Test
    void dmlTemplatesAlwaysQuoteSchemaTableAndColumns() {
        Table table = table("SA\"LES", "OR\"DERS", "INT");
        table.getColumnList().get(0).setName("C\"OL");
        DMSqlBuilder builder = new DMSqlBuilder();

        assertEquals("INSERT INTO \"SA\"\"LES\".\"OR\"\"DERS\" (\"C\"\"OL\") VALUES ( )",
                builder.buildTemplate(table, DmlTypeEnum.INSERT.name()));
        assertEquals("UPDATE \"SA\"\"LES\".\"OR\"\"DERS\" SET \"C\"\"OL\" =   WHERE ",
                builder.buildTemplate(table, DmlTypeEnum.UPDATE.name()));
        assertEquals("DELETE FROM \"SA\"\"LES\".\"OR\"\"DERS\" WHERE ",
                builder.buildTemplate(table, DmlTypeEnum.DELETE.name()));
        assertEquals("SELECT \"C\"\"OL\" FROM \"SA\"\"LES\".\"OR\"\"DERS\"",
                builder.buildTemplate(table, DmlTypeEnum.SELECT.name()));
    }

    @Test
    void metadataQualifiedNamesAreLimitedToSchemaAndObject() {
        DMMetaData metaData = new DMMetaData();
        assertEquals("\"SALES\".\"ORDERS\"",
                metaData.getMetaDataName("ignored_database", "SALES", "ORDERS"));
        assertEquals("\"SA\"\"LES\".\"OR\"\"DERS\"",
                metaData.getMetaDataName("SA\"LES", "OR\"DERS"));
    }

    @Test
    void createSchemaQuotesNameAndOwner() {
        Schema schema = new Schema();
        schema.setName("app");
        schema.setOwner("owner; DROP USER x; --");

        assertEquals("CREATE SCHEMA \"app\" AUTHORIZATION \"owner; DROP USER x; --\"",
                new DMSqlBuilder().buildCreateSchema(schema));
    }

    @Test
    void indexScriptEscapesNamesAndCanonicalizesDirection() {
        TableIndex index = new TableIndex();
        index.setType(DMIndexTypeEnum.NORMAL.getName());
        index.setSchemaName("S\"; X");
        index.setTableName("T");
        index.setName("I\"X");
        TableIndexColumn column = new TableIndexColumn();
        column.setColumnName("C\"D");
        column.setAscOrDesc(" desc ");
        index.setColumnList(List.of(column));

        assertEquals("CREATE INDEX \"S\"\"; X\".\"I\"\"X\" ON \"S\"\"; X\".\"T\" (\"C\"\"D\" DESC)",
                DMIndexTypeEnum.NORMAL.buildIndexScript(index));

        column.setAscOrDesc("DESC; DROP TABLE x; --");
        assertThrows(IllegalArgumentException.class, () -> DMIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void knownColumnTypeAcceptsOnlySupportedUnit() {
        TableColumn column = column("c1", "VARCHAR");
        column.setColumnSize(10);
        column.setUnit("byte");
        column.setDefaultValue("'O''Brien'");

        String sql = DMColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertTrue(sql.contains("VARCHAR(10 byte)"), sql);
        assertTrue(sql.contains("DEFAULT 'O''Brien'"), sql);

        column.setUnit("BYTE); DROP TABLE U; --");
        assertThrows(IllegalArgumentException.class,
                () -> DMColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));
    }

    @Test
    void defaultExpressionAcceptsLegitimateDmForms() {
        String[] valid = {"SYSDATE", "CURRENT_TIMESTAMP", "USER", "SEQ.NEXTVAL", "-1", "1.5",
                "'Y'", "'O''Brien'", "N'abc'", "X'1A'", "SYS_GUID()",
                "NVL(SUM(x),0)", "TO_DATE('1970-01-01', 'YYYY-MM-DD')",
                "CAST('1' AS NUMBER(10,2))", "\"My Seq\".NEXTVAL",
                "TIMESTAMP '2020-01-01 00:00:00'", "INTERVAL '1' DAY",
                "q'[O'Brien]'", "'a'||'b'", "now()"};
        for (String defaultValue : valid) {
            assertEquals(defaultValue, DMSqlGuards.requireDefaultExpression(defaultValue), defaultValue);
        }
    }

    @Test
    void defaultExpressionRejectsFragmentsThatReshapeDdl() {
        String[] payloads = {"0) --", "0 --", "1, x INT", "0); DROP TABLE x--", "'abc", "0\n+1",
                "'a'--", "'a'; DROP TABLE x--", "0 NOT NULL", "0 CHECK (1=1)",
                "0 CONSTRAINT injected UNIQUE", "x' OR '1'='1", "NVL(1,/*comment*/0)"};
        for (String payload : payloads) {
            assertThrows(IllegalArgumentException.class,
                    () -> DMSqlGuards.requireDefaultExpression(payload), payload);
        }
    }

    @Test
    void unknownColumnTypesArePreservedOnlyWhenStructurallySafe() {
        String[] valid = {"MYCUSTOMTYPE", "VARCHAR(20)", "NUMBER(10,2)",
                "TIMESTAMP(6) WITH TIME ZONE", "INTERVAL DAY(2) TO SECOND(6)",
                "VARCHAR(20 CHAR)", "\"APP\".\"Order Type\"", "REF \"APP\".\"Object Type\""};
        for (String typeName : valid) {
            TableColumn column = column("c1", typeName);
            assertEquals("\"c1\" " + typeName,
                    DMColumnTypeEnum.VARCHAR.buildCreateColumnSql(column), typeName);
        }

        String[] payloads = {"INTEGER); DROP TABLE U; --", "INT, x INT", "INT'--", "INT\"--",
                "0) --", "INTEGER NOT NULL", "VARCHAR(20) DEFAULT 0", "INTEGER CHECK(1=1)"};
        for (String typeName : payloads) {
            TableColumn column = column("c1", typeName);
            assertThrows(IllegalArgumentException.class,
                    () -> DMColumnTypeEnum.VARCHAR.buildCreateColumnSql(column), typeName);
        }
    }

    @Test
    void createTableKeepsSafeUnknownTypeInsteadOfDroppingColumn() {
        Table table = table("S", "T", "\"APP\".\"Order Type\"");

        String sql = new DMSqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.contains("\"C\" \"APP\".\"Order Type\""), sql);
    }

    @Test
    void caseOnlyColumnRenameIsNotSkipped() {
        TableColumn column = column("mixedcase", "VARCHAR");
        column.setEditStatus("MODIFY");
        column.setSchemaName("S");
        column.setTableName("T");
        column.setOldName("MixedCase");

        String sql = DMColumnTypeEnum.VARCHAR.buildModifyColumn(column);

        assertTrue(sql.contains("RENAME COLUMN \"MixedCase\" TO \"mixedcase\""), sql);
    }

    @Test
    void caseOnlyTableRenameIsNotSkipped() {
        Table oldTable = table("S", "MixedCase", "VARCHAR");
        Table newTable = table("S", "mixedcase", "VARCHAR");

        String sql = new DMSqlBuilder().buildAlterTable(oldTable, newTable);

        assertTrue(sql.startsWith("ALTER TABLE \"S\".\"MixedCase\" RENAME TO \"mixedcase\""), sql);
    }

    @Test
    void indexRequiresAtLeastOneNamedColumn() {
        TableIndex index = new TableIndex();
        index.setSchemaName("S");
        index.setTableName("T");
        index.setName("IDX");
        index.setColumnList(List.of(new TableIndexColumn()));

        assertThrows(IllegalArgumentException.class,
                () -> DMIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void bitValuesAreCanonicalizedInsteadOfEmittedAsRawSql() {
        DMBitProcessor processor = new DMBitProcessor();
        SQLDataValue value = new SQLDataValue();
        value.setValue(" true ");
        assertEquals("1", processor.convertSQLValueByType(value));
        value.setValue("false");
        assertEquals("0", processor.convertSQLValueByType(value));
        value.setValue(" ");
        assertEquals("NULL", processor.convertSQLValueByType(value));
        value.setValue("1); DROP TABLE U; --");
        assertThrows(IllegalArgumentException.class, () -> processor.convertSQLValueByType(value));
    }

    @Test
    void dmlValueFallbackEscapesStringLiteralContent() {
        SQLDataValue value = new SQLDataValue();
        value.setValue("O'Brien");
        DataType type = new DataType();
        type.setDataTypeName("VARCHAR");
        value.setDataType(type);

        assertEquals("'O''Brien'", new DMValueProcessor().convertSQLValueByType(value));
    }

    private static Table table(String schemaName, String tableName, String columnType) {
        Table table = new Table();
        table.setSchemaName(schemaName);
        table.setName(tableName);
        TableColumn column = column("C", columnType);
        column.setSchemaName(schemaName);
        column.setTableName(tableName);
        table.setColumnList(List.of(column));
        table.setIndexList(List.of());
        return table;
    }

    private static TableColumn column(String name, String columnType) {
        TableColumn column = new TableColumn();
        column.setName(name);
        column.setColumnType(columnType);
        return column;
    }
}
