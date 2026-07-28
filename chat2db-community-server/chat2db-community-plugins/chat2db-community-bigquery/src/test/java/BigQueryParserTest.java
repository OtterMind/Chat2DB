import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.plugin.bigquery.parser.BigQueryDialect;
import ai.chat2db.plugin.bigquery.parser.BigQueryParser;
import ai.chat2db.community.domain.api.model.parser.statement.Statement;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Regression tests for the BigQuery parser/dialect: backtick-quoted identifiers such as
 * `my-project.dataset.table` must lex as single identifiers and must not break statement
 * splitting (previously the parser reused the PostgreSQL grammar, whose lexer has no backtick
 * identifier rule).
 */
class BigQueryParserTest {

    private final BigQueryParser parser = new BigQueryParser();

    @Test
    void backtickQuotedIdentifierIsTokenizedAsSingleIdentifier() {
        List<Token> tokens = parser.getAllTokensOnDefault("SELECT * FROM `my-project.dataset.table`");
        Assertions.assertTrue(
                tokens.stream().anyMatch(t -> "`my-project.dataset.table`".equals(t.getText())),
                "backtick-quoted identifier should be a single token: " + tokens);
    }

    @Test
    void splitsScriptAroundBacktickQuotedIdentifiers() {
        List<Statement> statements = parser.parserSqlScript("""
                SELECT * FROM `my-project.dataset.table` WHERE id = 1;
                SELECT * FROM `other-project.dataset.other_table`;
                """);
        Assertions.assertEquals(2, statements.size());
        Assertions.assertTrue(statements.get(0).getSql().contains("`my-project.dataset.table`"));
        Assertions.assertTrue(statements.get(1).getSql().contains("`other-project.dataset.other_table`"));
    }

    @Test
    void semicolonInsideBacktickQuotedIdentifierDoesNotSplit() {
        List<Statement> statements = parser.parserSqlScript(
                "SELECT * FROM `my-proj;ect.dataset.table`;\nSELECT 1;");
        Assertions.assertEquals(2, statements.size());
        Assertions.assertTrue(statements.get(0).getSql().contains("`my-proj;ect.dataset.table`"));
    }

    @Test
    void isSelectRecognizesBacktickQuotedTable() {
        Assertions.assertTrue(parser.isSelect("SELECT * FROM `my-project.dataset.table`"));
    }

    @Test
    void beginEndScriptingBlockStaysOneStatement() {
        List<Statement> statements = parser.parserSqlScript("""
                BEGIN
                  SELECT 1;
                  SELECT 2;
                END;
                SELECT 3;
                """);
        Assertions.assertEquals(2, statements.size());
        Assertions.assertTrue(statements.get(0).getSql().contains("BEGIN"));
        Assertions.assertTrue(statements.get(0).getSql().contains("END"));
    }

    @Test
    void dialectHasNoClientDelimiterStatement() {
        // DELIMITER is a MySQL client-ism; BigQuery scripts must not treat it as a delimiter change.
        Assertions.assertFalse(new BigQueryDialect().isSetDelimiter("DELIMITER"));
    }

    @Test
    void dialectRecognizesCommentTokens() {
        BigQueryDialect dialect = new BigQueryDialect();
        Assertions.assertTrue(dialect.isComment(MySqlLexer.LINE_COMMENT));
        Assertions.assertTrue(dialect.isComment(MySqlLexer.COMMENT_INPUT));
        Assertions.assertFalse(dialect.isComment(MySqlLexer.SELECT));
    }
}
