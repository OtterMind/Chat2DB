package ai.chat2db.plugin.mysql.completion;

import ai.chat2db.plugin.mysql.completion.analysis.MysqlSqlCompletionCursorAnalyzer;
import ai.chat2db.plugin.mysql.completion.c3.MysqlSqlCompletionConfig;
import ai.chat2db.plugin.mysql.completion.c3.MysqlSqlCompletionEngine;
import ai.chat2db.community.domain.api.model.completion.core.SqlCompletionCandidates;
import ai.chat2db.plugin.mysql.completion.dummy.MysqlSqlCompletionDummyBuilder;
import ai.chat2db.plugin.mysql.completion.locate.MysqlSqlCompletionStatementLocator;
import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionInputCleaner;
import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.community.domain.api.service.db.ISqlCompletionMetadataProvider;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionMetadataRequest;
import ai.chat2db.community.domain.api.model.completion.request.DbSqlCompletionRequest;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionInputCleanResponse;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionMetadataResponse;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionDummyTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionInsertTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionParameterModeTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionSnippetSlotTypeEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatusEnum;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatementWindowTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionActiveSnippetSlot;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCursorContext;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionDummySql;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionStatementWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MysqlSqlCompletionProviderTest {

    private final MysqlSqlCompletionProvider provider = new MysqlSqlCompletionProvider();
    private final MysqlSqlCompletionStatementLocator statementLocator = new MysqlSqlCompletionStatementLocator();
    private final MysqlSqlCompletionCursorAnalyzer cursorAnalyzer = new MysqlSqlCompletionCursorAnalyzer();
    private final MysqlSqlCompletionDummyBuilder dummyBuilder = new MysqlSqlCompletionDummyBuilder();
    private final CapturingMetadataProvider metadataProvider = new CapturingMetadataProvider();

    @Test
    void cleanNormalizesNullSqlAndClampsCursor() {
        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(null, 99);

        Assertions.assertEquals("", result.sourceSql());
        Assertions.assertEquals("", result.parseSql());
        Assertions.assertEquals(0, result.cursor());
    }

    @Test
    void cleanPreservesOffsetsWhenCursorIsPastEnd() {
        String sql = "select * from orders";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, 999);

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(sql.length(), result.cursor());
    }

    @Test
    void masksMybatisHashPlaceholderWithoutMovingCursor() {
        String sql = "select * from orders where tenant_id = #{tenantId,jdbcType=BIGINT} and na";
        int cursor = sql.length();

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, cursor);

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(cursor, result.cursor());
        assertMaskedExpression(result.parseSql(), sql.indexOf("#{tenantId"), "#{tenantId,jdbcType=BIGINT}".length());
    }

    @Test
    void masksDifferentTemplateExpressionStyles() {
        String sql = "select * from orders where status = ${status} and name = :name and tag = {{ tag }} and na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        assertMaskedExpression(result.parseSql(), sql.indexOf("${status}"), "${status}".length());
        assertMaskedExpression(result.parseSql(), sql.indexOf(":name"), ":name".length());
        assertMaskedExpression(result.parseSql(), sql.indexOf("{{ tag }}"), "{{ tag }}".length());
    }

    @Test
    void keepsTemplateSyntaxInsideStringsQuotedIdentifiersAndComments() {
        String sql = "select '#{tenantId}' as literal, `col:${x}` from orders -- ${comment}\n"
                + "where name = 'a:b' and na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertTrue(result.parseSql().contains("'#{tenantId}'"));
        Assertions.assertTrue(result.parseSql().contains("`col:${x}`"));
        Assertions.assertTrue(result.parseSql().contains("-- ${comment}"));
        Assertions.assertTrue(result.parseSql().contains("'a:b'"));
    }

    @Test
    void masksXmlTemplateTagsAndKeepsCdataSqlBody() {
        String sql = "<![CDATA[ select * from orders where status = #{status} ]]>";
        int cursor = sql.indexOf("} ]]") + 1;

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, cursor);

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(cursor, result.cursor());
        Assertions.assertTrue(result.parseSql().startsWith("         "));
        Assertions.assertTrue(result.parseSql().contains("select * from orders where status = 0         "));
        Assertions.assertTrue(result.parseSql().endsWith("   "));
    }

    @Test
    void masksXmlTemplateTagsWithAttributesAndKeepsSqlText() {
        String sql = "<script><where>select * from orders <if test=\"status != null\">"
                + "and status = #{status}</if></where></script> and na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertFalse(result.parseSql().contains("<script>"));
        Assertions.assertFalse(result.parseSql().contains("<if"));
        Assertions.assertFalse(result.parseSql().contains("</where>"));
        Assertions.assertTrue(result.parseSql().contains("select * from orders"));
        Assertions.assertTrue(result.parseSql().contains("and status = 0        "));
    }

    @Test
    void keepsMysqlComparisonOperatorsUntouched() {
        String sql = "select * from orders where amount <> 0 and price <= 10 and deleted <=> 0 and na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        Assertions.assertEquals(sql, result.parseSql());
    }

    @Test
    void masksLexerErrorsBeforeCursor() {
        String sql = "select * from ? orders where ? and na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(' ', result.parseSql().charAt(sql.indexOf('?')));
        Assertions.assertEquals('0', result.parseSql().charAt(sql.lastIndexOf('?')));
    }

    @Test
    void masksQuestionInValuesAsExpressionPlaceholder() {
        String sql = "insert into orders(id, amount) values (?, ?)";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertEquals('0', result.parseSql().charAt(sql.indexOf('?')));
        Assertions.assertEquals('0', result.parseSql().charAt(sql.lastIndexOf('?')));
    }

    @Test
    void masksQuestionInCreateTableColumnDeclarationAsObjectNoise() {
        String sql = "create table orders (? int, amount decimal)";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(' ', result.parseSql().charAt(sql.indexOf('?')));
    }

    @Test
    void keepsLexerErrorTokenAtCursor() {
        String sql = "select * from orders where ?";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertEquals('?', result.parseSql().charAt(sql.indexOf('?')));
    }

    @Test
    void masksLexerErrorBeforeCursorSoColumnContextCanStillBeMatched() {
        String sql = "select * from orders where ? na";
        int cursor = sql.length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertEmpty(result);
        Assertions.assertNull(metadataProvider.lastRequest);
    }

    @Test
    void cleanerKeepsPlainTextBeforeSqlStatementForLocator() {
        String sql = "aaaascasca hello text select * from orders where na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        assertLengthPreserved(sql, result);
        Assertions.assertEquals(sql, result.parseSql());
    }

    @Test
    void locatorSelectsSqlAfterChinesePlainText() {
        String sql = "aaaascasca 你哈 实打实打你i  select * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.CURRENT_STATEMENT.name(), window.type());
        Assertions.assertEquals(sql.indexOf("select"), window.sourceStartOffset());
        Assertions.assertEquals("select * from orders where na", window.sourceSql());
        Assertions.assertEquals(window.sourceSql().length(), window.cursor());
    }

    @Test
    void locatorSelectsSqlAfterLogPrefix() {
        String sql = "2026-06-08 18:50:01.123 INFO sql=select * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(sql.indexOf("select"), window.sourceStartOffset());
        Assertions.assertEquals("select * from orders where na", window.sourceSql());
    }

    @Test
    void completesColumnWhenSqlIsPrefixedByPlainText() {
        String sql = "aaaascasca 你哈 实打实打你i  select * from orders where na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("na"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnWhenSqlIsPrefixedByLogText() {
        String sql = "[INFO] 2026-06-08 jdbc sql => select * from orders where na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("na"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void locatorSelectsCurrentStatementSegment() {
        String sql = "select * from old_orders; random trace hello select * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(sql.lastIndexOf("select"), window.sourceStartOffset());
        Assertions.assertEquals("select * from orders where na", window.sourceSql());
    }

    @Test
    void locatorKeepsCursorBeforeSeparatorInPreviousStatement() {
        String sql = "select * from orders where na; select * from users";
        int cursor = sql.indexOf(";");

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals("select * from orders where na", window.sourceSql());
        Assertions.assertEquals(cursor, window.cursor());
    }

    @Test
    void locatorReturnsEmptyWindowImmediatelyAfterSeparator() {
        String sql = "select * from orders; select * from users";
        int cursor = sql.indexOf(";") + 1;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name(), window.type());
        Assertions.assertEquals(cursor, window.sourceStartOffset());
        Assertions.assertEquals("", window.sourceSql());
    }

    @Test
    void locatorSelectsNextStatementWhenCursorIsInsideNextStatement() {
        String sql = "select * from orders; select * from users where na";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(sql.lastIndexOf("select"), window.sourceStartOffset());
        Assertions.assertEquals("select * from users where na", window.sourceSql());
    }

    @Test
    void locatorSelectsNextStatementWithoutSemicolonWhenCursorIsInsideNextStatement() {
        String sql = "select * from orders where status = 'PAID'\nselect * from users where na";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(sql.lastIndexOf("select"), window.sourceStartOffset());
        Assertions.assertEquals("select * from users where na", window.sourceSql());
    }

    @Test
    void locatorKeepsWithClauseAndMainSelectInSameWindowAcrossNewlines() {
        String sql = """
                WITH paid_orders as(
                SELECT * FROM orders
                WHERE status='paid'
                )
                SELECT * from pa""";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void locatorSelectsUpdateStatementWithoutSemicolonAfterPreviousSelect() {
        String sql = "select * from orders where status = 'PAID'\nupdate users set name = na";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(sql.indexOf("update"), window.sourceStartOffset());
        Assertions.assertEquals("update users set name = na", window.sourceSql());
    }

    @Test
    void locatorDoesNotSplitNestedSelectWithoutStatementSeparator() {
        String sql = "select * from orders where exists (select 1 from users where na)";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void ignoresPlainTextAfterCursor() {
        String sql = "select * from orders where na hello你是啊就埃及i是";
        int cursor = sql.indexOf(" hello");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesEmbeddedSqlWhenPlainTextExistsBeforeAndAfterCursor() {
        String sql = "aaaascasca 你哈 实打实打你i  select * from order  hello你是啊就埃及i是";
        int cursor = sql.indexOf("  hello");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("order"), cursor);
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
        Assertions.assertEquals("order", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW)
                .get(0).prefix());
    }

    @Test
    void keepsReplacementOffsetsWithEmojiChineseTabsAndCrlfPrefix() {
        String sql = "trace 🚀 中文\tline\r\nselect * from orders where na";
        int cursor = sql.length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesPrefixWhenCursorIsInTheMiddleOfIdentifier() {
        String sql = "select * from orders where name";
        int cursor = sql.indexOf("name") + 2;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("name"), sql.indexOf("name") + "name".length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void ignoresLogTextAfterCursor() {
        String sql = "select * from orders where na -- rows fetched in 12ms";
        int cursor = sql.indexOf(" --");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void keepsCursorTokenWhenLexerCannotRecognizeItSoProviderDoesNotGuess() {
        String sql = "select * from orders where ?";
        int cursor = sql.length();

        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(SqlCompletionStatusEnum.EMPTY.name(), result.getStatus());
        Assertions.assertNull(metadataProvider.lastRequest);
    }

    @Test
    void stripsMysqlVerticalResultSuffixWithoutMovingCursor() {
        String sql = "select * from orders where na\\G";
        int cursor = sql.indexOf("\\G");

        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals(cursor - 2, result.getReplaceStart());
        Assertions.assertEquals(cursor, result.getReplaceEnd());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void doesNotStripMysqlVerticalResultMarkerInTheMiddle() {
        String sql = "select '\\G' from orders where na";

        SqlCompletionInputCleanResponse result = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());

        Assertions.assertEquals(sql, result.parseSql());
    }

    @Test
    void completesTableNameAfterFrom() {
        String sql = "select * from ord";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("ord"), sql.length());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
        DbSqlCompletionMetadataRequest tableRequest = metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW)
                .get(0);
        Assertions.assertNull(tableRequest.scope().table());
        Assertions.assertEquals("ord", tableRequest.prefix());
        Assertions.assertEquals("order_items", result.getCandidates().get(0).getLabel());
    }

    @Test
    void completesTableNameAfterJoin() {
        String sql = "select * from orders join order_i";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("order_i"), sql.length());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
        Assertions.assertEquals("order_i", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW)
                .get(0).prefix());
    }

    @Test
    void completesColumnAfterWhere() {
        String sql = "select * from orders where stat";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("stat"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("stat", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnAfterJoinOn() {
        String sql = "select * from users u join orders o on na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("na"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnAfterGroupBy() {
        String sql = "select * from orders group by stat";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("stat"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("stat", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnAfterOrderBy() {
        String sql = "select * from orders order by stat";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("stat"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("stat", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnAfterUpdateSet() {
        String sql = "update orders set stat";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("stat"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("stat", metadataProvider.lastRequest.prefix());
    }

    @Test
    void updateTableAliasPrefixSuggestsSetKeywordWithoutDataTypes() {
        String sql = "update orders a s";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("s"), sql.length());
        candidate(result, SqlCompletionCandidateTypeEnum.KEYWORD, "SET");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TYPE);
    }

    @Test
    void completesColumnInsideIfFunctionFirstArgument() {
        String sql = "select if(na, 1, 0) from orders";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.PARAMETER.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completesColumnInsideIfFunctionAfterCommaArgument() {
        String sql = "select if(status = 'DONE', na, 0) from orders";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.PARAMETER.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
    }

    @Test
    void rejectsPureTemplateNoiseWithoutSqlAnchor() {
        String sql = "<where><if test=\"tenantId != null\">tenant_id = #{tenantId}</if></where> and na";

        SqlCompletionResponse result = complete(sql, sql.length());

        Assertions.assertEquals(SqlCompletionStatusEnum.EMPTY.name(), result.getStatus());
        Assertions.assertEquals(0, metadataProvider.callCount);
    }

    @Test
    void completesColumnsAfterTableDot() {
        String sql = "select orders.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void completesColumnsAfterQualifiedTableDotWithSchemaScope() {
        String sql = "select shop.orders.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertNull(metadataProvider.lastRequest);
    }

    @Test
    void qualifiedTableDotColumnsUseExplicitSchemaInsteadOfCurrentDatabase() {
        metadataProvider.defaultDatabaseName = "db2";
        metadataProvider.schemaSensitiveMetadata = true;
        String sql = "select db1.orders.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertNull(metadataProvider.lastRequest);
    }

    @Test
    void completesColumnsAfterQualifiedTableDotPrefixWithSchemaScope() {
        String sql = "select shop.orders.st";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertNull(metadataProvider.lastRequest);
    }

    @Test
    void completesColumnsAfterAliasDotUsingResolvedTableScope() {
        String sql = "select * from users u where u.na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("na"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("users", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "name");
        Assertions.assertEquals("users", candidate.getTableName());
        Assertions.assertEquals("u", candidate.getTableAlias());
    }

    @Test
    void completesRelationAliasBeforeDotInWherePredicate() {
        String sql = """
                select
                  *
                from
                  access_control_apply_record asdasdasda  where asd;
                """;
        SqlCompletionResponse result = complete(sql, sql.indexOf("asd;") + "asd".length());

        assertSuccessReplacement(result, sql.indexOf("asd;"), sql.indexOf("asd;") + "asd".length());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.ALIAS, "asdasdasda");
        Assertions.assertEquals("asdasdasda.", candidate.getInsertText());
        Assertions.assertEquals("access_control_apply_record", candidate.getTableName());
        Assertions.assertEquals("asdasdasda", candidate.getTableAlias());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void completesRelationAliasAndColumnsAtBlankWherePredicate() {
        String sql = """
                select
                  *
                from
                  access_control_apply_record asdasdasda  where ;
                """;
        int cursor = sql.indexOf(";");
        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        SqlCompletionCandidate alias = candidate(result, SqlCompletionCandidateTypeEnum.ALIAS, "asdasdasda");
        Assertions.assertEquals("asdasdasda.", alias.getInsertText());
        Assertions.assertEquals("access_control_apply_record", alias.getTableName());
        Assertions.assertEquals("asdasdasda", alias.getTableAlias());
        SqlCompletionCandidate column = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "_candidate");
        Assertions.assertEquals("access_control_apply_record", column.getTableName());
        Assertions.assertEquals("asdasdasda", column.getTableAlias());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
    }

    @Test
    void completesOnlyColumnsAfterRelationAliasDot() {
        String sql = """
                select
                  *
                from
                  access_control_apply_record asdasdasda  where asdasdasda.;
                """;
        int cursor = sql.indexOf(";");
        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.ALIAS);
        SqlCompletionCandidate column = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "_candidate");
        Assertions.assertEquals("access_control_apply_record", column.getTableName());
        Assertions.assertEquals("asdasdasda", column.getTableAlias());
    }

    @Test
    void completesForwardFromAliasColumnsInSelectListAfterDot() {
        String sql = """
                select
                  d.
                from
                    dsadasd d where id;
                """;
        int cursor = sql.indexOf("d.") + "d.".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        SqlCompletionCandidate column = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "_candidate");
        Assertions.assertEquals("dsadasd", column.getTableName());
        Assertions.assertEquals("d", column.getTableAlias());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("dsadasd", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
    }

    @Test
    void completeAliasDotUsesQualifiedDraftCreateTableColumnsFromSameEditor() {
        String sql = """
                create table enterprise_gateway_dev.dsadasd(
                    id INT primary key auto_increment,
                    id2 DATE default current_timestamp
                );

                select
                 d.id
                from
                    dsadasd d  where d.;

                insert into dsadasd (id,id2)
                values (1,2);

                with gfdgdfgdfsg as (
                    select
                        *
                    from
                        access_control_apply_record
                )
                select
                    *
                from
                    gfdgdfgdfsg;
                """;
        int cursor = sql.indexOf("where d.") + "where d.".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        SqlCompletionCandidate column = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "id2");
        Assertions.assertEquals("dsadasd", column.getTableName());
        Assertions.assertEquals("d", column.getTableAlias());
        Assertions.assertEquals("id2", column.getColumnName());
        assertNoMetadataRequest(SqlCompletionCandidateTypeEnum.COLUMN, "dsadasd");
    }

    @Test
    void qualifiedTableAliasKeepsDatabaseScopeForColumnLookup() {
        String sql = "select * from archive.orders o where o.st";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("st"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("archive", metadataProvider.lastRequest.scope().schema());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("st", metadataProvider.lastRequest.prefix());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "status");
        Assertions.assertEquals("orders", candidate.getTableName());
        Assertions.assertEquals("o", candidate.getTableAlias());
    }

    @Test
    void tableIndexHintIsNotTreatedAsAlias() {
        String sql = "select * from orders force index for join (idx_orders_status) where st";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("st"), sql.length());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "status");
        Assertions.assertEquals("orders", candidate.getTableName());
        Assertions.assertNull(candidate.getTableAlias());
        Assertions.assertEquals("status", candidate.getInsertText());
    }

    @Test
    void completesUnqualifiedColumnsFromVisibleRelationsByPrefix() {
        String sql = "select * from users u join orders o on i";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf('i'), sql.length());
        Assertions.assertEquals(2, metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.COLUMN).size());
        SqlCompletionCandidate userColumn = result.getCandidates().stream()
                .filter(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.COLUMN)
                .filter(candidate -> "id".equals(candidate.getLabel()))
                .filter(candidate -> "users".equals(candidate.getTableName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected users id candidate"));
        SqlCompletionCandidate orderColumn = result.getCandidates().stream()
                .filter(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.COLUMN)
                .filter(candidate -> "id".equals(candidate.getLabel()))
                .filter(candidate -> "orders".equals(candidate.getTableName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected orders id candidate"));
        Assertions.assertEquals("users", userColumn.getTableName());
        Assertions.assertEquals("u", userColumn.getTableAlias());
        Assertions.assertEquals("orders", orderColumn.getTableName());
        Assertions.assertEquals("o", orderColumn.getTableAlias());
    }

    @Test
    void completesColumnsWithTableNameWhenNoAliasExists() {
        String sql = "select * from users where na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("na"), sql.length());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "name");
        Assertions.assertEquals("users", candidate.getTableName());
        Assertions.assertNull(candidate.getTableAlias());
    }

    @Test
    void keepsSelfJoinColumnsSeparatedByAliasWithPrefix() {
        String sql = "select * from users u1 join users u2 on na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("na"), sql.length());
        List<SqlCompletionCandidate> nameCandidates = result.getCandidates().stream()
                .filter(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.COLUMN)
                .filter(candidate -> "name".equals(candidate.getLabel()))
                .toList();
        Assertions.assertEquals(2, nameCandidates.size());
        Assertions.assertTrue(nameCandidates.stream().anyMatch(candidate -> "u1".equals(candidate.getTableAlias())));
        Assertions.assertTrue(nameCandidates.stream().anyMatch(candidate -> "u2".equals(candidate.getTableAlias())));
    }

    @Test
    void projectionBeforeFromOnlyScansCurrentSelectRange() {
        String sql = "with x as (select st from orders) select * from users";
        int cursor = sql.indexOf("st") + 2;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("st"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.PARAMETER.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        assertNoMetadataRequest(SqlCompletionCandidateTypeEnum.COLUMN, "users");
    }

    @Test
    void c3CollectsSyntaxCandidatesAfterDummySqlIsBuilt() {
        String sql = "select * from users where ";
        SqlCompletionInputCleanResponse input = MysqlSqlCompletionInputCleaner.clean(sql, sql.length());
        SqlCompletionStatementWindow window = statementLocator.locate(input);
        SqlCompletionCursorContext cursorContext = cursorAnalyzer.analyze(window);
        SqlCompletionDummySql dummySql = dummyBuilder.build(window, cursorContext);

        SqlCompletionCandidates result = MysqlSqlCompletionEngine.collect(new MysqlSqlCompletionCandidateContext(
                DbSqlCompletionRequest.of(sql, sql.length(), "MYSQL", 1, metadataProvider),
                input, window, dummySql, cursorContext));

        Assertions.assertTrue(result.available());
        Assertions.assertFalse(result.empty());
    }

    @Test
    void c3ConfigIgnoresStructureTokens() {
        Set.of(
                MySqlLexer.DOT,
                MySqlLexer.COMMA,
                MySqlLexer.SEMI,
                MySqlLexer.STAR,
                MySqlLexer.DIVIDE,
                MySqlLexer.MODULE,
                MySqlLexer.PLUS,
                MySqlLexer.MINUS,
                MySqlLexer.LR_BRACKET,
                MySqlLexer.RR_BRACKET,
                MySqlLexer.EQUAL_SYMBOL,
                MySqlLexer.GREATER_SYMBOL,
                MySqlLexer.LESS_SYMBOL,
                MySqlLexer.EXCLAMATION_SYMBOL,
                MySqlLexer.BIT_NOT_OP,
                MySqlLexer.BIT_OR_OP,
                MySqlLexer.BIT_AND_OP,
                MySqlLexer.BIT_XOR_OP)
                .forEach(token -> Assertions.assertTrue(MysqlSqlCompletionConfig.ignoredTokens().contains(token)));
    }

    @Test
    void emptyPrefixExpressionUsesNormalFlow() {
        String sql = "select * from users where ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void emptyPrefixExpressionStillDoesNotExposeC3ParenthesesCandidate() {
        String sql = "select * from users where ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertFalse(result.getCandidates().stream()
                .anyMatch(candidate -> "()".equals(candidate.getLabel())
                        && "()".equals(candidate.getInsertText())));
    }

    @Test
    void statementStartPrefixDoesNotExposeBuiltinFunctionsWithoutFunctionRule() {
        String sql = "s";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, 0, sql.length());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
    }

    @Test
    void completesBuiltinFunctionByPrefixInExpression() {
        String sql = "select cou";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("cou"), sql.length());
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.FUNCTION
                        && "COUNT".equals(candidate.getLabel())
                        && "COUNT(${1:DISTINCT | ALL expr})$0".equals(candidate.getInsertText())
                        && SqlCompletionInsertTypeEnum.SNIPPET.equals(candidate.getInsertType())
                        && "([{DISTINCT | ALL}] expr:any*)".equals(candidate.getDetail())
                        && "int".equals(candidate.getDescription())
                        && "int".equals(candidate.getDataType())));
    }

    @Test
    void completesBuiltinFunctionAfterWhere() {
        String sql = "select * from orders where date_f";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("date_f"), sql.length());
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.FUNCTION
                        && "DATE_FORMAT".equals(candidate.getLabel())
                        && "DATE_FORMAT(${1:date}, ${2:format})$0".equals(candidate.getInsertText())
                        && SqlCompletionInsertTypeEnum.SNIPPET.equals(candidate.getInsertType())
                        && "(date:date, format:string)".equals(candidate.getDetail())
                        && "string".equals(candidate.getDescription())
                        && "string".equals(candidate.getDataType())));
    }

    @Test
    void completesBuiltinFunctionWithExpandedSignatureMetadata() {
        String sql = "select bit_c";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("bit_c"), sql.length());
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.FUNCTION
                        && "BIT_COUNT".equals(candidate.getLabel())
                        && "BIT_COUNT(${1:n})$0".equals(candidate.getInsertText())
                        && SqlCompletionInsertTypeEnum.SNIPPET.equals(candidate.getInsertType())
                        && "(n:bigint)".equals(candidate.getDetail())
                        && "int".equals(candidate.getDescription())
                        && "int".equals(candidate.getDataType())));
    }

    @Test
    void builtinFunctionCatalogCandidateSuppressesDuplicateSyntaxFunctionToken() {
        String sql = "select max";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("max"), sql.length());
        List<SqlCompletionCandidate> maxCandidates = result.getCandidates().stream()
                .filter(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.FUNCTION)
                .filter(candidate -> "MAX".equals(candidate.getLabel()))
                .toList();
        Assertions.assertEquals(1, maxCandidates.size());
        Assertions.assertEquals("MAX(${1:expr})$0", maxCandidates.get(0).getInsertText());
        Assertions.assertEquals("(expr:any)", maxCandidates.get(0).getDetail());
    }

    @Test
    void completesAdditionalBuiltinFunctionSignatureFamilies() {
        assertFunctionCandidate("select json_schema_v", "JSON_SCHEMA_VALID",
                "(schema:json, json_doc:json)", "int");
        assertFunctionCandidate("select regexp_rep", "REGEXP_REPLACE",
                "(expr:string, pat:string, repl:string[, pos:int[, occurrence:int[, match_type:string]]])", "string");
        assertFunctionCandidate("select st_x", "ST_X", "(point:point)", "number");
        assertFunctionCandidate("select ca", "CAST", "(expr:any AS type:data_type)", "any");
    }

    @Test
    void tableReferencePrefixDoesNotIncludeBuiltinFunctionsWithoutFunctionRule() {
        String sql = "select * from count";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("count"), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).isEmpty());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
    }

    @Test
    void emptyPrefixTableReferenceCompletesTables() {
        String sql = "select * from ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
        Assertions.assertEquals("", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW)
                .get(0).prefix());
        candidate(result, SqlCompletionCandidateTypeEnum.TABLE, "orders");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void tableReferenceBeforeSemicolonCompletesTables() {
        String sql = "select * from ;";
        int cursor = sql.indexOf(';');

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
        Assertions.assertEquals("", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW)
                .get(0).prefix());
        candidate(result, SqlCompletionCandidateTypeEnum.TABLE, "orders");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void createTableDeclarationEmptyPrefixDoesNotExposeStandaloneDatabaseQualifiers() {
        String sql = "create table ";

        SqlCompletionResponse result = complete(sql, sql.length());

        Assertions.assertEquals(SqlCompletionStatusEnum.EMPTY.name(), result.getStatus());
        Assertions.assertTrue(metadataProvider.requests.isEmpty());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE_VIEW);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
    }

    @Test
    void createTableDeclarationPrefixCompletesDatabaseQualifierButNotExistingTables() {
        String sql = "create table ap";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("ap"), sql.length());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.DATABASE.name()),
                metadataProvider.requestTypes());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE_VIEW);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
    }

    @Test
    void createTableLikeQueriesExistingTables() {
        String sql = "create table new_orders like ord";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf("ord"), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).isEmpty());
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> "orders".equals(candidate.getLabel())));
    }

    @Test
    void candidatePostProcessorDeduplicatesAndSortsColumns() {
        metadataProvider.duplicateColumns = true;
        String sql = "select * from orders where a";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf('a'), sql.length());
        Assertions.assertEquals(List.of("amount"),
                result.getCandidates().stream()
                        .filter(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.COLUMN)
                        .map(SqlCompletionCandidate::getLabel)
                        .toList());
    }

    @Test
    void locatorIgnoresSemicolonInsideStringLiteral() {
        String sql = "select ';' as semi, * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorIgnoresSemicolonInsideBlockComment() {
        String sql = "select * from orders /* keep ; here */ where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorIgnoresSemicolonInsideMysqlExecutableComment() {
        String sql = "select /*!40101 SQL_NO_CACHE ; */ * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorIgnoresSemicolonInsideOptimizerHint() {
        String sql = "select /*+ MAX_EXECUTION_TIME(1000); */ * from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorIgnoresSemicolonInsideLineCommentBeforeCursor() {
        String sql = "select * from orders -- keep ; here\nwhere na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorIgnoresSemicolonInsideQuotedIdentifier() {
        String sql = "select `semi;column` from orders where na";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
    }

    @Test
    void locatorReturnsEmptyStatementAfterSeparatorWhitespace() {
        String sql = "select * from orders;   ";

        SqlCompletionStatementWindow window = locate(sql, sql.length());

        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name(), window.type());
        Assertions.assertEquals(sql.length() - 3, window.sourceStartOffset());
        Assertions.assertEquals("", window.sourceSql().trim());
    }

    @Test
    void dummyInsertsIdentifierAfterDanglingDotOnlyInScratchSql() {
        String sql = "select orders.";
        SqlCompletionStatementWindow window = locate(sql, sql.length());
        SqlCompletionCursorContext context = cursorAnalyzer.analyze(window);

        SqlCompletionDummySql dummySql = dummyBuilder.build(window, context);

        Assertions.assertEquals(SqlCompletionDummyTypeEnum.DANGLING_DOT_IDENTIFIER.name(), dummySql.type());
        Assertions.assertTrue(dummySql.sql().startsWith(sql));
        Assertions.assertTrue(dummySql.sql().length() > window.parseSql().length());
        Assertions.assertEquals(sql.length(), context.replaceStart());
        Assertions.assertEquals(sql.length(), context.replaceEnd());
    }

    @Test
    void completesColumnsAfterDotWithPrefixWithoutDummyChangingReplacementRange() {
        String sql = "select orders.na";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("na"), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void dummyInsertsIdentifierForEmptyColumnContextPrefix() {
        String sql = "select * from orders where ";
        SqlCompletionStatementWindow window = locate(sql, sql.length());
        SqlCompletionCursorContext context = cursorAnalyzer.analyze(window);

        SqlCompletionDummySql dummySql = dummyBuilder.build(window, context);

        Assertions.assertTrue(context.admitted());
        Assertions.assertEquals(SqlCompletionDummyTypeEnum.IDENTIFIER.name(), dummySql.type());
        Assertions.assertTrue(dummySql.sql().contains("__chat2db_completion_dummy"));
        Assertions.assertEquals(sql.length(), context.replaceStart());
        Assertions.assertEquals(sql.length(), context.replaceEnd());
    }

    @Test
    void dummyInsertsIdentifierForEmptyTableReferenceContext() {
        String sql = "select * from ";
        SqlCompletionStatementWindow window = locate(sql, sql.length());
        SqlCompletionCursorContext context = cursorAnalyzer.analyze(window);

        SqlCompletionDummySql dummySql = dummyBuilder.build(window, context);

        Assertions.assertTrue(context.admitted());
        Assertions.assertEquals(SqlCompletionDummyTypeEnum.IDENTIFIER.name(), dummySql.type());
        Assertions.assertTrue(dummySql.sql().contains("__chat2db_completion_dummy"));
    }

    @Test
    void dummyInsertsExpressionForJoinOnEmptyContext() {
        String sql = "select * from users u join orders o on ";
        SqlCompletionStatementWindow window = locate(sql, sql.length());
        SqlCompletionCursorContext context = cursorAnalyzer.analyze(window);

        SqlCompletionDummySql dummySql = dummyBuilder.build(window, context);

        Assertions.assertTrue(context.admitted());
        Assertions.assertEquals(SqlCompletionDummyTypeEnum.IDENTIFIER.name(), dummySql.type());
        Assertions.assertTrue(dummySql.sql().contains("__chat2db_completion_dummy"));
    }

    @Test
    void cursorAnalyzerRejectsStringAndCommentCursors() {
        String inString = "select * from orders where name = 'na";
        String closedString = "select * from orders where name = 'name'";
        String inComment = "select * from orders -- na";

        Assertions.assertFalse(cursorAnalyzer.analyze(locate(inString, inString.length())).admitted());
        Assertions.assertFalse(cursorAnalyzer.analyze(locate(closedString, closedString.indexOf("name'") + 2))
                .admitted());
        Assertions.assertFalse(cursorAnalyzer.analyze(locate(inComment, inComment.length())).admitted());
    }

    @Test
    void cursorAnalyzerDoesNotDependOnCandidateType() throws Exception {
        String source = Files.readString(mysqlCompletionSource(
                "analysis/MysqlSqlCompletionCursorAnalyzer.java"));

        Assertions.assertFalse(source.contains("SqlCompletionCandidateTypeEnum"));
        Assertions.assertFalse(source.contains("SqlCompletionCursorClauseTypeEnum"));
    }

    @Test
    void c3EngineDoesNotDependOnCursorClauseFallback() throws Exception {
        String source = Files.readString(mysqlCompletionSource("c3/MysqlSqlCompletionEngine.java"));

        Assertions.assertFalse(source.contains("SqlCompletionCursorClauseTypeEnum"));
        Assertions.assertFalse(source.contains("fallback"));
        Assertions.assertFalse(source.contains("C3ParseView"));
    }

    private static Path mysqlCompletionSource(String relativePath) {
        Path moduleSource = Path.of("src/main/java/ai/chat2db/plugin/mysql/completion");
        if (Files.isDirectory(moduleSource)) {
            return moduleSource.resolve(relativePath);
        }
        return Path.of("chat2db-community-plugins/chat2db-community-mysql/src/main/java/ai/chat2db/plugin/mysql/completion")
                .resolve(relativePath);
    }

    @Test
    void dummyDoesNotChangeProviderReplacementRange() {
        String sql = "select * from old_orders; select orders.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void completesColumnsAfterQualifiedQuotedTableDot() {
        String sql = "select `shop`.`orders`.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("shop", metadataProvider.lastRequest.scope().schema());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void locatorKeepsLargeScriptCurrentWindowBoundedByStatementSeparator() {
        StringBuilder sqlBuilder = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sqlBuilder.append("select * from archive_").append(i).append(";\n");
        }
        int currentStart = sqlBuilder.length();
        sqlBuilder.append("trace prefix select * from orders where na");
        sqlBuilder.append(";\nselect * from next_table");
        String sql = sqlBuilder.toString();
        int cursor = currentStart + "trace prefix select * from orders where na".length();

        SqlCompletionStatementWindow window = locate(sql, cursor);
        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(currentStart + "trace prefix ".length(), window.sourceStartOffset());
        Assertions.assertEquals("select * from orders where na", window.sourceSql());
        assertSuccessReplacement(result, sql.indexOf("na", currentStart), cursor);
        Assertions.assertEquals("na", metadataProvider.lastRequest.prefix());
    }

    @Test
    void locatorKeepsProcedureBodyInternalSemicolonsInRoutineWindow() {
        String sql = "create procedure sync_orders()\n"
                + "begin\n"
                + "  select * from orders where na;\n"
                + "  update orders set status = 'DONE';\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().startsWith("create procedure sync_orders()"));
        Assertions.assertTrue(window.sourceSql().contains("update orders set status = 'DONE';"));
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void locatorKeepsDelimiterProcedureAsSingleRoutineWindow() {
        String sql = "delimiter //\n"
                + "create procedure sync_orders()\n"
                + "begin\n"
                + "  select * from orders where na;\n"
                + "  update orders set status = 'DONE';\n"
                + "end //\n"
                + "delimiter ;\n"
                + "select * from users";
        int createOffset = sql.indexOf("create procedure");
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(createOffset, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf("\ndelimiter ;"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().startsWith("create procedure sync_orders()"));
        Assertions.assertTrue(window.sourceSql().contains("select * from orders where na;"));
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void locatorSelectsStatementAfterDelimiterRoutine() {
        String sql = "delimiter //\n"
                + "create procedure sync_orders()\n"
                + "begin\n"
                + "  select * from orders;\n"
                + "end //\n"
                + "delimiter ;\n"
                + "select * from users where na";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);
        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(sql.lastIndexOf("select * from users"), window.sourceStartOffset());
        Assertions.assertEquals("select * from users where na", window.sourceSql());
        assertSuccessReplacement(result, sql.lastIndexOf("na"), cursor);
    }

    @Test
    void locatorKeepsTriggerBodyInternalSemicolonsInRoutineWindow() {
        String sql = "create trigger before_orders_update before update on orders\n"
                + "for each row\n"
                + "begin\n"
                + "  set new.updated_by = user();\n"
                + "  select new.updated_by;\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("updated_by =") + "updated_by".length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().startsWith("create trigger before_orders_update"));
        Assertions.assertTrue(window.sourceSql().contains("select new.updated_by;"));
    }

    @Test
    void locatorKeepsFunctionWithNestedControlBlocksInRoutineWindow() {
        String sql = "create function normalize_status(p_status varchar(20)) returns varchar(20)\n"
                + "begin\n"
                + "  if p_status is null then\n"
                + "    return 'NEW';\n"
                + "  end if;\n"
                + "  case p_status\n"
                + "    when 'done' then return 'DONE';\n"
                + "    else return upper(p_status);\n"
                + "  end case;\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("upper(p_status)") + "upper(".length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("end if;"));
        Assertions.assertTrue(window.sourceSql().contains("end case;"));
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void locatorDistinguishesIfBlockFromIfFunctionInsideRoutine() {
        String sql = "create procedure score_orders()\n"
                + "begin\n"
                + "  if na then\n"
                + "    select if(status = 'DONE', amount, 0) from orders;\n"
                + "  end if;\n"
                + "  select if(na, 1, 0) from orders;\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("if na") + "if ".length() + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);
        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("select if(status = 'DONE', amount, 0)"));
        Assertions.assertTrue(window.sourceSql().contains("select if(na, 1, 0)"));
        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.PARAMETER.name(), metadataProvider.lastRequest.type());
    }

    @Test
    void completeRoutineParameterFromLocalContext() {
        String sql = "create function normalize_status(p_status varchar(20)) returns varchar(20)\n"
                + "begin\n"
                + "  return p_sta;\n"
                + "end";
        int cursor = sql.lastIndexOf("p_sta") + "p_sta".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("p_sta"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "p_status");
        Assertions.assertEquals("varchar", candidate.getDataType());
        Assertions.assertTrue((Integer) traceValues(result, "localContext").get("variableCount") >= 1);
    }

    @Test
    void definingRoutineParameterDoesNotCompleteItAsLocalVariable() {
        String sql = "create function sdasdasd(a) returns int return ;";
        int cursor = sql.indexOf("a)") + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "a");
        Assertions.assertEquals(0, traceValues(result, "localContext").get("variableCount"));
    }

    @Test
    void completeDeclaredRoutineVariableFromLocalContext() {
        String sql = "create procedure score_orders()\n"
                + "begin\n"
                + "  declare total_score int;\n"
                + "  set total_score = total_ + 1;\n"
                + "end";
        int cursor = sql.lastIndexOf("total_") + "total_".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("total_"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "total_score");
        Assertions.assertEquals("int", candidate.getDataType());
        Assertions.assertTrue((Integer) traceValues(result, "localContext").get("variableCount") >= 1);
    }

    @Test
    void locatorKeepsProcedureWithLoopInRoutineWindow() {
        String sql = "create procedure archive_orders()\n"
                + "begin\n"
                + "  read_loop: loop\n"
                + "    select * from orders where na;\n"
                + "    leave read_loop;\n"
                + "  end loop;\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("read_loop: loop"));
        Assertions.assertTrue(window.sourceSql().contains("end loop;"));
    }

    @Test
    void locatorKeepsProcedureWithStackPairedWhileIfCaseBlocksInRoutineWindow() {
        String sql = "create procedure rebalance_orders()\n"
                + "begin\n"
                + "  while na do\n"
                + "    if status = 'NEW' then\n"
                + "      case priority\n"
                + "        when 'HIGH' then select * from orders;\n"
                + "        else select * from users;\n"
                + "      end case;\n"
                + "    end if;\n"
                + "  end while;\n"
                + "end;\n"
                + "select * from audit_log";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from audit_log"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("end case;"));
        Assertions.assertTrue(window.sourceSql().contains("end if;"));
        Assertions.assertTrue(window.sourceSql().contains("end while;"));
    }

    @Test
    void locatorKeepsOuterRoutineWhenNestedRoutineAppearsInBody() {
        String sql = "create procedure outer_sync()\n"
                + "begin\n"
                + "  create procedure inner_sync()\n"
                + "  begin\n"
                + "    select * from orders where na;\n"
                + "  end;\n"
                + "  update orders set status = 'DONE';\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql.indexOf(";\nselect * from users"), window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("create procedure inner_sync()"));
        Assertions.assertTrue(window.sourceSql().contains("update orders set status = 'DONE';"));
    }

    @Test
    void locatorSelectsStatementAfterNestedRoutineWindow() {
        String sql = "create procedure outer_sync()\n"
                + "begin\n"
                + "  create procedure inner_sync()\n"
                + "  begin\n"
                + "    select * from orders;\n"
                + "  end;\n"
                + "end;\n"
                + "select * from users where na";
        int cursor = sql.length();

        SqlCompletionStatementWindow window = locate(sql, cursor);
        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(sql.lastIndexOf("select * from users"), window.sourceStartOffset());
        Assertions.assertEquals("select * from users where na", window.sourceSql());
        assertSuccessReplacement(result, sql.lastIndexOf("na"), cursor);
    }

    @Test
    void incompleteJoinOnDotEmptyPrefixCompletesAliasColumns() {
        String sql = "select * from users u join orders o on u.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.COLUMN.name(), metadataProvider.lastRequest.type());
        Assertions.assertEquals("users", metadataProvider.lastRequest.scope().table());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void nestedSubqueryWindowStaysInOuterStatement() {
        String sql = "select t.na from (select id, name from users) t where t.id > 0";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);
        SqlCompletionResponse result = complete(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(sql, window.sourceSql());
        assertSuccessReplacement(result, sql.indexOf("na"), cursor);
        Assertions.assertEquals(1, metadataProvider.callCount);
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.COLUMN
                        && "name".equals(candidate.getLabel())));
    }

    @Test
    void ignoresMinPrefixLengthForTableCompletion() {
        String sql = "select * from o";

        SqlCompletionResponse result = provider.complete(DbSqlCompletionRequest.of(
                sql, sql.length(), "MYSQL", 2, metadataProvider));

        assertSuccessReplacement(result, sql.lastIndexOf("o"), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).isEmpty());
    }

    @Test
    void ignoresMinPrefixLengthForColumnCompletion() {
        String sql = "select * from orders where n";

        SqlCompletionResponse result = provider.complete(DbSqlCompletionRequest.of(
                sql, sql.length(), "MYSQL", 2, metadataProvider));

        assertSuccessReplacement(result, sql.lastIndexOf("n"), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.COLUMN).isEmpty());
    }

    @Test
    void dotScopedColumnCompletionAllowsEmptyMemberPrefix() {
        String sql = "select orders.";

        SqlCompletionResponse result = provider.complete(DbSqlCompletionRequest.of(
                sql, sql.length(), "MYSQL", 2, metadataProvider));

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.COLUMN).isEmpty());
        Assertions.assertEquals("", metadataProvider.lastRequest.prefix());
        Assertions.assertEquals("orders", metadataProvider.lastRequest.scope().table());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
    }

    @Test
    void dotScopedTableCompletionAllowsEmptyMemberPrefix() {
        String sql = "select * from app.";

        SqlCompletionResponse result = provider.complete(DbSqlCompletionRequest.of(
                sql, sql.length(), "MYSQL", 2, metadataProvider));

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertFalse(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).isEmpty());
        DbSqlCompletionMetadataRequest tableRequest =
                metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).get(0);
        Assertions.assertEquals("app", tableRequest.scope().schema());
        Assertions.assertNull(tableRequest.scope().table());
        Assertions.assertEquals("", tableRequest.prefix());
        Assertions.assertTrue(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.DATABASE).isEmpty());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE);
    }

    @Test
    void dotScopedTableCompletionUsesExplicitSchemaInsteadOfCurrentDatabase() {
        metadataProvider.defaultDatabaseName = "db2";
        metadataProvider.schemaSensitiveMetadata = true;
        String sql = "select * from db1.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name()),
                metadataProvider.requestTypes());
        DbSqlCompletionMetadataRequest tableRequest =
                metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.TABLE_VIEW).get(0);
        Assertions.assertEquals("db1", tableRequest.scope().schema());
        Assertions.assertNull(tableRequest.scope().table());
        Assertions.assertEquals("", tableRequest.prefix());
        candidate(result, SqlCompletionCandidateTypeEnum.TABLE, "db1_orders");
        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.TABLE, "db2_orders");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
    }

    @Test
    void fromQualifiedTableDotDoesNotCompleteColumns() {
        metadataProvider.defaultDatabaseName = "db2";
        metadataProvider.schemaSensitiveMetadata = true;
        String sql = "select * from db1.orders.";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertTrue(metadataProvider.requests.isEmpty());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE_VIEW);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
    }

    @Test
    void fromQualifiedTableDotPrefixDoesNotCompleteColumns() {
        metadataProvider.defaultDatabaseName = "db2";
        metadataProvider.schemaSensitiveMetadata = true;
        String sql = "select * from db1.orders.db1_s";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertTrue(metadataProvider.requests.isEmpty());
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.TABLE_VIEW);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.DATABASE);
    }

    @Test
    void expressionOperatorEmptyPrefixUsesNormalFlow() {
        String sql = "select * from orders where status = ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.COLUMN);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void returnsEmptyWhenMetadataReturnsNoCandidatesWithoutFunctionRule() {
        metadataProvider.emptyCandidates = true;
        String sql = "select * from ord";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertEmpty(result);
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
    }

    @Test
    void returnsUnsupportedWhenMetadataLookupIsUnsupported() {
        metadataProvider.unsupported = true;
        String sql = "select * from ord";

        SqlCompletionResponse result = complete(sql, sql.length());

        Assertions.assertEquals(SqlCompletionStatusEnum.UNSUPPORTED.name(), result.getStatus());
        Assertions.assertEquals("sql.completion.unsupported.MYSQL", result.getReasonCode());
        Assertions.assertEquals(List.of(SqlCompletionCandidateTypeEnum.TABLE_VIEW.name(),
                SqlCompletionCandidateTypeEnum.DATABASE.name()), metadataProvider.requestTypes());
    }

    @Test
    void emptyPrefixInsertValueReturnsEditorHintWithoutCompletionCandidates() {
        String sql = "INSERT INTO orders (id, status) VALUES (1, )";

        SqlCompletionResponse result = complete(sql, sql.length() - 1);

        assertEmpty(result);
        SqlCompletionEditorHint hint = singleEditorHint(result, SqlCompletionEditorHintTypeEnum.INSERT_VALUE);
        SqlCompletionEditorHint.Item activeItem = hint.getItems().stream()
                .filter(SqlCompletionEditorHint.Item::isActive)
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals("status", activeItem.getFieldName());
        Assertions.assertEquals("VARCHAR", activeItem.getFieldType());
        Assertions.assertEquals("''", activeItem.getDefaultValue());
    }

    @Test
    void completeReturnsAllInsertValueEditorHintsWithoutCompletionCandidatesForEmptyValuesRow() {
        String sql = "INSERT INTO orders (id, amount, status) VALUES ()";

        SqlCompletionResponse result = complete(sql, sql.lastIndexOf(")"));

        assertEmpty(result);
        SqlCompletionEditorHint hint = singleEditorHint(result, SqlCompletionEditorHintTypeEnum.INSERT_VALUE);
        Assertions.assertEquals(3, hint.getItems().size());
        Assertions.assertEquals("id", hint.getItems().get(0).getFieldName());
        Assertions.assertEquals("BIGINT", hint.getItems().get(0).getFieldType());
        Assertions.assertEquals("0", hint.getItems().get(0).getDefaultValue());
    }

    @Test
    void typeAwareValuesHandleBooleanAndDateColumns() {
        String booleanSql = "INSERT INTO typed_values (active) VALUES ()";
        SqlCompletionResponse booleanResult = complete(booleanSql, booleanSql.lastIndexOf(")"));

        assertEmpty(booleanResult);
        Assertions.assertEquals("FALSE", singleEditorHint(booleanResult, SqlCompletionEditorHintTypeEnum.INSERT_VALUE)
                .getItems().get(0).getDefaultValue());

        String dateSql = "INSERT INTO typed_values (created_at) VALUES ()";
        SqlCompletionResponse dateResult = complete(dateSql, dateSql.lastIndexOf(")"));

        assertEmpty(dateResult);
        Assertions.assertEquals("CURRENT_TIMESTAMP",
                singleEditorHint(dateResult, SqlCompletionEditorHintTypeEnum.INSERT_VALUE)
                        .getItems().get(0).getDefaultValue());
    }

    @Test
    void emptyPrefixAfterInsertValueSlotDoesNotReturnEditorHints() {
        String sql = "INSERT INTO orders (id) VALUES (1);";

        SqlCompletionResponse result = complete(sql, sql.indexOf(";"));

        assertEmpty(result);
        Assertions.assertTrue(result.getEditorHints().isEmpty());
    }

    @Test
    void emptyPrefixFunctionParameterDoesNotReturnEditorHints() {
        String sql = "select add_numbers(1, 2)";
        int cursor = sql.indexOf("2") + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertEmpty(result);
        Assertions.assertTrue(result.getEditorHints().isEmpty());
    }

    @Test
    void completeReturnsAllFunctionParameterEditorHintsForEmptyCall() {
        String sql = "select add_numbers()";
        int cursor = sql.indexOf(")");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        Assertions.assertTrue(result.getEditorHints().isEmpty());
    }

    @Test
    void completeKeepsRoutineParameterEditorHintsAfterCursorLeavesCallWithPrefix() {
        String sql = "select add_numbers(1, 2) from dual";

        SqlCompletionResponse result = complete(sql, sql.length());

        SqlCompletionEditorHint hint = singleEditorHint(result, SqlCompletionEditorHintTypeEnum.ROUTINE_PARAMETER);
        Assertions.assertEquals(2, hint.getItems().size());
        Assertions.assertFalse(hint.getItems().stream().anyMatch(SqlCompletionEditorHint.Item::isActive));
        Assertions.assertTrue(hint.getItems().stream()
                .anyMatch(item -> "a".equals(item.getFieldName()) && "INT".equals(item.getFieldType())));
        Assertions.assertTrue(hint.getItems().stream()
                .anyMatch(item -> "b".equals(item.getFieldName()) && "INT".equals(item.getFieldType())));
    }

    @Test
    void emptyPrefixProcedureParameterDoesNotReturnEditorHints() {
        String sql = "call refresh_orders(1, )";

        SqlCompletionResponse result = complete(sql, sql.length() - 1);

        assertSuccessReplacement(result, sql.length() - 1, sql.length() - 1);
        Assertions.assertTrue(result.getEditorHints().isEmpty());
    }

    @Test
    void completeDoesNotReturnRoutineParameterEditorHintsInsideStringLiteral() {
        String sql = "select 'add_numbers(1, )'";

        SqlCompletionResponse result = complete(sql, sql.indexOf(",") + 1);

        Assertions.assertTrue(result.getEditorHints().stream()
                .noneMatch(hint -> hint.getType() == SqlCompletionEditorHintTypeEnum.ROUTINE_PARAMETER));
        Assertions.assertTrue(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.PARAMETER).isEmpty());
    }

    @Test
    void completeDoesNotLookupRoutineParametersForColumnTypeParentheses() {
        String sql = "create table t (id int(11))";

        SqlCompletionResponse result = complete(sql, sql.indexOf("11") + 1);

        Assertions.assertTrue(result.getEditorHints().stream()
                .noneMatch(hint -> hint.getType() == SqlCompletionEditorHintTypeEnum.ROUTINE_PARAMETER));
        Assertions.assertTrue(metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.PARAMETER).isEmpty());
    }

    @Test
    void completeReturnsFunctionCandidateSnippetWithRoutineParameters() {
        String sql = "select add_";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("add_"), sql.length());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.FUNCTION, "add_numbers");
        Assertions.assertEquals("add_numbers(${1:a}, ${2:b})$0", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionInsertTypeEnum.SNIPPET, candidate.getInsertType());
        Assertions.assertEquals("(a:INT, b:INT)", candidate.getDetail());
    }

    @Test
    void emptyPrefixSelectWithoutActiveSnippetSlotUsesNormalFlowWithoutSnippets() {
        String sql = "select ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void emptyPrefixSelectFunctionSnippetSlotUsesNormalFlowWithoutSnippets() {
        String sql = "select ;";
        int cursor = sql.indexOf(";");

        SqlCompletionResponse result = complete(sql, cursor, activeSlot(
                SqlCompletionSnippetSlotTypeEnum.SELECT_FUNCTION, cursor, cursor));

        assertSuccessReplacement(result, cursor, cursor);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void emptyPrefixCallCompletesProcedureCandidates() {
        String sql = "call ";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.length(), sql.length());
        Assertions.assertEquals(SqlCompletionCandidateTypeEnum.PROCEDURE.name(),
                metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.PROCEDURE).get(0).type());
        Assertions.assertEquals("", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.PROCEDURE)
                .get(0).prefix());
        candidate(result, SqlCompletionCandidateTypeEnum.PROCEDURE, "_candidate");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void emptyPrefixCallProcedureSnippetSlotReturnsProcedureCandidates() {
        String sql = "call ;";
        int cursor = sql.indexOf(";");

        SqlCompletionResponse result = complete(sql, cursor, activeSlot(
                SqlCompletionSnippetSlotTypeEnum.CALL_PROCEDURE, cursor, cursor));

        assertSuccessReplacement(result, cursor, cursor);
        Assertions.assertEquals("", metadataProvider.requestsOf(SqlCompletionCandidateTypeEnum.PROCEDURE)
                .get(0).prefix());
        candidate(result, SqlCompletionCandidateTypeEnum.PROCEDURE, "_candidate");
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.KEYWORD);
        assertNoCandidateType(result, SqlCompletionCandidateTypeEnum.SNIPPET);
    }

    @Test
    void completeReturnsFunctionCandidateSnippetInsideExpressionParentheses() {
        String sql = "select (add_);";
        int cursor = sql.indexOf(")");

        SqlCompletionResponse result = complete(sql, cursor);

        assertEmpty(result);
    }

    @Test
    void completeReplacesEmptyFunctionArgumentListWithSnippet() {
        String sql = "select add_()";
        int cursor = sql.indexOf("(");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("add_"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.FUNCTION, "add_numbers");
        Assertions.assertEquals("add_numbers(${1:a}, ${2:b})$0", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionInsertTypeEnum.SNIPPET, candidate.getInsertType());
        Assertions.assertEquals(Integer.valueOf(sql.indexOf("add_")), candidate.getReplaceStart());
        Assertions.assertEquals(Integer.valueOf(sql.indexOf(")") + 1), candidate.getReplaceEnd());
    }

    @Test
    void completeKeepsExistingFunctionArgumentsWhenArgumentListIsNotEmpty() {
        String sql = "select add_(1, 2)";
        int cursor = sql.indexOf("(");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("add_"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.FUNCTION, "add_numbers");
        Assertions.assertEquals("add_numbers", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionInsertTypeEnum.PLAIN_TEXT, candidate.getInsertType());
        Assertions.assertNull(candidate.getReplaceStart());
        Assertions.assertNull(candidate.getReplaceEnd());
    }

    @Test
    void completeReturnsProcedureCandidateSnippetWithRoutineParameters() {
        String sql = "call refresh_";

        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.indexOf("refresh_"), sql.length());
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.PROCEDURE, "refresh_orders");
        Assertions.assertEquals("refresh_orders(${1:tenant_id}, ${2:force})$0", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionInsertTypeEnum.SNIPPET, candidate.getInsertType());
        Assertions.assertEquals("(tenant_id:BIGINT, force:BOOLEAN)", candidate.getDetail());
    }

    @Test
    void completeReplacesEmptyProcedureArgumentListWithSnippet() {
        String sql = "call refresh_()";
        int cursor = sql.indexOf("(");

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.indexOf("refresh_"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.PROCEDURE, "refresh_orders");
        Assertions.assertEquals("refresh_orders(${1:tenant_id}, ${2:force})$0", candidate.getInsertText());
        Assertions.assertEquals(SqlCompletionInsertTypeEnum.SNIPPET, candidate.getInsertType());
        Assertions.assertEquals(Integer.valueOf(sql.indexOf("refresh_")), candidate.getReplaceStart());
        Assertions.assertEquals(Integer.valueOf(sql.indexOf(")") + 1), candidate.getReplaceEnd());
    }

    @Test
    void completionResultIncludesPipelineTraceWithC3RulesAndPlan() {
        String sql = "create table t (id bigint, constraint fk_item_order f";

        SqlCompletionResponse result = complete(sql, sql.length());

        Assertions.assertFalse(result.getTrace().steps().isEmpty());
        Map<String, Object> c3 = traceValues(result, "c3");
        Assertions.assertEquals(Boolean.TRUE, c3.get("available"));
        Assertions.assertTrue(c3.containsKey("tokens"));
        Assertions.assertTrue(c3.containsKey("rules"));
        Assertions.assertFalse(((Map<?, ?>) c3.get("tokens")).isEmpty());
        Map<String, Object> ruleEvidence = traceValues(result, "ruleEvidence");
        Assertions.assertFalse(((List<?>) ruleEvidence.get("items")).isEmpty());
        Assertions.assertFalse(((List<?>) traceValues(result, "slots").get("items")).isEmpty());
        Assertions.assertFalse(((List<?>) traceValues(result, "intents").get("items")).isEmpty());
        Assertions.assertFalse(((List<?>) traceValues(result, "candidatePlan").get("items")).isEmpty());
        Assertions.assertEquals("SUCCESS", traceValues(result, "result").get("status"));
    }

    @Test
    void localContextTraceIncludesSourceDistribution() {
        String sql = """
                create table draft_order (
                  id bigint
                );
                set @result = 1;
                insert into draft_
                """;
        int cursor = sql.lastIndexOf("draft_") + "draft_".length();

        SqlCompletionResponse result = complete(sql, cursor);

        Map<String, Object> localContext = traceValues(result, "localContext");
        Assertions.assertTrue((Integer) localContext.get("relationCount") >= 1);
        Assertions.assertTrue((Integer) localContext.get("variableCount") >= 1);
        Assertions.assertTrue(((Map<?, ?>) localContext.get("relationSources")).containsKey("DRAFT_DDL"));
        Assertions.assertTrue(((Map<?, ?>) localContext.get("variableSources")).containsKey("USER_VARIABLE"));
    }

    @Test
    void completeTableReferenceIncludesDraftCreateTableFromSameEditor() {
        String sql = """
                create table draft_order (
                  id bigint,
                  amount decimal(12,2)
                );
                insert into draft_
                """;
        int cursor = sql.lastIndexOf("draft_") + "draft_".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("draft_"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.TABLE, "draft_order");
        Assertions.assertEquals("draft_order", candidate.getTableName());
        Assertions.assertEquals(100, candidate.getSortRank());
        Assertions.assertTrue((Integer) traceValues(result, "localContext").get("relationCount") >= 1);
    }

    @Test
    void completeTableReferenceIncludesDraftTemporaryCreateTableFromSameEditor() {
        String sql = """
                create temporary table tmp_order (
                  id bigint
                );
                select * from tmp_
                """;
        int cursor = sql.lastIndexOf("tmp_") + "tmp_".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("tmp_"), cursor);
        candidate(result, SqlCompletionCandidateTypeEnum.TABLE, "tmp_order");
    }

    @Test
    void incompleteIfNotExistsDraftTableDoesNotPublishIfAsTable() {
        String sql = """
                create table if
                insert into i
                """;

        SqlCompletionResponse result = complete(sql, sql.length());

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.TABLE, "if");
    }

    @Test
    void completeInsertColumnListUsesDraftCreateTableColumnsFromSameEditor() {
        String sql = """
                create table draft_order (
                  id bigint,
                  amount decimal(12,2),
                  status varchar(20)
                );
                insert into draft_order (a
                """;
        int cursor = sql.lastIndexOf("a\n") + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("a"), cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "amount");
        Assertions.assertEquals("draft_order", candidate.getTableName());
        Assertions.assertEquals("amount", candidate.getColumnName());
        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "id");
    }

    @Test
    void completeQualifiedColumnsUsesDraftCreateTableColumnsFromSameEditor() {
        String sql = """
                create table draft_order (
                  id bigint,
                  amount decimal(12,2),
                  status varchar(20)
                );
                select d.a from draft_order d
                """;
        int replaceStart = sql.lastIndexOf("a from");
        int cursor = replaceStart + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, replaceStart, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "amount");
        Assertions.assertEquals("draft_order", candidate.getTableName());
        Assertions.assertEquals("d", candidate.getTableAlias());
        Assertions.assertEquals("amount", candidate.getColumnName());
        assertNoMetadataRequest(SqlCompletionCandidateTypeEnum.COLUMN, "draft_order");
    }

    @Test
    void completeUnqualifiedColumnsUsesAliasedDraftCreateTableColumnsFromSameEditor() {
        String sql = """
                create table draft_order (
                  id bigint,
                  amount decimal(12,2),
                  status varchar(20)
                );
                select a from draft_order d
                """;
        int replaceStart = sql.lastIndexOf("a from");
        int cursor = replaceStart + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, replaceStart, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "amount");
        Assertions.assertEquals("draft_order", candidate.getTableName());
        Assertions.assertEquals("d", candidate.getTableAlias());
        Assertions.assertEquals("d.amount", candidate.getInsertText());
        assertNoMetadataRequest(SqlCompletionCandidateTypeEnum.COLUMN, "draft_order");
    }

    @Test
    void openDraftCreateTableDoesNotCollectColumnsPastStatementSeparator() {
        String sql = """
                create table draft_order (
                  id bigint
                ;
                select leaked_column from users;
                insert into draft_order (l
                """;

        SqlCompletionResponse result = complete(sql, sql.length());

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.COLUMN, "leaked_column");
    }

    @Test
    void completeUserVariableProducedByOutCallFromSameEditor() {
        String sql = """
                call add_numbers_proc(1, 2, @result);
                select @res
                """;
        int cursor = sql.lastIndexOf("@res") + "@res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("@res") + 1, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
        Assertions.assertEquals("@result", candidate.getObjectName());
        Assertions.assertEquals("result", candidate.getInsertText());
        Assertions.assertEquals("INT", candidate.getDataType());
        Assertions.assertTrue((Integer) traceValues(result, "localContext").get("variableCount") >= 1);
    }

    @Test
    void completeUserVariableProducedByInoutCallFromSameEditor() {
        String sql = """
                call mix_proc(@value);
                select @va
                """;
        int cursor = sql.lastIndexOf("@va") + "@va".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("@va") + 1, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@value");
        Assertions.assertEquals("value", candidate.getInsertText());
    }

    @Test
    void callInArgumentDoesNotProduceUserVariable() {
        String sql = """
                call echo_proc(@input);
                select @in
                """;
        int cursor = sql.lastIndexOf("@in") + "@in".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@input");
    }

    @Test
    void unknownCallDoesNotProduceUserVariable() {
        String sql = """
                call unknown_proc(@result);
                select @res
                """;
        int cursor = sql.lastIndexOf("@res") + "@res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
    }

    @Test
    void userVariableCandidateRequiresAtContext() {
        String sql = """
                set @result = 1;
                select res
                """;
        int cursor = sql.lastIndexOf("res") + "res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
    }

    @Test
    void userVariablePlainReferenceDoesNotProduceCandidate() {
        String sql = """
                select @result;
                select @res
                """;
        int cursor = sql.lastIndexOf("@res") + "@res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
    }

    @Test
    void userVariableMentionInCommentOrStringDoesNotProduceCandidate() {
        String sql = """
                select '@result';
                -- set @result = 1;
                select @res
                """;
        int cursor = sql.lastIndexOf("@res") + "@res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
    }

    @Test
    void userVariableCandidateDoesNotAppearAtBlankExpressionPrefix() {
        String sql = "set @result = 1;\n"
                + "select ";
        int cursor = sql.length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, cursor, cursor);
        assertNoCandidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
        assertCandidateType(result, SqlCompletionCandidateTypeEnum.FUNCTION);
    }

    @Test
    void userVariableCandidateDoesNotAppearAfterAtMarkerOnly() {
        String sql = """
                set @result = 1;
                select @
                """;
        int cursor = sql.length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertEmpty(result);
    }

    @Test
    void userVariableCandidateDoesNotAppearAfterSeparatedAtMarker() {
        String sql = """
                set @result = 1;
                select @ res
                """;
        int cursor = sql.lastIndexOf("@") + 1;

        SqlCompletionResponse result = complete(sql, cursor);

        assertEmpty(result);
    }

    @Test
    void userVariableAssignmentCandidateUsesSuffixInsertTextAfterAt() {
        String sql = """
                set @result = 1;
                select @res
                """;
        int cursor = sql.lastIndexOf("@res") + "@res".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("@res") + 1, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@result");
        Assertions.assertEquals("result", candidate.getInsertText());
    }

    @Test
    void userVariableAssignmentCandidateUsesSuffixInsertTextAfterAtInLaterStatement() {
        String sql = """
                set @dasdsa=1;

                select @da
                """;
        int cursor = sql.lastIndexOf("@da") + "@da".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("@da") + 1, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@dasdsa");
        Assertions.assertEquals("dasdsa", candidate.getInsertText());
    }

    @Test
    void selectIntoMultipleUserVariablesAreCollected() {
        String sql = """
                select count(*), max(id) into @total_count, @max_id from orders;
                select @max
                """;
        int cursor = sql.lastIndexOf("@max") + "@max".length();

        SqlCompletionResponse result = complete(sql, cursor);

        assertSuccessReplacement(result, sql.lastIndexOf("@max") + 1, cursor);
        SqlCompletionCandidate candidate = candidate(result, SqlCompletionCandidateTypeEnum.VARIABLE, "@max_id");
        Assertions.assertEquals("max_id", candidate.getInsertText());
    }

    private SqlCompletionResponse complete(String sql, int cursor) {
        return provider.complete(DbSqlCompletionRequest.of(sql, cursor, "MYSQL", 1, metadataProvider));
    }

    private SqlCompletionResponse complete(String sql, int cursor, SqlCompletionActiveSnippetSlot activeSnippetSlot) {
        return provider.complete(DbSqlCompletionRequest.of(sql, cursor, "MYSQL", 1, metadataProvider,
                activeSnippetSlot));
    }

    private SqlCompletionActiveSnippetSlot activeSlot(SqlCompletionSnippetSlotTypeEnum type, int start, int end) {
        return new SqlCompletionActiveSnippetSlot(type.name(), start, end);
    }

    private SqlCompletionStatementWindow locate(String sql, int cursor) {
        return statementLocator.locate(MysqlSqlCompletionInputCleaner.clean(sql, cursor));
    }

    private Map<String, Object> traceValues(SqlCompletionResponse result, String stage) {
        return result.getTrace().steps().stream()
                .filter(step -> stage.equals(step.stage()))
                .findFirst()
                .orElseThrow()
                .values();
    }

    private void assertLengthPreserved(String sql, SqlCompletionInputCleanResponse result) {
        Assertions.assertEquals(sql, result.sourceSql());
        Assertions.assertEquals(sql.length(), result.parseSql().length());
    }

    private void assertMaskedExpression(String parseSql, int start, int length) {
        Assertions.assertTrue(start >= 0, "expression start should exist");
        Assertions.assertEquals('0', parseSql.charAt(start));
        for (int i = start + 1; i < start + length; i++) {
            Assertions.assertEquals(' ', parseSql.charAt(i), "expression tail should be masked at " + i);
        }
    }

    private void assertSuccessReplacement(SqlCompletionResponse result, int replaceStart, int replaceEnd) {
        Assertions.assertEquals(SqlCompletionStatusEnum.SUCCESS.name(), result.getStatus());
        Assertions.assertEquals(replaceStart, result.getReplaceStart());
        Assertions.assertEquals(replaceEnd, result.getReplaceEnd());
        Assertions.assertFalse(result.getCandidates().isEmpty());
    }

    private void assertEmpty(SqlCompletionResponse result) {
        Assertions.assertEquals(SqlCompletionStatusEnum.EMPTY.name(), result.getStatus());
        Assertions.assertTrue(result.getCandidates().isEmpty());
    }

    private void assertCandidateType(SqlCompletionResponse result, SqlCompletionCandidateTypeEnum type) {
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == type), "expected candidate type " + type);
    }

    private void assertNoCandidateType(SqlCompletionResponse result, SqlCompletionCandidateTypeEnum type) {
        Assertions.assertFalse(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == type), "unexpected candidate type " + type);
    }

    private SqlCompletionCandidate candidate(SqlCompletionResponse result,
                                             SqlCompletionCandidateTypeEnum type,
                                             String label) {
        return result.getCandidates().stream()
                .filter(candidate -> candidate.getType() == type)
                .filter(candidate -> label.equals(candidate.getLabel()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected candidate " + type + ":" + label));
    }

    private void assertNoMetadataRequest(SqlCompletionCandidateTypeEnum type, String table) {
        Assertions.assertFalse(metadataProvider.requestsOf(type).stream()
                        .anyMatch(request -> table.equals(request.scope().table())),
                () -> "unexpected metadata request " + type + ":" + table);
    }

    private void assertNoCandidate(SqlCompletionResponse result,
                                   SqlCompletionCandidateTypeEnum type,
                                   String label) {
        Assertions.assertFalse(result.getCandidates().stream()
                        .anyMatch(candidate -> candidate.getType() == type && label.equals(candidate.getLabel())),
                () -> "unexpected candidate " + type + ":" + label);
    }

    private void assertFunctionCandidate(String sql, String label, String detail, String dataType) {
        SqlCompletionResponse result = complete(sql, sql.length());

        assertSuccessReplacement(result, sql.lastIndexOf(' ') + 1, sql.length());
        Assertions.assertTrue(result.getCandidates().stream()
                .anyMatch(candidate -> candidate.getType() == SqlCompletionCandidateTypeEnum.FUNCTION
                        && label.equals(candidate.getLabel())
                        && callableSnippet(label, functionParameterPlaceholders(detail)).equals(candidate.getInsertText())
                        && SqlCompletionInsertTypeEnum.SNIPPET.equals(candidate.getInsertType())
                        && detail.equals(candidate.getDetail())
                        && dataType.equals(candidate.getDescription())
                        && dataType.equals(candidate.getDataType())));
    }

    private String callableSnippet(String label, List<String> parameterNames) {
        if (parameterNames.isEmpty()) {
            return label + "(${1:})$0";
        }
        StringBuilder builder = new StringBuilder(label).append("(");
        for (int index = 0; index < parameterNames.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("${")
                    .append(index + 1)
                    .append(":")
                    .append(parameterNames.get(index))
                    .append("}");
        }
        return builder.append(")$0").toString();
    }

    private List<String> functionParameterPlaceholders(String detail) {
        String normalized = detail == null ? "" : detail.replaceAll("^\\(|\\)$", "");
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> placeholders = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ']' || ch == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                addFunctionParameterPlaceholder(placeholders, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addFunctionParameterPlaceholder(placeholders, current.toString());
        return placeholders;
    }

    private void addFunctionParameterPlaceholder(List<String> placeholders, String segment) {
        String normalized = segment == null
                ? ""
                : segment.replace("[", "")
                .replace("]", "")
                .replace("{", "")
                .replace("}", "")
                .replace("*", "")
                .trim();
        if (normalized.isBlank()) {
            return;
        }
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > 0) {
            placeholders.add(normalized.substring(0, colonIndex).trim());
            return;
        }
        int spaceIndex = normalized.indexOf(' ');
        placeholders.add(spaceIndex > 0 ? normalized.substring(0, spaceIndex).trim() : normalized);
    }

    private SqlCompletionEditorHint singleEditorHint(SqlCompletionResponse result, SqlCompletionEditorHintTypeEnum type) {
        return result.getEditorHints().stream()
                .filter(hint -> type.equals(hint.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected editor hint " + type));
    }

    private static final class CapturingMetadataProvider implements ISqlCompletionMetadataProvider {

        private boolean emptyCandidates;
        private boolean unsupported;
        private boolean duplicateColumns;
        private boolean schemaSensitiveMetadata;
        private String defaultDatabaseName;
        private int callCount;
        private DbSqlCompletionMetadataRequest lastRequest;
        private final List<DbSqlCompletionMetadataRequest> requests = new ArrayList<>();

        @Override
        public SqlCompletionMetadataResponse list(DbSqlCompletionMetadataRequest request) {
            callCount++;
            lastRequest = request;
            requests.add(request);
            if (unsupported) {
                return SqlCompletionMetadataResponse.unsupported();
            }
            if (emptyCandidates) {
                return SqlCompletionMetadataResponse.of(Collections.emptyList());
            }
            if (SqlCompletionCandidateTypeEnum.COLUMN.name().equals(request.type())) {
                return SqlCompletionMetadataResponse.of(columns(request));
            }
            if (SqlCompletionCandidateTypeEnum.TABLE.name().equals(request.type())
                    || SqlCompletionCandidateTypeEnum.TABLE_VIEW.name().equals(request.type())) {
                return SqlCompletionMetadataResponse.of(tables(request));
            }
            if (SqlCompletionCandidateTypeEnum.DATABASE.name().equals(request.type())) {
                return SqlCompletionMetadataResponse.of(databases(request));
            }
            if (SqlCompletionCandidateTypeEnum.FUNCTION.name().equals(request.type())
                    && "add_".equals(request.prefix())) {
                return SqlCompletionMetadataResponse.of(List.of(function("add_numbers")));
            }
            if (SqlCompletionCandidateTypeEnum.PROCEDURE.name().equals(request.type())
                    && "refresh_".equals(request.prefix())) {
                return SqlCompletionMetadataResponse.of(List.of(procedure("refresh_orders")));
            }
            if (SqlCompletionCandidateTypeEnum.PARAMETER.name().equals(request.type())) {
                return routineParameters(request);
            }
            return SqlCompletionMetadataResponse.of(new ArrayList<>(List.of(
                    SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.from(request.type()),
                            request.prefix() + "_candidate"))));
        }

        private SqlCompletionMetadataResponse routineParameters(DbSqlCompletionMetadataRequest request) {
            if ("add_numbers".equals(request.scope().object())) {
                return SqlCompletionMetadataResponse.of(List.of(
                        parameter("a", "INT", 1, SqlCompletionParameterModeTypeEnum.IN),
                        parameter("b", "INT", 2, SqlCompletionParameterModeTypeEnum.IN)));
            }
            if (SqlCompletionCandidateTypeEnum.PROCEDURE.name().equals(request.objectType())
                    && "add_numbers_proc".equals(request.scope().object())) {
                return SqlCompletionMetadataResponse.of(List.of(
                        parameter("a", "INT", 1, SqlCompletionParameterModeTypeEnum.IN),
                        parameter("b", "INT", 2, SqlCompletionParameterModeTypeEnum.IN),
                        parameter("result", "INT", 3, SqlCompletionParameterModeTypeEnum.OUT)));
            }
            if (SqlCompletionCandidateTypeEnum.PROCEDURE.name().equals(request.objectType())
                    && "mix_proc".equals(request.scope().object())) {
                return SqlCompletionMetadataResponse.of(List.of(
                        parameter("value", "INT", 1, SqlCompletionParameterModeTypeEnum.INOUT)));
            }
            if (SqlCompletionCandidateTypeEnum.PROCEDURE.name().equals(request.objectType())
                    && "echo_proc".equals(request.scope().object())) {
                return SqlCompletionMetadataResponse.of(List.of(
                        parameter("input", "INT", 1, SqlCompletionParameterModeTypeEnum.IN)));
            }
            if (SqlCompletionCandidateTypeEnum.PROCEDURE.name().equals(request.objectType())
                    && "refresh_orders".equals(request.scope().object())) {
                return SqlCompletionMetadataResponse.of(List.of(
                        parameter("tenant_id", "BIGINT", 1, SqlCompletionParameterModeTypeEnum.IN),
                        parameter("force", "BOOLEAN", 2, SqlCompletionParameterModeTypeEnum.IN)));
            }
            return SqlCompletionMetadataResponse.of(Collections.emptyList());
        }

        private List<String> requestTypes() {
            return requests.stream()
                    .map(DbSqlCompletionMetadataRequest::type)
                    .toList();
        }

        private List<DbSqlCompletionMetadataRequest> requestsOf(SqlCompletionCandidateTypeEnum type) {
            return requests.stream()
                    .filter(request -> type.name().equals(request.type()))
                    .toList();
        }

        private List<SqlCompletionCandidate> tables(DbSqlCompletionMetadataRequest request) {
            if (schemaSensitiveMetadata) {
                return schemaSensitiveTables(request);
            }
            return List.of("orders", "order_items", "users", "count_table").stream()
                    .filter(table -> table.startsWith(request.prefix()))
                    .map(table -> {
                        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.TABLE, table);
                        candidate.setTableName(table);
                        return candidate;
                    })
                    .toList();
        }

        private List<SqlCompletionCandidate> schemaSensitiveTables(DbSqlCompletionMetadataRequest request) {
            String databaseName = effectiveDatabaseName(request);
            List<String> tables = switch (String.valueOf(databaseName)) {
                case "db1" -> List.of("db1_orders", "db1_users");
                case "db2" -> List.of("db2_orders", "db2_users");
                default -> List.of("unknown_orders");
            };
            return tables.stream()
                    .filter(table -> table.startsWith(request.prefix()))
                    .map(table -> {
                        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.TABLE, table);
                        candidate.setDatabaseName(databaseName);
                        candidate.setTableName(table);
                        return candidate;
                    })
                    .toList();
        }

        private List<SqlCompletionCandidate> databases(DbSqlCompletionMetadataRequest request) {
            return List.of("app", "archive").stream()
                    .filter(database -> database.startsWith(request.prefix()))
                    .map(database -> {
                        SqlCompletionCandidate candidate = SqlCompletionCandidate.of(
                                SqlCompletionCandidateTypeEnum.DATABASE, database);
                        candidate.setDatabaseName(database);
                        return candidate;
                    })
                    .toList();
        }

        private SqlCompletionCandidate function(String name) {
            SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.FUNCTION, name);
            candidate.setObjectName(name);
            return candidate;
        }

        private SqlCompletionCandidate procedure(String name) {
            SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.PROCEDURE, name);
            candidate.setObjectName(name);
            return candidate;
        }

        private List<SqlCompletionCandidate> columns(DbSqlCompletionMetadataRequest request) {
            if (schemaSensitiveMetadata) {
                return schemaSensitiveColumns(request);
            }
            List<String> columns = switch (String.valueOf(request.scope().table())) {
                case "users" -> List.of("id", "name", "status");
                case "orders" -> duplicateColumns
                        ? List.of("id", "amount", "status", "amount")
                        : List.of("id", "amount", "status");
                case "typed_values" -> List.of("active", "created_at");
                default -> List.of(request.prefix() + "_candidate");
            };
            List<SqlCompletionCandidate> candidates = new ArrayList<>();
            for (int index = 0; index < columns.size(); index++) {
                String column = columns.get(index);
                if (!column.startsWith(request.prefix())) {
                    continue;
                }
                SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, column);
                candidate.setTableName(request.scope().table());
                candidate.setColumnName(column);
                candidate.setDataType(switch (column) {
                    case "active" -> "BOOLEAN";
                    case "created_at" -> "DATETIME";
                    default -> index == 0 ? "BIGINT" : "VARCHAR";
                });
                candidate.setSortRank(index + 1);
                candidates.add(candidate);
            }
            if (candidates.isEmpty()) {
                SqlCompletionCandidate fallback = SqlCompletionCandidate.of(
                        SqlCompletionCandidateTypeEnum.from(request.type()), request.prefix() + "_candidate");
                fallback.setTableName(request.scope().table());
                fallback.setColumnName(request.prefix() + "_candidate");
                candidates.add(fallback);
            }
            return candidates;
        }

        private List<SqlCompletionCandidate> schemaSensitiveColumns(DbSqlCompletionMetadataRequest request) {
            String databaseName = effectiveDatabaseName(request);
            List<String> columns = switch (String.valueOf(databaseName)) {
                case "db1" -> List.of("db1_id", "db1_status");
                case "db2" -> List.of("db2_id", "db2_status");
                default -> List.of("unknown_status");
            };
            List<SqlCompletionCandidate> candidates = new ArrayList<>();
            for (int index = 0; index < columns.size(); index++) {
                String column = columns.get(index);
                if (!column.startsWith(request.prefix())) {
                    continue;
                }
                SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.COLUMN, column);
                candidate.setDatabaseName(databaseName);
                candidate.setTableName(request.scope().table());
                candidate.setColumnName(column);
                candidate.setDataType(index == 0 ? "BIGINT" : "VARCHAR");
                candidate.setSortRank(index + 1);
                candidates.add(candidate);
            }
            return candidates;
        }

        private String effectiveDatabaseName(DbSqlCompletionMetadataRequest request) {
            String schema = request.scope().schema();
            if (schema != null && !schema.isBlank()) {
                return schema;
            }
            return defaultDatabaseName;
        }

        private SqlCompletionCandidate parameter(String name,
                                                 String type,
                                                 int sortRank,
                                                 SqlCompletionParameterModeTypeEnum parameterMode) {
            SqlCompletionCandidate candidate = SqlCompletionCandidate.of(SqlCompletionCandidateTypeEnum.PARAMETER, name);
            candidate.setColumnName(name);
            candidate.setDataType(type);
            candidate.setDetail(type);
            candidate.setSortRank(sortRank);
            candidate.setParameterMode(parameterMode);
            return candidate;
        }
    }
}
