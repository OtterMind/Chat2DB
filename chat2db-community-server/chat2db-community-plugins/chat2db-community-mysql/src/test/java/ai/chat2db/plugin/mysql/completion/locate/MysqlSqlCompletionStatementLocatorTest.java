package ai.chat2db.plugin.mysql.completion.locate;

import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionInputCleaner;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionStatementWindowTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionStatementWindow;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MysqlSqlCompletionStatementLocatorTest {

    private final MysqlSqlCompletionStatementLocator locator = new MysqlSqlCompletionStatementLocator();

    @Test
    void rawParserKeepsCompleteStatement() {
        String sql = "select * from orders where status = 'PAID'";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void selectsSqlAfterChinesePlainText() {
        String sql = "aaaascasca 你哈 实打实打你i  select * from orders where na";
        int start = sql.indexOf("select");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from orders where na", start, sql.length());
    }

    @Test
    void selectsSqlAfterLogPrefix() {
        String sql = "2026-06-08 18:50:01.123 INFO sql=select * from orders where na";
        int start = sql.indexOf("select");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from orders where na", start, sql.length());
    }

    @Test
    void selectsCurrentStatementAfterSemicolonAndPlainText() {
        String sql = "select * from old_orders; random trace hello select * from orders where na";
        int start = sql.lastIndexOf("select");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from orders where na", start, sql.length());
    }

    @Test
    void keepsCursorBeforeSeparatorInPreviousStatement() {
        String sql = "select * from orders where na; select * from users";
        int cursor = sql.indexOf(";");

        SqlCompletionStatementWindow window = locate(sql, cursor);

        assertCurrentWindow(window, "select * from orders where na", 0, cursor);
        Assertions.assertEquals(cursor, window.cursor());
    }

    @Test
    void returnsEmptyWindowImmediatelyAfterSeparator() {
        String sql = "select * from orders; select * from users";
        int cursor = sql.indexOf(";") + 1;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name(), window.type());
        Assertions.assertEquals(cursor, window.sourceStartOffset());
        Assertions.assertEquals(cursor, window.sourceEndOffset());
        Assertions.assertEquals("", window.sourceSql());
    }

    @Test
    void returnsEmptyStatementAfterSeparatorWhitespace() {
        String sql = "select * from orders;   ";

        SqlCompletionStatementWindow window = locate(sql);

        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.EMPTY_STATEMENT.name(), window.type());
        Assertions.assertEquals(sql.length() - 3, window.sourceStartOffset());
        Assertions.assertTrue(window.sourceSql().isBlank());
    }

    @Test
    void selectsNextStatementWhenCursorIsInsideNextStatement() {
        String sql = "select * from orders; select * from users where na";
        int start = sql.lastIndexOf("select");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", start, sql.length());
    }

    @Test
    void probeSelectsNextStatementWithoutSemicolon() {
        String sql = "select * from orders where status = 'PAID'\nselect * from users where na";
        int start = sql.lastIndexOf("select");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", start, sql.length());
    }

    @Test
    void probeSelectsUpdateStatementWithoutSemicolonAfterPreviousSelect() {
        String sql = "select * from orders where status = 'PAID'\nupdate users set name = na";
        int start = sql.indexOf("update");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "update users set name = na", start, sql.length());
    }

    @Test
    void keepsWithClauseAndMainSelectInSameWindowAcrossNewlines() {
        String sql = """
                WITH paid_orders as(
                SELECT * FROM orders
                WHERE status='paid'
                )
                SELECT * from pa""";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void keepsWithClauseWhenMainSelectPrefixIsMidToken() {
        String sql = """
                WITH paid_orders AS (
                  SELECT * FROM orders WHERE status = 'paid'
                )
                SELECT * FROM paid_orders pa
                WHERE pa.sta""";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void doesNotSplitNestedSelectWithoutStatementSeparator() {
        String sql = "select * from orders where exists (select 1 from users where na)";
        int cursor = sql.indexOf("na") + 2;

        SqlCompletionStatementWindow window = locate(sql, cursor);

        assertCurrentWindow(window, sql, 0, sql.length(), cursor);
    }

    @Test
    void ignoresSemicolonInsideStringLiteral() {
        String sql = "select ';' as semi, * from orders where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void ignoresSemicolonInsideBlockComment() {
        String sql = "select * from orders /* keep ; here */ where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void ignoresSemicolonInsideLineCommentBeforeCursor() {
        String sql = "select * from orders -- keep ; here\nwhere na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void keepsNestedSelectInsideSingleStatementOnTheFastPath() {
        String sql = "select * from orders where id in (select id from users where users.id = 1) and na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void keepsUnionQueryTogetherWhenTopLevelSelectsRepeat() {
        String sql = "select id from orders where na union select id from archived_orders where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void keepsInsertSelectTogetherWhenTopLevelStatementKeywordsRepeat() {
        String sql = "insert into orders(id) select id from users where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void preservesCurrentWindowWhenLargeHistoryCursorIsBeforeSeparator() {
        StringBuilder sqlBuilder = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            sqlBuilder.append("select * from archive_").append(index).append(";\n");
        }
        int currentStart = sqlBuilder.length();
        sqlBuilder.append("select * from orders where na; select * from next_table");
        String sql = sqlBuilder.toString();
        int cursor = currentStart + "select * from orders where na".length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        assertCurrentWindow(window, "select * from orders where na", currentStart, cursor);
    }

    @Test
    void fallsBackToParserForUnclosedParentheses() {
        String sql = "select coalesce(o.sta from app.orders o";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, sql, 0, sql.length());
    }

    @Test
    void fallsBackToParserForUnclosedStringLiteral() {
        String sql = "select * from orders where name = 'unterminated; select * from users where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", sql.lastIndexOf("select * from users"),
                sql.length());
    }

    @Test
    void fallsBackToParserForUnclosedBlockComment() {
        String sql = "select * from orders /* unterminated; select * from users where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", sql.lastIndexOf("select * from users"),
                sql.length());
    }

    @Test
    void keepsQuestionMarkParametersEligibleForTheFastPath() {
        String sql = "select * from orders where id = ?; select * from users where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", sql.lastIndexOf("select * from users"),
                sql.length());
    }

    @Test
    void doesNotUseFastPathForUnmatchedClosingParenthesis() {
        String sql = "select * from orders where id = 1); select * from users where na";

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", sql.lastIndexOf("select * from users"),
                sql.length());
    }

    @Test
    void keepsLargeScriptCurrentWindowBoundedByStatementSeparator() {
        StringBuilder sqlBuilder = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            sqlBuilder.append("select * from archive_").append(index).append(";\n");
        }
        int currentStart = sqlBuilder.length();
        sqlBuilder.append("trace prefix select * from orders where na");
        sqlBuilder.append(";\nselect * from next_table");
        String sql = sqlBuilder.toString();
        int cursor = currentStart + "trace prefix select * from orders where na".length();
        int start = currentStart + "trace prefix ".length();

        SqlCompletionStatementWindow window = locate(sql, cursor);

        assertCurrentWindow(window, "select * from orders where na", start, cursor);
    }

    @Test
    void keepsProcedureBodyInternalSemicolonsInRoutineWindow() {
        String sql = "create procedure sync_orders()\n"
                + "begin\n"
                + "  select * from orders where na;\n"
                + "  update orders set status = 'DONE';\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("na") + 2;
        int routineEnd = sql.indexOf(";\nselect * from users");

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(routineEnd, window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().startsWith("create procedure sync_orders()"));
        Assertions.assertTrue(window.sourceSql().contains("update orders set status = 'DONE';"));
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void keepsTriggerBodyInternalSemicolonsInRoutineWindow() {
        String sql = "create trigger before_orders_update before update on orders\n"
                + "for each row\n"
                + "begin\n"
                + "  set new.updated_by = user();\n"
                + "  select new.updated_by;\n"
                + "end;\n"
                + "select * from users";
        int cursor = sql.indexOf("updated_by =") + "updated_by".length();
        int routineEnd = sql.indexOf(";\nselect * from users");

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(routineEnd, window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().startsWith("create trigger before_orders_update"));
        Assertions.assertTrue(window.sourceSql().contains("select new.updated_by;"));
    }

    @Test
    void keepsFunctionWithNestedControlBlocksInRoutineWindow() {
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
        int routineEnd = sql.indexOf(";\nselect * from users");

        SqlCompletionStatementWindow window = locate(sql, cursor);

        Assertions.assertEquals(0, window.sourceStartOffset());
        Assertions.assertEquals(routineEnd, window.sourceEndOffset());
        Assertions.assertTrue(window.sourceSql().contains("end if;"));
        Assertions.assertTrue(window.sourceSql().contains("end case;"));
        Assertions.assertEquals(cursor, window.sourceCursor());
    }

    @Test
    void selectsStatementAfterNestedRoutineWindow() {
        String sql = "create procedure outer_sync()\n"
                + "begin\n"
                + "  create procedure inner_sync()\n"
                + "  begin\n"
                + "    select * from orders;\n"
                + "  end;\n"
                + "end;\n"
                + "select * from users where na";
        int start = sql.lastIndexOf("select * from users");

        SqlCompletionStatementWindow window = locate(sql);

        assertCurrentWindow(window, "select * from users where na", start, sql.length());
    }

    private SqlCompletionStatementWindow locate(String sql) {
        return locate(sql, sql.length());
    }

    private SqlCompletionStatementWindow locate(String sql, int cursor) {
        return locator.locate(MysqlSqlCompletionInputCleaner.clean(sql, cursor));
    }

    private void assertCurrentWindow(SqlCompletionStatementWindow window, String sourceSql, int start, int end) {
        assertCurrentWindow(window, sourceSql, start, end, end);
    }

    private void assertCurrentWindow(SqlCompletionStatementWindow window,
                                     String sourceSql,
                                     int start,
                                     int end,
                                     int sourceCursor) {
        Assertions.assertEquals(SqlCompletionStatementWindowTypeEnum.CURRENT_STATEMENT.name(), window.type());
        Assertions.assertEquals(start, window.sourceStartOffset());
        Assertions.assertEquals(end, window.sourceEndOffset());
        Assertions.assertEquals(sourceSql, window.sourceSql());
        Assertions.assertEquals(sourceCursor, window.sourceCursor());
        Assertions.assertEquals(sourceCursor - start, window.cursor());
    }
}
