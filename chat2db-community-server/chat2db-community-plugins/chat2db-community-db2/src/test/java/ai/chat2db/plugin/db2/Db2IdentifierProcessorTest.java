package ai.chat2db.plugin.db2;

import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.plugin.db2.builder.DB2SqlBuilder;
import ai.chat2db.plugin.db2.enums.type.DB2ColumnTypeEnum;
import ai.chat2db.plugin.db2.enums.type.DB2IndexTypeEnum;
import ai.chat2db.plugin.db2.identifier.Db2IdentifierProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Db2IdentifierProcessorTest {

    @Test
    void escapeSqlLiteralDoublesSingleQuotes() {
        assertEquals("O''Brien", Db2IdentifierProcessor.INSTANCE.escapeString("O'Brien"));
        assertEquals("plain", Db2IdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(Db2IdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void escapeIdentifierDoublesEveryDoubleQuote() {
        assertEquals("we\"\"ird", Db2IdentifierProcessor.escapeIdentifier("we\"ird"));
        assertEquals("\"\"abc\"\"", Db2IdentifierProcessor.escapeIdentifier("\"abc\""));
        assertEquals("", Db2IdentifierProcessor.escapeIdentifier(null));
        assertEquals("\"a\"\"b\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("a\"b"));
    }

    @Test
    void quoteIdentifierPassesThroughNullAndBlank() {
        assertEquals(null, Db2IdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", Db2IdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals("  ", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("  "));
    }

    @Test
    void quoteIdentifierLeavesValidPlainIdentifierUnquoted() {
        assertEquals("\"plain\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("T1_COL", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("T1_COL"));
        assertEquals("\"plain\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("plain", 11, 5));
        assertEquals("\"SELECT\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("SELECT"));
    }

    @Test
    void quoteIdentifierQuotesNonPlainIdentifiers() {
        assertEquals("\"has space\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("has space"));
        assertEquals("\"1abc\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("1abc"));
        assertEquals("\"a\"\"b\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifier("a\"b"));
        assertEquals("\"\"\"already\"\" quoted\"\"\"",
                Db2IdentifierProcessor.INSTANCE.quoteIdentifier("\"already\" quoted\""));
    }

    @Test
    void quoteIdentifierAlwaysQuotesUnconditionally() {
        assertEquals(null, Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("\"plain\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("\"a\"\"b\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("a\"b"));
        assertEquals("\"\"\"abc\"\"\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"abc\""));
        assertEquals("\"abc\"", Db2IdentifierProcessor.INSTANCE.removeIdentifierQuote(
                Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("\"abc\"")));
    }

    @Test
    void quoteIdentifierIgnoreCasePreservesSafeNamesWithoutCaseFolding() {
        assertEquals(null, Db2IdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase(null));
        assertEquals("plain", Db2IdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
        assertEquals("\"SELECT\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("SELECT"));
        assertEquals("\"a\"\"b\"", Db2IdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("a\"b"));
    }

    @Test
    void createSchemaNeutralizesMaliciousNameAndComment() {
        Schema schema = new Schema();
        schema.setName("bad\"name");
        schema.setComment("x'; DROP TABLE t; --");

        String sql = new DB2SqlBuilder().buildCreateSchema(schema);

        assertEquals("CREATE SCHEMA \"bad\"\"name\";\nCOMMENT ON SCHEMA \"bad\"\"name\" IS 'x''; DROP TABLE t; --';", sql);
    }

    @Test
    void createTableNeutralizesMaliciousTableName() {
        Table table = new Table();
        table.setSchemaName("S");
        table.setName("evil\" , \"x");
        table.setColumnList(new ArrayList<>());
        table.setIndexList(new ArrayList<>());

        String sql = new DB2SqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.startsWith("CREATE TABLE \"S\".\"evil\"\" , \"\"x\" "), sql);
    }

    @Test
    void indexCommentNeutralizesMaliciousNameAndComment() {
        TableIndex index = new TableIndex();
        index.setName("ix\"name");
        index.setSchemaName("S");
        index.setTableName("T");
        index.setComment("'; DROP TABLE t; --");

        String sql = DB2IndexTypeEnum.NORMAL.buildIndexComment(index);

        assertEquals("COMMENT ON INDEX \"ix\"\"name\" IS '''; DROP TABLE t; --';", sql);
    }

    @Test
    void getMetaDataNameDoublesEmbeddedQuotes() {
        assertEquals("\"a\".\"b\"\"c\"", new DB2MetaData().getMetaDataName("a", "b\"c"));
    }

    @Test
    void createColumnEscapesNameAndKeepsPlainDefault() {
        TableColumn column = new TableColumn();
        column.setName("c\"x");
        column.setColumnType("INT");
        column.setDefaultValue("0");

        String sql = DB2ColumnTypeEnum.INT.buildCreateColumnSql(column);

        assertTrue(sql.startsWith("\"c\"\"x\" INT DEFAULT 0"), sql);
    }

    @Test
    void createColumnRejectsMaliciousDefaultValue() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT");
        column.setDefaultValue("0; DROP TABLE t");

        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void createColumnRejectsMaliciousUnit() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("VARCHAR");
        column.setColumnSize(10);
        column.setUnit("OCTETS) DROP TABLE t");

        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));
    }

    @Test
    void fallbackColumnContainsMaliciousNameInsideQuotedIdentifier() {
        TableColumn column = new TableColumn();
        column.setName("x INT); DROP TABLE t; --");
        column.setColumnType("VARCHAR(10)");

        String sql = DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertEquals("\"x INT); DROP TABLE t; --\" VARCHAR(10)", sql);
    }

    @Test
    void fallbackColumnEscapesEmbeddedQuoteInName() {
        TableColumn column = new TableColumn();
        column.setName("a\"b");
        column.setColumnType("VARCHAR(10)");

        String sql = DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertEquals("\"a\"\"b\" VARCHAR(10)", sql);
    }

    @Test
    void fallbackColumnRejectsMaliciousColumnType() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT); DROP TABLE t; --");

        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));

        column.setColumnType("VARCHAR(10); DROP TABLE t");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));

        column.setColumnType("VAR CHAR");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(column));
    }

    @Test
    void tableDDLRejectsQuoteBearingNames() {
        DB2MetaData metaData = new DB2MetaData();

        assertThrows(IllegalArgumentException.class, () -> metaData.tableDDL(null, null, "s\"x", "t"));
        assertThrows(IllegalArgumentException.class, () -> metaData.tableDDL(null, null, "s", "x\" -t \"y"));
    }

    @Test
    void setSchemaEscapesIdentifierInsideTemplateQuotes() {
        String sql = String.format(ai.chat2db.plugin.db2.constant.DB2DBManagerConstants.SQL_SET_SCHEMA,
                Db2IdentifierProcessor.escapeIdentifier("bad\"schema"));

        assertEquals("SET SCHEMA \"bad\"\"schema\"", sql);
    }

    @Test
    void dropTableQuotesIdentifier() {
        String sql = new DB2DBManager().dropTable(null, null, null, "t\"x");

        assertEquals("DROP TABLE \"t\"\"x\"", sql);
    }

    @Test
    void copyTableQuotesBothIdentifiers() {
        String sql = String.format(ai.chat2db.plugin.db2.constant.DB2DBManagerConstants.SQL_COPY_TABLE,
                Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("n\"t"), Db2IdentifierProcessor.INSTANCE.quoteIdentifierAlways("s\"t"));

        assertEquals("CREATE TABLE \"n\"\"t\" LIKE \"s\"\"t\" INCLUDING INDEXES", sql);
    }

    @Test
    void dropAndTruncateTableQuoteQualifiedIdentifiers() {
        ai.chat2db.spi.model.request.DropTableRequest drop = new ai.chat2db.spi.model.request.DropTableRequest();
        drop.setSchemaName("s\"x");
        drop.setTableName("t\"y");
        assertEquals("DROP TABLE \"s\"\"x\".\"t\"\"y\"", new DB2SqlBuilder().buildDropTable(drop));

        ai.chat2db.spi.model.request.TruncateTableRequest truncate = new ai.chat2db.spi.model.request.TruncateTableRequest();
        truncate.setSchemaName("s");
        truncate.setTableName("t\"z");
        assertEquals("TRUNCATE TABLE \"s\".\"t\"\"z\"", new DB2SqlBuilder().buildTruncateTable(truncate));

        assertEquals("TRUNCATE TABLE \"t\"\"z\"", new DB2DBManager().truncateTable(null, null, null, "t\"z"));
    }

    @Test
    void indexColumnRejectsMaliciousSortDirection() {
        TableIndex index = new TableIndex();
        index.setSchemaName("S");
        index.setTableName("T");
        index.setName("I");
        ai.chat2db.community.domain.api.model.metadata.TableIndexColumn column =
                new ai.chat2db.community.domain.api.model.metadata.TableIndexColumn();
        column.setColumnName("c");
        column.setAscOrDesc("DESC); DROP TABLE t; --");
        index.setColumnList(java.util.List.of(column));

        assertThrows(IllegalArgumentException.class, () -> DB2IndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void createTableIncludesSizedVarcharColumn() {
        Table table = new Table();
        table.setSchemaName("S");
        table.setName("T");
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("VARCHAR(10)");
        table.setColumnList(java.util.List.of(column));
        table.setIndexList(new ArrayList<>());

        String sql = new DB2SqlBuilder().buildCreateTable(table, null);

        assertTrue(sql.contains("\"c\" VARCHAR(10)"), sql);
    }

    @Test
    void createColumnRejectsParenBreakoutDefaultValue() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT");
        column.setDefaultValue("1), (evil INT DEFAULT (0");

        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void createColumnKeepsLegalDb2Defaults() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT");

        column.setDefaultValue("'O''Brien'");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column).contains("DEFAULT 'O''Brien'"));

        column.setDefaultValue("CURRENT TIMESTAMP");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column).contains("DEFAULT CURRENT TIMESTAMP"));

        column.setDefaultValue("DATE '2026-07-29'");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column).contains("DEFAULT DATE '2026-07-29'"));

        column.setDefaultValue("GENERATE_UNIQUE()");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column).contains("DEFAULT GENERATE_UNIQUE()"));

        column.setDefaultValue("NEXT VALUE FOR \"S\".\"SEQ\"");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column)
                .contains("DEFAULT NEXT VALUE FOR \"S\".\"SEQ\""));
    }

    @Test
    void createColumnRejectsQuoteAndCommaBreakoutDefaultValues() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT");

        column.setDefaultValue("0) --");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));

        column.setDefaultValue("1, x INT");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));

        column.setDefaultValue("x' OR '1'='1");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void validatorsTrimSafeDefaultsAndRejectNewlinesInStructuralTokens() {
        TableColumn column = new TableColumn();
        column.setName("c");
        column.setColumnType("INT");
        column.setDefaultValue("0\n");
        assertTrue(DB2ColumnTypeEnum.INT.buildCreateColumnSql(column).contains("DEFAULT 0"));

        TableColumn sized = new TableColumn();
        sized.setName("c");
        sized.setColumnType("VARCHAR(10)\n");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(sized));

        TableColumn withUnit = new TableColumn();
        withUnit.setName("c");
        withUnit.setColumnType("VARCHAR");
        withUnit.setColumnSize(10);
        withUnit.setUnit("OCTETS\n");
        assertThrows(IllegalArgumentException.class, () -> DB2ColumnTypeEnum.VARCHAR.buildCreateColumnSql(withUnit));

        TableIndex index = new TableIndex();
        index.setSchemaName("S");
        index.setTableName("T");
        index.setName("I");
        ai.chat2db.community.domain.api.model.metadata.TableIndexColumn indexColumn =
                new ai.chat2db.community.domain.api.model.metadata.TableIndexColumn();
        indexColumn.setColumnName("c");
        indexColumn.setAscOrDesc("ASC\n");
        index.setColumnList(java.util.List.of(indexColumn));
        assertThrows(IllegalArgumentException.class, () -> DB2IndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void getDdlTokenBindsTheCompleteOptionString() {
        assertEquals("call SYSPROC.DB2LK_GENERATE_DDL(?,?)",
                ai.chat2db.plugin.db2.constant.DB2MetaDataConstants.GET_DDL_TOKEN);
        String options = String.format(ai.chat2db.plugin.db2.constant.DB2MetaDataConstants.DB2LOOK_OPTIONS,
                "o'brien", "t'; DROP TABLE t; --");

        assertTrue(options.contains("-td \"o'brien\""), options);
        assertTrue(options.contains("-t \"t'; DROP TABLE t; --\""), options);
    }

    @Test
    void courseDoesNotResolveAsDb2ColumnType() {
        assertNull(DB2ColumnTypeEnum.getByType("COURSE"));
    }
}
