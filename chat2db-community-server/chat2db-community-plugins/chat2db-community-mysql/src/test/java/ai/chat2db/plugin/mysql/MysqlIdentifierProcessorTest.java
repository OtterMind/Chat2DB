package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.community.domain.api.model.view.ModifyView;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.plugin.mysql.enums.MysqlViewAlgorithmOptionEnum;
import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import ai.chat2db.plugin.mysql.enums.type.MysqlIndexTypeEnum;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.plugin.mysql.value.template.MysqlDmlValueTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlIdentifierProcessorTest {

    @Test
    void escapeStringDoublesBackslashBeforeSingleQuote() {
        assertEquals("a''b\\\\c", MysqlIdentifierProcessor.INSTANCE.escapeString("a'b\\c"));
        assertEquals("''", MysqlIdentifierProcessor.INSTANCE.escapeString("'"));
        assertEquals("plain", MysqlIdentifierProcessor.INSTANCE.escapeString("plain"));
        assertNull(MysqlIdentifierProcessor.INSTANCE.escapeString(null));
    }

    @Test
    void quoteIdentifierIsConditionalForSpiConsumers() {
        // null/blank pass through untouched
        assertNull(MysqlIdentifierProcessor.INSTANCE.quoteIdentifier(null));
        assertEquals("", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier(""));
        assertEquals("  ", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("  "));
        // valid plain identifiers that are not reserved keywords stay unquoted
        assertEquals("plain", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("plain"));
        assertEquals("plain_name", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("plain_name"));
        // reserved keywords and non-plain identifiers are quoted with doubling
        assertEquals("`select`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("select"));
        assertEquals("`weird``name`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("weird`name"));
        assertEquals("`a``; DROP TABLE b; --`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("a`; DROP TABLE b; --"));
        // quoteIdentifier treats its argument as raw identifier content
        assertEquals("```a``b```", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("`a`b`"));
        assertEquals("```quoted```", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("`quoted`"));
        // versioned overload delegates to the same conditional behavior
        assertEquals("users", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("users", null, null));
        assertEquals("`a``b`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifier("a`b", null, null));
    }

    @Test
    void quoteIdentifierAlwaysWrapsUnconditionally() {
        assertNull(MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(null));
        assertEquals("``", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(""));
        assertEquals("`plain`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain"));
        assertEquals("`plain_name`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("plain_name"));
        assertEquals("`weird``name`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("weird`name"));
        assertEquals("`a``; DROP TABLE b; --`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("a`; DROP TABLE b; --"));
        assertEquals("```a``b```", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`a`b`"));
        assertEquals("```quoted```", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`quoted`"));
        assertEquals("`quoted`", MysqlIdentifierProcessor.INSTANCE.removeIdentifierQuote(
                MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways("`quoted`")));
    }

    @Test
    void quoteIdentifierIgnoreCaseKeepsConditionalSpiSemantics() {
        assertEquals("`a``b`", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("a`b"));
        assertEquals("plain", MysqlIdentifierProcessor.INSTANCE.quoteIdentifierIgnoreCase("plain"));
    }

    @Test
    void requireMysqlNameRejectsInjection() {
        assertEquals("utf8mb4_0900_ai_ci", MysqlSqlGuards.requireMysqlName("utf8mb4_0900_ai_ci", "collation"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.requireMysqlName("InnoDB, COMMENT='x'", "engine"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.requireMysqlName("utf8mb4;DROP TABLE t", "charset"));
    }

    @Test
    void requireNumericDefaultRejectsNonLiteral() {
        assertEquals("42", MysqlSqlGuards.requireNumericDefault("42"));
        assertEquals("-1.5", MysqlSqlGuards.requireNumericDefault("-1.5"));
        assertEquals("1e3", MysqlSqlGuards.requireNumericDefault("1e3"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.requireNumericDefault("0);DROP TABLE t"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.requireNumericDefault("(uuid())"));
    }

    @Test
    void requireBitLiteralRejectsNonBits() {
        assertEquals("0101", MysqlSqlGuards.requireBitLiteral("0101"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.requireBitLiteral("2"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.requireBitLiteral("1' OR '1'='1"));
    }

    @Test
    void requireDefinerAcceptsOnlyAccountSyntax() {
        assertEquals("root@localhost", MysqlSqlGuards.requireDefiner("root@localhost"));
        assertEquals("'root'@'%'", MysqlSqlGuards.requireDefiner("'root'@'%'"));
        assertEquals("`root`@`localhost`", MysqlSqlGuards.requireDefiner("`root`@`localhost`"));
        assertEquals("'ro''ot'@'%'", MysqlSqlGuards.requireDefiner("'ro''ot'@'%'"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.requireDefiner("'root\\\\name'@'localhost'"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.requireDefiner("root@localhost SQL SECURITY INVOKER"));
    }

    @Test
    void requireEnumConstantRejectsUnknownOption() {
        assertEquals("MERGE",
                MysqlSqlGuards.requireEnumConstant("merge", MysqlViewAlgorithmOptionEnum.values(), "algorithm"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.requireEnumConstant(
                "MERGE SQL SECURITY INVOKER", MysqlViewAlgorithmOptionEnum.values(), "algorithm"));
    }

    @Test
    void quoteEnumValuesDecodesBeforeReEscapingAndRejectsMalformedLists() {
        assertEquals("'draft','published'", MysqlSqlGuards.quoteEnumValues("'draft','published'"));
        assertEquals("('draft','published')", MysqlSqlGuards.quoteEnumValues("('draft','published')"));
        assertEquals("'can''t'", MysqlSqlGuards.quoteEnumValues("'can''t'"));
        assertEquals("'can''t'", MysqlSqlGuards.quoteEnumValues("'can\\'t'"));
        assertEquals("'a\\\\b'", MysqlSqlGuards.quoteEnumValues("'a\\\\b'"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.quoteEnumValues("'),DROP TABLE t;-- x"));
        assertThrows(IllegalArgumentException.class,
                () -> MysqlSqlGuards.quoteEnumValues("'a','b'); DROP TABLE t;-- '"));
    }

    @Test
    void parseEnumValuesPreservesComplexDeclaredValues() {
        assertEquals(List.of("needs,review", "can't", "a\\b", "(nested)", ""),
                MysqlSqlGuards.parseEnumValues("('needs,review','can\\'t','a\\\\b','(nested)','')"));
        assertEquals(List.of("can\'t"), MysqlSqlGuards.parseEnumValues("'can''t'"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.parseEnumValues("('a','b'"));
        assertThrows(IllegalArgumentException.class, () -> MysqlSqlGuards.parseEnumValues("'a',"));
    }

    @Test
    void createColumnSqlQuotesColumnNameAndEscapesComment() {
        TableColumn column = TableColumn.builder()
                .name("a`b")
                .columnType("VARCHAR")
                .columnSize(255)
                .comment("it's")
                .build();

        String sql = MysqlColumnTypeEnum.VARCHAR.buildCreateColumnSql(column);

        assertTrue(sql.contains("`a``b`"), sql);
        assertTrue(sql.contains("COMMENT 'it''s'"), sql);
    }

    @Test
    void createEnumColumnSqlRejectsMalformedValueList() {
        TableColumn column = TableColumn.builder()
                .name("e")
                .columnType("ENUM")
                .value("'),DROP TABLE t;-- x")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> MysqlColumnTypeEnum.ENUM.buildCreateColumnSql(column));
    }

    @Test
    void fallbackColumnQuotesNameEscapesCommentAndValidatesType() {
        TableColumn column = TableColumn.builder()
                .name("a`b")
                .columnType("CUSTOM_TYPE(10) UNSIGNED")
                .comment("it's")
                .build();
        assertEquals("`a``b` CUSTOM_TYPE(10) UNSIGNED COMMENT 'it''s'",
                MysqlColumnTypeEnum.VARCHAR.buildDefaultColumn(column, true));

        column.setColumnType("CUSTOM_TYPE); DROP TABLE t;--");
        assertThrows(IllegalArgumentException.class,
                () -> MysqlColumnTypeEnum.VARCHAR.buildDefaultColumn(column, true));
    }

    @Test
    void createColumnSqlRejectsRawDefaultInjection() {
        TableColumn column = TableColumn.builder()
                .name("n")
                .columnType("INT")
                .defaultValue("0);DROP TABLE t")
                .build();

        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void indexScriptQuotesIndexNameAndEscapesComment() {
        TableIndex tableIndex = TableIndex.builder()
                .name("i`x")
                .type("Normal")
                .method("BTREE")
                .comment("c'd")
                .columnList(List.of(TableIndexColumn.builder().columnName("col`1").build()))
                .build();

        String sql = MysqlIndexTypeEnum.NORMAL.buildIndexScript(tableIndex);

        assertTrue(sql.contains("`i``x`"), sql);
        assertTrue(sql.contains("(`col``1`)"), sql);
        assertTrue(sql.contains("COMMENT 'c''d'"), sql);
    }

    @Test
    void indexScriptCanonicalizesAscOrDescAndRejectsInjection() {
        TableIndex benign = TableIndex.builder()
                .name("i")
                .type("Normal")
                .method("BTREE")
                .columnList(List.of(TableIndexColumn.builder().columnName("c").ascOrDesc("desc").build()))
                .build();
        assertTrue(MysqlIndexTypeEnum.NORMAL.buildIndexScript(benign).contains("(`c` DESC)"),
                MysqlIndexTypeEnum.NORMAL.buildIndexScript(benign));

        TableIndex malicious = TableIndex.builder()
                .name("i")
                .type("Normal")
                .method("BTREE")
                .columnList(List.of(TableIndexColumn.builder().columnName("c")
                        .ascOrDesc("DESC, DROP TABLE t;--").build()))
                .build();
        assertThrows(IllegalArgumentException.class, () -> MysqlIndexTypeEnum.NORMAL.buildIndexScript(malicious));
    }

    @Test
    void createTableEscapesCommentAndRejectsEngineInjection() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        Table table = Table.builder()
                .name("t")
                .columnList(List.of())
                .indexList(List.of())
                .comment("x'; DROP TABLE u;--")
                .build();

        String sql = builder.ddl().table().buildCreateTable(table, TableBuilderConfig.defaultConfig());

        assertTrue(sql.contains("COMMENT='x''; DROP TABLE u;--'"), sql);

        Table evilEngine = Table.builder()
                .name("t")
                .columnList(List.of())
                .indexList(List.of())
                .engine("InnoDB COMMENT='x'")
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> builder.ddl().table().buildCreateTable(evilEngine, TableBuilderConfig.defaultConfig()));
    }

    @Test
    void createViewRejectsCheckOptionAndDefinerInjection() {
        MysqlSqlBuilder builder = new MysqlSqlBuilder();
        ModifyView modifyView = new ModifyView();
        modifyView.setViewName("v");
        modifyView.setViewBody("select 1");
        modifyView.setCheckOption("CASCADED; DROP TABLE t");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateView(modifyView));

        ModifyView definerView = new ModifyView();
        definerView.setViewName("v");
        definerView.setViewBody("select 1");
        definerView.setDefiner("root@localhost SQL SECURITY INVOKER");
        assertThrows(IllegalArgumentException.class, () -> builder.buildCreateView(definerView));
    }

    @Test
    void dropTableAndDropViewQuoteIdentifiers() {
        MysqlDBManager manager = new MysqlDBManager();
        assertEquals("DROP TABLE `a``;DROP TABLE b;--`",
                manager.dropTable(null, null, null, "a`;DROP TABLE b;--"));
        assertEquals("DROP VIEW `db`.`v`", MysqlDBManager.buildDropViewSql("db", "v"));
        assertEquals("DROP VIEW `v`", MysqlDBManager.buildDropViewSql(null, "v"));
    }

    @Test
    void dmlValueTemplatesEscapeOrValidate() {
        assertEquals("ST_GeomFromText('POINT(1 1)')", MysqlDmlValueTemplate.wrapGeometry("POINT(1 1)"));
        assertEquals("ST_GeomFromText('x''y')", MysqlDmlValueTemplate.wrapGeometry("x'y"));
        assertEquals("b'0101'", MysqlDmlValueTemplate.wrapBit("0101"));
        assertThrows(IllegalArgumentException.class, () -> MysqlDmlValueTemplate.wrapBit("1' OR '1'='1"));
        assertEquals("0x4d7953514c", MysqlDmlValueTemplate.wrapHex("4d7953514c"));
        assertThrows(IllegalArgumentException.class, () -> MysqlDmlValueTemplate.wrapHex("41, name=(SELECT user())-- "));
    }

    @Test
    void hexLiteralPassthroughRequiresWellFormedHex() {
        assertTrue(MysqlSqlGuards.isHexLiteral("0x4D7953514C"));
        assertTrue(!MysqlSqlGuards.isHexLiteral("0x41, name=(SELECT user())-- "));
        assertTrue(!MysqlSqlGuards.isHexLiteral("0x"));
    }

    @Test
    void identifierProcessorDdlPathsKeepAlwaysQuoteSemantics() {
        MysqlIdentifierProcessor processor = MysqlIdentifierProcessor.INSTANCE;
        // SPI-facing conditional quote leaves plain identifiers bare
        assertEquals("plain_name", processor.quoteIdentifier("plain_name"));
        // DDL-generation always quote keeps producing quoted output
        assertEquals("`a``b`", processor.quoteIdentifierAlways("a`b"));
        assertEquals("`plain_name`", processor.quoteIdentifierAlways("plain_name"));
    }

    @Test
    void truncateTableEscapesBacktickIdentifier() {
        MysqlDBManager manager = new MysqlDBManager();
        assertEquals("TRUNCATE TABLE `a``b`",
                manager.truncateTable(null, null, null, "a`b"));
        assertEquals("TRUNCATE TABLE `a``; DROP TABLE b; --`",
                manager.truncateTable(null, null, null, "a`; DROP TABLE b; --"));
    }

    @Test
    void copyTableSqlEscapesBothIdentifiers() {
        assertEquals("CREATE TABLE `n``t` AS SELECT * FROM `o``t`",
                MysqlDBManager.buildCopyTableSql("o`t", "n`t", true));
        assertEquals("CREATE TABLE `n``t` AS SELECT * FROM `o``t` WHERE 1=0",
                MysqlDBManager.buildCopyTableSql("o`t", "n`t", false));
        assertEquals("CREATE TABLE `c` AS SELECT * FROM `a``; DROP TABLE b; --`",
                MysqlDBManager.buildCopyTableSql("a`; DROP TABLE b; --", "c", true));
    }

    @Test
    void exportTitleValueCannotTerminateCommentLine() {
        assertEquals("orders DROP TABLE users;-- ",
                MysqlDBManager.formatExportTitleValue("orders\nDROP TABLE users;--\r"));
        assertNull(MysqlDBManager.formatExportTitleValue(null));
    }
}
