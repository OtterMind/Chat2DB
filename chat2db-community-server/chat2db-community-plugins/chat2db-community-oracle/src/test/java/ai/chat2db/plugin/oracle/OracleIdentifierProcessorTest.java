package ai.chat2db.plugin.oracle;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.plugin.oracle.builder.OracleSqlBuilder;
import ai.chat2db.plugin.oracle.enums.type.OracleColumnTypeEnum;
import ai.chat2db.plugin.oracle.enums.type.OracleIndexTypeEnum;
import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.plugin.oracle.value.sub.OracleRawValueProcessor;
import ai.chat2db.plugin.oracle.value.template.OracleDmlValueTemplate;
import ai.chat2db.plugin.oracle.value.OracleValueProcessor;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import com.google.common.io.BaseEncoding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleIdentifierProcessorTest {

    @Test
    void quoteIdentifierPassesThroughNullAndBlank() {
        assertNull(OracleIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertNull(OracleIdentifierProcessor.INSTANCE.quoteIdentifier(null, 12, 2));
        assertNull(OracleIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
        assertEquals("", OracleIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals(" ", OracleIdentifierProcessor.INSTANCE.quoteIdentifier(" "));
        assertEquals(" ", OracleIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(" "));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        OracleIdentifierProcessor processor = OracleIdentifierProcessor.INSTANCE;
        // plain uppercase identifier: returned unquoted
        assertEquals("EMPLOYEES", processor.quoteIdentifier("EMPLOYEES"));
        // lowercase: quoted to preserve case
        assertEquals("\"employees\"", processor.quoteIdentifier("employees"));
        // reserved keyword: quoted
        assertEquals("\"SELECT\"", processor.quoteIdentifier("SELECT"));
        assertEquals("\"TABLE\"", processor.quoteIdentifier("TABLE", 12, 2));
        // invalid characters: quoted with embedded quotes doubled
        assertEquals("\"A\"\"B\"", processor.quoteIdentifier("A\"B"));
        assertEquals("\"WE IRD\"", processor.quoteIdentifier("WE IRD"));
        // already-quoted input is unwrapped once, not double-wrapped
        assertEquals("\"ALREADY\"", processor.quoteIdentifier("\"ALREADY\""));
        // versioned overload delegates to the single-arg conditional variant
        assertEquals(processor.quoteIdentifier("employees"), processor.quoteIdentifier("employees", 19, 0));
    }

    @Test
    void quoteIdentifierIgnoreCasePreservesCaseAndIsConditional() {
        OracleIdentifierProcessor processor = OracleIdentifierProcessor.INSTANCE;
        assertEquals("employees", processor.quoteIdentifierIgnoreCase("employees"));
        assertEquals("EMPLOYEES", processor.quoteIdentifierIgnoreCase("EMPLOYEES"));
        assertEquals("\"select\"", processor.quoteIdentifierIgnoreCase("select"));
        assertEquals("\"A\"\"B\"", processor.quoteIdentifierIgnoreCase("A\"B"));
    }

    @Test
    void quoteIdentifierAlwaysWrapsUnconditionallyForDdlPaths() {
        OracleIdentifierProcessor processor = OracleIdentifierProcessor.INSTANCE;
        assertNull(processor.quoteIdentifierAlways(null));
        assertEquals("\"EMPLOYEES\"", processor.quoteIdentifierAlways("EMPLOYEES"));
        assertEquals("\"employees\"", processor.quoteIdentifierAlways("employees"));
        assertEquals("\"A\"\"B\"", processor.quoteIdentifierAlways("A\"B"));
        assertEquals("\"\"\"ALREADY\"\"\"", processor.quoteIdentifierAlways("\"ALREADY\""));

        String[] rawIdentifiers = {"", "A\"B", "\"ALREADY\"", "\"A", "A\"", "\"\"", "A\"\"B"};
        for (String raw : rawIdentifiers) {
            assertEquals(raw, processor.removeIdentifierQuote(processor.quoteIdentifierAlways(raw)),
                    "always-quote round trip must preserve the raw identifier");
        }
    }

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("O''Brien", OracleIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertNull(OracleIdentifierProcessor.INSTANCE.escapeString(null));
        assertEquals("plain", OracleIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertEquals("'C:\\tmp\\O''Brien'",
                OracleIdentifierProcessor.INSTANCE.quoteStringLiteral("C:\\tmp\\O'Brien"));
    }

    @Test
    void valueProcessorsDoNotRewriteOracleBackslashes() {
        SQLDataValue value = new SQLDataValue();
        value.setValue("C:\\tmp\\O'Brien");
        DataType type = new DataType();
        type.setDataTypeName("VARCHAR2");
        value.setDataType(type);

        assertEquals("'C:\\tmp\\O''Brien'", new OracleValueProcessor().convertSQLValueByType(value));

        type.setDataTypeName("CLOB");
        assertEquals("'C:\\tmp\\O''Brien'", new OracleValueProcessor().convertSQLValueByType(value));
    }

    @Test
    void escapeIdentifierDoublesEveryDoubleQuote() {
        assertEquals("WE\"\"IRD", OracleIdentifierProcessor.escapeIdentifier("WE\"IRD"));
        assertEquals("\"\"ALREADY\"\"", OracleIdentifierProcessor.escapeIdentifier("\"ALREADY\""));
        assertEquals("\"A\"\"B\"", OracleIdentifierProcessor.INSTANCE.quoteIdentifierAlways("A\"B"));
        assertNull(OracleIdentifierProcessor.escapeIdentifier(null));
    }

    @Test
    void reservedWordsAndCaseConversionAreLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(OracleIdentifierProcessor.INSTANCE.isReservedKeyword("insert", null, null));
            assertEquals("ID", OracleIdentifierProcessor.INSTANCE.convertIdentifierCase("id"));
            assertEquals("\"insert\"", OracleIdentifierProcessor.INSTANCE.quoteIdentifier("insert"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void getMetaDataNameNeutralizesEmbeddedQuotes() {
        OracleMetaData metaData = new OracleMetaData();
        String result = metaData.getMetaDataName("PUBLIC", "A\".\"B");
        assertEquals("\"PUBLIC\".\"A\"\".\"\"B\"", result);
        assertFalse(result.contains("A\".\"B\"."), "injection payload must not break out of the quoted identifier");
    }

    @Test
    void dropTableQuotesAndEscapesTableName() {
        String sql = new OracleDBManager().dropTable(null, null, null, "T\"; DROP TABLE U; --");
        assertEquals("DROP TABLE \"T\"\"; DROP TABLE U; --\"", sql);
    }

    @Test
    void dbManagerQualifiesRawAndServicePrequotedNames() throws Exception {
        OracleDBManager manager = new OracleDBManager();
        assertEquals("DROP TABLE \"SA\"\"LES\".\"ORDERS\"",
                manager.dropTable(null, "ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("TRUNCATE TABLE \"SA\"\"LES\".\"OR\"\"DERS\"",
                manager.truncateTable(null, "ignored_database", "SA\"LES", "\"OR\"\"DERS\""));
    }

    @Test
    void inheritedBuilderPathsUseOracleSchemaQualification() {
        OracleSqlBuilder builder = new OracleSqlBuilder();
        assertEquals("SELECT * FROM \"SA\"\"LES\".\"ORDERS\"",
                builder.buildSelectTable("ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("SELECT COUNT(1) FROM \"SA\"\"LES\".\"ORDERS\"",
                builder.buildSelectCount("ignored_database", "SA\"LES", "ORDERS"));
        assertEquals("DROP TABLE \"SA\"\"LES\".\"ORDERS\"",
                builder.buildDropTable(new DropTableRequest("ignored_database", "SA\"LES", "ORDERS")));
        assertEquals("TRUNCATE TABLE \"SA\"\"LES\".\"ORDERS\"",
                builder.buildTruncateTable(new TruncateTableRequest("ignored_database", "SA\"LES", "ORDERS")));
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
    void metadataQualifiedNamesAreLimitedToSchemaAndObject() {
        assertEquals("\"SALES\".\"ORDERS\"",
                new OracleMetaData().getMetaDataName("ignored_database", "SALES", "ORDERS"));
    }

    @Test
    void buildCreateViewEscapesNamesAndComment() {
        ModifyView view = new ModifyView();
        view.setSchemaName("SA\"LES");
        view.setViewName("V\"; DROP TABLE U; --");
        view.setViewBody("SELECT ID FROM EMPLOYEE");
        view.setComment("x'); DROP TABLE USERS; --");

        String sql = new OracleSqlBuilder().buildCreateView(view);

        assertTrue(sql.startsWith("CREATE VIEW \"SA\"\"LES\".\"V\"\"; DROP TABLE U; --\""));
        assertTrue(sql.endsWith("COMMENT ON TABLE \"SA\"\"LES\".\"V\"\"; DROP TABLE U; --\" is 'x''); DROP TABLE USERS; --';"));
    }

    @Test
    void buildCreateViewRejectsUnrecognizedRawClauses() {
        ModifyView view = new ModifyView();
        view.setSchemaName("SALES");
        view.setViewName("V");
        view.setViewBody("SELECT 1 FROM DUAL");
        view.setEditClause("EDITIONING; DROP TABLE U; --");

        assertThrows(IllegalArgumentException.class, () -> new OracleSqlBuilder().buildCreateView(view));

        view.setEditClause(null);
        view.setSubqueryRestrictionClause("CHECK OPTION");
        view.setSubqueryConstraintName("C\"; DROP TABLE U; --");
        String sql = new OracleSqlBuilder().buildCreateView(view);
        assertTrue(sql.contains("CHECK OPTION CONSTRAINT \"C\"\"; DROP TABLE U; --\""), sql);
    }

    @Test
    void buildCreateTableEscapesNamesAndComments() {
        Table table = new Table();
        table.setSchemaName("S\"CHEMA");
        table.setName("T\"ABLE");
        table.setComment("x'); DROP TABLE USERS; --");
        TableColumn column = new TableColumn();
        column.setName("C\"OL");
        column.setSchemaName("S\"CHEMA");
        column.setTableName("T\"ABLE");
        column.setColumnType("VARCHAR2");
        column.setColumnSize(20);
        column.setComment("c'); DROP TABLE U; --");
        table.setColumnList(List.of(column));
        table.setIndexList(List.of());

        String sql = new OracleSqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.startsWith("CREATE TABLE \"S\"\"CHEMA\".\"T\"\"ABLE\"("), sql);
        assertTrue(sql.contains("COMMENT ON COLUMN \"S\"\"CHEMA\".\"T\"\"ABLE\".\"C\"\"OL\" IS 'c''); DROP TABLE U; --'"), sql);
        assertTrue(sql.contains("COMMENT ON TABLE \"S\"\"CHEMA\".\"T\"\"ABLE\" IS 'x''); DROP TABLE USERS; --'"), sql);
    }

    @Test
    void buildModifyColumnDeleteEscapesQualifiedNames() {
        TableColumn column = new TableColumn();
        column.setEditStatus("DELETE");
        column.setSchemaName("S\"; DROP TABLE U; --");
        column.setTableName("T");
        column.setName("C");

        String sql = OracleColumnTypeEnum.VARCHAR2.buildModifyColumn(column);

        assertEquals("ALTER TABLE \"S\"\"; DROP TABLE U; --\".\"T\" DROP COLUMN \"C\"", sql);
    }

    @Test
    void buildModifyColumnPreservesUnknownTypesAndCaseOnlyRenames() {
        TableColumn oldColumn = new TableColumn();
        oldColumn.setNullable(1);

        TableColumn column = new TableColumn();
        column.setEditStatus("MODIFY");
        column.setSchemaName("S");
        column.setTableName("T");
        column.setOldName("MixedCase");
        column.setName("mixedcase");
        column.setColumnType("\"APP\".\"Order Type\"");
        column.setNullable(1);
        column.setOldColumn(oldColumn);

        String sql = OracleColumnTypeEnum.VARCHAR2.buildModifyColumn(column);

        assertTrue(sql.contains("MODIFY (\"mixedcase\" \"APP\".\"Order Type\" NULL)"), sql);
        assertTrue(sql.contains("RENAME COLUMN \"MixedCase\" TO \"mixedcase\""), sql);
    }

    @Test
    void buildIndexScriptEscapesNamesAndValidatesDirection() {
        TableIndex index = new TableIndex();
        index.setType(OracleIndexTypeEnum.NORMAL.getName());
        index.setSchemaName("S\"; X");
        index.setTableName("T");
        index.setName("I\"X");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C\"D");
        indexColumn.setAscOrDesc("desc");
        index.setColumnList(List.of(indexColumn));

        String sql = OracleIndexTypeEnum.NORMAL.buildIndexScript(index);

        assertEquals("CREATE INDEX \"S\"\"; X\".\"I\"\"X\" ON \"S\"\"; X\".\"T\" (\"C\"\"D\" DESC)", sql);
    }

    @Test
    void buildIndexColumnRejectsHostileSortDirection() {
        TableIndex index = new TableIndex();
        index.setType(OracleIndexTypeEnum.NORMAL.getName());
        index.setSchemaName("S");
        index.setTableName("T");
        index.setName("I");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C");
        indexColumn.setAscOrDesc("DESC; DROP TABLE U; --");
        index.setColumnList(List.of(indexColumn));

        assertThrows(IllegalArgumentException.class, () -> OracleIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void buildCreateColumnSqlAcceptsLegitimateDefaults() {
        String[] valid = {"SYSDATE", "CURRENT_TIMESTAMP", "USER", "SEQ.NEXTVAL", "-1", "1.5",
                "'Y'", "'0'", "'O''Brien'", "'1970-01-01'", "''", "SYS_GUID()",
                "TO_DATE('1970-01-01', 'YYYY-MM-DD')", "CAST('1' AS NUMBER(10,2))",
                "\"My Seq\".NEXTVAL", "TIMESTAMP '2020-01-01 00:00:00'",
                "INTERVAL '1' DAY", "q'[O'Brien]'", "'a'||'b'", "now()"};
        for (String defaultValue : valid) {
            TableColumn column = new TableColumn();
            column.setName("c1");
            column.setColumnType("VARCHAR2");
            column.setDefaultValue(defaultValue);
            assertTrue(OracleColumnTypeEnum.VARCHAR2.buildCreateColumnSql(column).contains("DEFAULT " + defaultValue),
                    "default should be accepted: " + defaultValue);
        }
    }

    @Test
    void buildCreateColumnSqlRejectsDefaultValuesThatReshapeDdl() {
        String[] payloads = {
                "0) --",
                "0 --",
                "1, x INT",
                "0); DROP TABLE x--",
                "'abc",
                "'a'--",
                "'a'; DROP TABLE x--",
                "0 NOT NULL",
                "0 CHECK (1=1)",
                "0 CONSTRAINT injected UNIQUE"
        };
        for (String payload : payloads) {
            TableColumn column = new TableColumn();
            column.setName("c1");
            column.setColumnType("VARCHAR2");
            column.setDefaultValue(payload);
            assertThrows(IllegalArgumentException.class,
                    () -> OracleColumnTypeEnum.VARCHAR2.buildCreateColumnSql(column),
                    "default should be rejected: " + payload);
        }
    }

    @Test
    void buildCreateColumnSqlAcceptsUnknownButSafeTypeNames() {
        String[] valid = {"MYCUSTOMTYPE", "VARCHAR2(20)", "NUMBER(10,2)", "TIMESTAMP(6) WITH TIME ZONE",
                "INTERVAL DAY(2) TO SECOND(6)", "VARCHAR2(20 CHAR)", "\"APP\".\"Order Type\"",
                "REF \"APP\".\"Object Type\""};
        for (String typeName : valid) {
            TableColumn column = new TableColumn();
            column.setName("c1");
            column.setColumnType(typeName);
            assertEquals("\"c1\" " + typeName, OracleColumnTypeEnum.VARCHAR2.buildCreateColumnSql(column),
                    "type should be accepted: " + typeName);
        }
    }

    @Test
    void buildCreateColumnSqlRejectsHostileTypeNames() {
        String[] payloads = {"INTEGER); DROP TABLE U; --", "INT, x INT", "INT'--", "INT\"--", "0) --",
                "INTEGER NOT NULL", "VARCHAR2(20) DEFAULT 0", "INTEGER CHECK(1=1)"};
        for (String payload : payloads) {
            TableColumn column = new TableColumn();
            column.setName("c1");
            column.setColumnType(payload);
            assertThrows(IllegalArgumentException.class,
                    () -> OracleColumnTypeEnum.VARCHAR2.buildCreateColumnSql(column),
                    "type should be rejected: " + payload);
        }
    }

    @Test
    void requireUnitAcceptsCharAndByteOnly() {
        assertEquals("CHAR", OracleSqlGuards.requireUnit("CHAR"));
        assertEquals("byte", OracleSqlGuards.requireUnit("byte"));
        assertThrows(IllegalArgumentException.class, () -> OracleSqlGuards.requireUnit("BYTE); DROP TABLE U; --"));
    }

    @Test
    void requireAscOrDescCanonicalizesAndRejects() {
        assertEquals("ASC", OracleSqlGuards.requireAscOrDesc("asc"));
        assertEquals("DESC", OracleSqlGuards.requireAscOrDesc(" DESC "));
        assertThrows(IllegalArgumentException.class, () -> OracleSqlGuards.requireAscOrDesc("DESC; DROP TABLE U; --"));
    }

    @Test
    void wrapDateEscapesSingleQuotesInLiteralPosition() {
        String sql = OracleDmlValueTemplate.wrapDate("2020-01-01' OR '1'='1");
        assertEquals("TO_DATE('2020-01-01'' OR ''1''=''1', 'SYYYY-MM-DD HH24:MI:SS')", sql);
        assertFalse(sql.contains("'2020-01-01' OR"));
    }

    @Test
    void rawProcessorNeutralizesNonHexAfterZeroXPrefix() {
        SQLDataValue dataValue = new SQLDataValue();
        dataValue.setValue("0x'; DROP TABLE U; --");

        String sql = new OracleRawValueProcessor().convertSQLValueByType(dataValue);

        String expectedHex = BaseEncoding.base16().encode("0x'; DROP TABLE U; --".getBytes());
        assertEquals("'" + expectedHex + "'", sql);
    }

    @Test
    void rawProcessorKeepsValidHexLiteral() {
        SQLDataValue dataValue = new SQLDataValue();
        dataValue.setValue("0x1A2b");

        String sql = new OracleRawValueProcessor().convertSQLValueByType(dataValue);

        assertEquals("'1A2b'", sql);
    }
}
