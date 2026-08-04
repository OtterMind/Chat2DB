package ai.chat2db.plugin.oscar;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.oscar.builder.OscarSqlBuilder;
import ai.chat2db.plugin.oscar.constant.OscarConstants;
import ai.chat2db.plugin.oscar.enums.type.OscarColumnTypeEnum;
import ai.chat2db.plugin.oscar.enums.type.OscarIndexTypeEnum;
import ai.chat2db.plugin.oscar.identifier.OscarIdentifierProcessor;
import ai.chat2db.plugin.oscar.util.OscarUtils;
import ai.chat2db.spi.model.request.DropTableRequest;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.model.request.TruncateTableRequest;
import ai.chat2db.spi.model.request.UpdateSqlRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OscarIdentifierProcessorTest {

    @Test
    void escapeStringDoublesSingleQuotes() {
        assertEquals("O''Brien", OscarIdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertEquals("plain", OscarIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(OscarIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void quoteIdentifierPassesThroughNullAndBlank() {
        assertNull(OscarIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", OscarIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals("  ", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("  "));
        assertNull(OscarIdentifierProcessor.INSTANCE.quoteIdentifier(null, null, null));
    }

    @Test
    void quoteIdentifierLeavesValidPlainIdentifiersUnquoted() {
        assertEquals("plain", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("T1", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("T1"));
        assertEquals("col_1", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("col_1"));
        assertEquals("T1", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("T1", null, null));
    }

    @Test
    void quoteIdentifierQuotesKeywordsAndInvalidIdentifiersWithDoubling() {
        assertEquals("\"SELECT\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
        assertEquals("\"select\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("select"));
        assertEquals("\"MyTable\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("\"MyTable\""));
        assertEquals("\"a\"\"b\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("\"a\"\"b\""));
        assertEquals("\"\"\"bad\"\"quote\"\"\"",
                OscarIdentifierProcessor.INSTANCE.quoteIdentifier("\"bad\"quote\""));
        assertEquals("\"a\"\"b\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifier("a\"b"));
        assertEquals("\"x\"\"; DROP TABLE t; --\"",
                OscarIdentifierProcessor.INSTANCE.quoteIdentifier("x\"; DROP TABLE t; --"));
    }

    @Test
    void reservedKeywordLookupIsCaseInsensitiveAndNullSafe() {
        assertTrue(OscarIdentifierProcessor.INSTANCE.isReservedKeyword("SELECT", null, null));
        assertTrue(OscarIdentifierProcessor.INSTANCE.isReservedKeyword("select", null, null));
        assertTrue(OscarIdentifierProcessor.INSTANCE.isReservedKeyword("SeLeCt", null, null));
        assertFalse(OscarIdentifierProcessor.INSTANCE.isReservedKeyword("plain_name", null, null));
        assertFalse(OscarIdentifierProcessor.INSTANCE.isReservedKeyword(null, null, null));
    }

    @Test
    void quoteIdentifierAlwaysQuotesUnconditionally() {
        assertNull(OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("\"\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertEquals("\" \"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways(" "));
        assertEquals("\"plain\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("\"SYSDBA\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways("SYSDBA"));
        assertEquals("\"\"\"MyTable\"\"\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"MyTable\""));
        assertEquals("\"a\"\"b\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways("a\"b"));
        assertEquals("\"x\"\"; DROP TABLE t; --\"",
                OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways("x\"; DROP TABLE t; --"));

        for (String raw : List.of("", " ", "\"abc\"", "A\"B", "\"")) {
            assertEquals(raw, OscarIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                    OscarIdentifierProcessor.INSTANCE.quoteIdentifierAlways(raw)), raw);
        }
    }

    @Test
    void quoteIdentifierIgnoreCaseAlwaysQuotes() {
        assertNull(OscarIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
        assertEquals("\"plain\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
        assertEquals("\"SYSDBA\"", OscarIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("SYSDBA"));
    }

    @Test
    void escapeIdentifierDoublesEveryRawQuote() {
        assertEquals("a\"\"b", OscarIdentifierProcessor.escapeIdentifier("a\"b"));
        assertEquals("\"\"MyTable\"\"", OscarIdentifierProcessor.escapeIdentifier("\"MyTable\""));
        assertEquals("", OscarIdentifierProcessor.escapeIdentifier(null));
    }

    @Test
    void identifierProcessorNeutralizesMaliciousNames() {
        assertEquals("\"a\"\"b\"", OscarUtils.quoteIdentifierIgnoreCase("a\"b"));
        assertEquals("\"x\"\"; DROP TABLE t; --\"",
                OscarUtils.quoteIdentifierIgnoreCase("x\"; DROP TABLE t; --"));
        assertEquals("\"SYSDBA\"", OscarUtils.quoteIdentifierIgnoreCase("SYSDBA"));
        assertEquals("\"\"\"MyTable\"\"\"", OscarUtils.quoteIdentifierIgnoreCase("\"MyTable\""));
        assertNull(OscarUtils.quoteIdentifierIgnoreCase(null));
        assertEquals("", OscarUtils.quoteIdentifierIgnoreCase(""));
    }

    @Test
    void metadataSqlTemplatesNeutralizeMaliciousLiterals() {
        String malicious = "X' OR '1'='1";
        String sql = String.format(OscarConstants.VIEW_DDL_SQL,
                OscarIdentifierProcessor.INSTANCE.escapeString(malicious),
                OscarIdentifierProcessor.INSTANCE.escapeString(malicious));
        assertTrue(sql.contains("OWNER = 'X'' OR ''1''=''1'"));
        assertTrue(sql.contains("VIEW_NAME = 'X'' OR ''1''=''1'"));

        String triggerSql = String.format(OscarConstants.TRIGGER_DETAIL_SQL,
                OscarIdentifierProcessor.INSTANCE.escapeString("SYSDBA"),
                OscarIdentifierProcessor.INSTANCE.escapeString(malicious));
        assertTrue(triggerSql.contains("TRIGGER_NAME = 'X'' OR ''1''=''1'"));
    }

    @Test
    void createColumnSqlNeutralizesMaliciousColumnName() {
        TableColumn column = new TableColumn();
        column.setName("col\"x");
        column.setColumnType("VARCHAR");
        column.setColumnSize(10);
        String sql = OscarColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);
        assertTrue(sql.startsWith("\"col\"\"x\" VARCHAR(10)"));
    }

    @Test
    void createIndexSqlNeutralizesMaliciousIndexName() {
        TableIndex index = new TableIndex();
        index.setName("idx\"; DROP TABLE t; --");
        index.setType(OscarIndexTypeEnum.NORMAL.getName());
        index.setTableName("T1");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C1");
        indexColumn.setAscOrDesc("ASC");
        index.setColumnList(List.of(indexColumn));
        String sql = OscarIndexTypeEnum.NORMAL.buildIndexScript(index);
        assertTrue(sql.contains("\"idx\"\"; DROP TABLE t; --\""));
        assertTrue(sql.contains("(\"C1\" ASC)"));
    }

    @Test
    void defaultValueWhitelistAcceptsLegitimateValues() {
        assertEquals("0", OscarSqlGuards.requireDefaultValueExpression("0"));
        assertEquals("-1", OscarSqlGuards.requireDefaultValueExpression("-1"));
        assertEquals("3.14", OscarSqlGuards.requireDefaultValueExpression("3.14"));
        assertEquals("1e10", OscarSqlGuards.requireDefaultValueExpression("1e10"));
        assertEquals("SYSDATE", OscarSqlGuards.requireDefaultValueExpression("SYSDATE"));
        assertEquals("CURRENT_TIMESTAMP", OscarSqlGuards.requireDefaultValueExpression("CURRENT_TIMESTAMP"));
        assertEquals("sys_guid()", OscarSqlGuards.requireDefaultValueExpression("sys_guid()"));
        assertEquals("to_date('2024-01-01', 'YYYY-MM-DD')",
                OscarSqlGuards.requireDefaultValueExpression("to_date('2024-01-01', 'YYYY-MM-DD')"));
    }

    @Test
    void defaultValueWhitelistAcceptsQuotedStringDefaults() {
        assertEquals("'abc'", OscarSqlGuards.requireDefaultValueExpression("'abc'"));
        assertEquals("'O''Brien'", OscarSqlGuards.requireDefaultValueExpression("'O''Brien'"));
        assertEquals("''", OscarSqlGuards.requireDefaultValueExpression("''"));
    }

    @Test
    void defaultValueWhitelistRejectsInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireDefaultValueExpression("'; DROP TABLE t; --"));
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireDefaultValueExpression("1; DROP TABLE t"));
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireDefaultValueExpression("a' OR '1'='1"));
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireDefaultValueExpression("f(''); DROP TABLE t; --('x')"));
    }

    @Test
    void createColumnSqlRejectsMaliciousDefaultValue() {
        TableColumn column = new TableColumn();
        column.setName("C1");
        column.setColumnType("VARCHAR");
        column.setColumnSize(10);
        column.setDefaultValue("'; DROP TABLE t; --");
        assertThrows(IllegalArgumentException.class,
                () -> OscarColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));
    }

    @Test
    void createColumnSqlKeepsQuotedStringDefault() {
        TableColumn column = new TableColumn();
        column.setName("C1");
        column.setColumnType("VARCHAR");
        column.setColumnSize(10);
        column.setDefaultValue("'O''Brien'");
        String sql = OscarColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);
        assertTrue(sql.contains("DEFAULT 'O''Brien'"));
    }

    @Test
    void lengthUnitWhitelistAcceptsByteAndChar() {
        assertEquals("BYTE", OscarSqlGuards.requireLengthUnit("BYTE"));
        assertEquals("char", OscarSqlGuards.requireLengthUnit("char"));
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireLengthUnit("BYTE; DROP TABLE t"));
    }

    @Test
    void sortOrderWhitelistAcceptsAscDesc() {
        assertEquals("ASC", OscarSqlGuards.requireSortOrder("ASC"));
        assertEquals("desc", OscarSqlGuards.requireSortOrder("desc"));
        assertThrows(IllegalArgumentException.class,
                () -> OscarSqlGuards.requireSortOrder("ASC; DROP TABLE t; --"));
    }

    @Test
    void createIndexSqlRejectsMaliciousSortOrder() {
        TableIndex index = new TableIndex();
        index.setName("IDX1");
        index.setType(OscarIndexTypeEnum.NORMAL.getName());
        index.setTableName("T1");
        TableIndexColumn indexColumn = new TableIndexColumn();
        indexColumn.setColumnName("C1");
        indexColumn.setAscOrDesc("ASC; DROP TABLE t; --");
        index.setColumnList(List.of(indexColumn));
        assertThrows(IllegalArgumentException.class,
                () -> OscarIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void unknownColumnTypesAreStructurallyValidated() {
        TableColumn custom = column("custom\"name", "\"Tenant\".CustomType(10,2)");
        custom.setDefaultValue("0");
        custom.setNullable(0);
        String sql = OscarColumnTypeEnum.VARCHAR.buildCreateColumnSql(custom);
        assertEquals("\"custom\"\"name\" \"Tenant\".CustomType(10,2) DEFAULT 0 NOT NULL", sql);

        assertEquals("types.CustomType(10,2)",
                OscarSqlGuards.requireColumnTypeExpression("  types.CustomType(10,2)  "));
        for (String value : List.of(
                "CustomType)", "CustomType(", "CustomType; DROP TABLE t",
                "CustomType DEFAULT 0", "CustomType/*comment*/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OscarSqlGuards.requireColumnTypeExpression(value), value);
        }
    }

    @Test
    void createTableUsesValidatedUnknownColumnType() {
        OscarSqlBuilder builder = new OscarSqlBuilder();
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
    void indexRequiresAtLeastOneNamedColumn() {
        TableIndex index = TableIndex.builder()
                .schemaName("App")
                .tableName("T1")
                .name("IDX1")
                .type(OscarIndexTypeEnum.NORMAL.getName())
                .columnList(List.of(TableIndexColumn.builder().columnName(" ").build()))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> OscarIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void metadataAndDdlBuildersAlwaysQuoteRawIdentifiers() {
        OscarMetaData metaData = new OscarMetaData();
        assertSame(OscarIdentifierProcessor.INSTANCE, metaData.getSQLIdentifierProcessor());
        assertEquals("\"App\".\"MixedTable\"", metaData.getMetaDataName("App", "MixedTable"));

        OscarSqlBuilder builder = new OscarSqlBuilder();
        assertEquals("DROP TABLE \"App\".\"MixedTable\"",
                builder.ddl().table().buildDropTable(new DropTableRequest(null, "App", "MixedTable")));
        assertEquals("TRUNCATE TABLE \"App\".\"MixedTable\"",
                builder.ddl().table().buildTruncateTable(new TruncateTableRequest(null, "App", "MixedTable")));
    }

    @Test
    void dmlBuildersEscapeTableAndColumnIdentifierBoundaries() {
        OscarSqlBuilder builder = new OscarSqlBuilder();
        SingleInsertSqlRequest insert = SingleInsertSqlRequest.builder()
                .schemaName("app\";DROP TABLE t;--")
                .tableName("tab\";DROP TABLE t;--")
                .columnList(List.of("col\"; DROP TABLE t; --"))
                .valueList(List.of("1"))
                .build();
        String insertSql = builder.dml().buildInsert(insert);
        assertTrue(insertSql.contains("INSERT INTO \"app\"\";DROP TABLE t;--\".\"tab\"\";DROP TABLE t;--\""), insertSql);
        assertTrue(insertSql.contains("(\"col\"\"; DROP TABLE t; --\")"), insertSql);
        assertFalse(insertSql.contains("INTO \"app\";"), insertSql);

        UpdateSqlRequest update = UpdateSqlRequest.builder()
                .schemaName("App")
                .tableName("MixedTable")
                .row(Map.of("set\"; DROP TABLE t; --", "1"))
                .primaryKeyMap(Map.of("pk\"; DROP TABLE t; --", "2"))
                .build();
        String updateSql = builder.dml().buildUpdate(update);
        assertEquals("UPDATE \"App\".\"MixedTable\" SET \"set\"\"; DROP TABLE t; --\" = 1"
                + " WHERE \"pk\"\"; DROP TABLE t; --\" = 2", updateSql);
    }

    private static TableColumn column(String name, String type) {
        return TableColumn.builder()
                .schemaName("App")
                .tableName("CustomTable")
                .name(name)
                .columnType(type)
                .nullable(1)
                .build();
    }
}
