package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import ai.chat2db.spi.ISQLParser;
import com.alibaba.druid.DbType;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlUtilsTest {

    @Test
    void countTrimsGeneratedSqlSemicolonWithoutTruncatingCountSql() {
        assertEquals("SELECT COUNT(*) FROM users", SqlUtils.count("SELECT * FROM users;", "mysql"));
    }

    @Test
    void trimTrailingSemicolonUsesInputSqlLength() {
        assertEquals("SELECT COUNT(*) FROM users",
                SqlUtils.trimTrailingSemicolon("SELECT COUNT(*) FROM users;"));
    }

    @Test
    void updateNowRewritesColumnDefaultInDdl() throws Exception {
        assertEquals("CREATE TABLE t (a DATETIME default CURRENT_TIMESTAMP);",
                updateNow("CREATE TABLE t (a DATETIME DEFAULT now());"));
        assertEquals("CREATE TABLE t (a DATETIME default CURRENT_TIMESTAMP);",
                updateNow("CREATE TABLE t (a DATETIME default now ());"));
    }

    @Test
    void updateNowLeavesQuotedStringLiteralsUntouched() throws Exception {
        String singleQuoted = "INSERT INTO t (a) VALUES ('default now()');";
        assertEquals(singleQuoted, updateNow(singleQuoted));

        String escapedQuote = "INSERT INTO t (a) VALUES ('it''s default now()');";
        assertEquals(escapedQuote, updateNow(escapedQuote));

        String doubleQuoted = "INSERT INTO t (a) VALUES (\"DEFAULT now ()\");";
        assertEquals(doubleQuoted, updateNow(doubleQuoted));
    }

    @Test
    void updateNowLeavesCommentsAndLongerIdentifiersUntouched() throws Exception {
        String sql = """
                -- default now()
                # DEFAULT now ()
                /* default now() */
                SELECT nodefault now();
                """;

        assertEquals(sql, updateNow(sql));
    }

    @Test
    void updateNowAcceptsSqlWhitespaceAroundFunctionTokens() throws Exception {
        assertEquals("x default CURRENT_TIMESTAMP",
                updateNow("x default\t now ( \n )"));
    }

    @Test
    void updateNowEmitsConsistentCasingAcrossVariants() throws Exception {
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x DEFAULT now()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x default now()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x DEFAULT now ()"));
        assertEquals("x default CURRENT_TIMESTAMP", updateNow("x default now ()"));
    }

    private static String updateNow(String sql) throws Exception {
        Method method = SqlUtils.class.getDeclaredMethod("updateNow", String.class, DbType.class);
        method.setAccessible(true);
        return (String) method.invoke(null, sql, DbType.mysql);
    }

    @Test
    void restoreStatementSqlCopiesPlaceholdersBackFromTheOriginalScript() {
        String script = "select 1;\nselect name from books where id = :id and slug = ?;";
        String masked = SqlParameterParser.maskPlaceholdersAsLiterals(script, SqlParameterSyntax.MYSQL);
        Statement first = statement(masked, 0, "select 1".length());
        int secondStart = script.indexOf("select name");
        Statement second = statement(masked, secondStart, script.length() - 1);

        assertTrue(SqlUtils.restoreStatementSql(List.of(first, second), script, masked, SqlParameterSyntax.MYSQL));

        assertEquals("select 1", first.getSql());
        assertEquals("select name from books where id = :id and slug = ?", second.getSql());
    }

    @Test
    void restoreStatementSqlRejectsStatementsItCannotLocate() {
        String script = "select name from books where id = :id";
        String masked = SqlParameterParser.maskPlaceholdersAsLiterals(script, SqlParameterSyntax.MYSQL);
        Statement statement = statement(masked, 0, script.length());
        statement.setSql("select name from books");

        assertFalse(SqlUtils.restoreStatementSql(List.of(statement), script, masked, SqlParameterSyntax.MYSQL));
    }

    @Test
    void restoreStatementSqlRejectsRangesThatCutAPlaceholderShort() {
        String script = "select name from books where id = :id";
        String masked = SqlParameterParser.maskPlaceholdersAsLiterals(script, SqlParameterSyntax.MYSQL);
        // A range ending inside the masked placeholder would restore "... id = :".
        Statement statement = statement(masked, 0, script.indexOf(":id") + 1);

        assertFalse(SqlUtils.restoreStatementSql(List.of(statement), script, masked, SqlParameterSyntax.MYSQL));
    }

    @Test
    void parserThatRejectsPlaceholdersStillFindsTheStatementAsWritten() {
        String script = "select name from books where id = :id and slug = ?";
        List<String> parsed = new ArrayList<>();

        List<Statement> statements = SqlUtils.simpleParserStatementsAllowingParameters(
                grammarWithoutPlaceholders(parsed), script, "MYSQL");

        assertEquals(1, statements.size());
        assertEquals(script, statements.get(0).getSql());
        assertEquals(0, statements.get(0).getFirstToken().getStartIndex(), "positions are those of the original text");
        assertEquals(List.of(script, "select name from books where id = 000 and slug = 0"), parsed);
    }

    @Test
    void parserFailuresWithoutPlaceholdersAreRethrown() {
        ISQLParser failing = grammarWithoutPlaceholders(new ArrayList<>());

        assertThrows(IllegalStateException.class,
                () -> SqlUtils.simpleParserStatementsAllowingParameters(failing, "select ':id' from", "MYSQL"));
    }

    /**
     * A grammar that fails on {@code :} and {@code ?} outside literals and returns
     * the whole text as one statement otherwise.
     */
    private static ISQLParser grammarWithoutPlaceholders(List<String> parsed) {
        return (ISQLParser) Proxy.newProxyInstance(ISQLParser.class.getClassLoader(), new Class<?>[]{ISQLParser.class},
                (proxy, method, args) -> {
                    if (!"simpleParserStatements".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String sql = (String) args[0];
                    parsed.add(sql);
                    if (sql.replaceAll("'[^']*'", "").matches("(?s).*[:?].*") || sql.endsWith("from")) {
                        throw new IllegalStateException("syntax error");
                    }
                    return SqlParserResponse.builder().statements(List.of(statement(sql, 0, sql.length()))).build();
                });
    }

    private static Statement statement(String maskedScript, int start, int end) {
        Statement statement = new Statement();
        statement.setSql(maskedScript.substring(start, end));
        statement.setFirstToken(token(start, start));
        statement.setLastToken(token(end - 1, end - 1));
        return statement;
    }

    private static Token token(int start, int stop) {
        CommonToken token = new CommonToken(0, "x");
        token.setStartIndex(start);
        token.setStopIndex(stop);
        return token;
    }
}
