package ai.chat2db.plugin.bigquery.parser;

import ai.chat2db.mysql.parser.base.MySqlLexer;
import ai.chat2db.spi.parser.dialect.AbstractSQLDialect;

import java.util.Set;

/**
 * BigQuery dialect rules driving token-based statement splitting and keyword handling.
 * Lexing/parsing is delegated to a grammar whose lexer, like BigQuery, treats
 * backtick-quoted text (e.g. `my-project.dataset.table`) as a single identifier.
 * BigQuery has no client-side DELIMITER statement, so the set-delimiter set is
 * intentionally left empty (unlike the MySQL dialect).
 */
public class BigQueryDialect extends AbstractSQLDialect {

    private static final Set<Integer> BIGQUERY_COMMENT_TOKENS =
            Set.of(MySqlLexer.SPEC_MYSQL_COMMENT, MySqlLexer.COMMENT_INPUT, MySqlLexer.LINE_COMMENT);

    private static final Set<String> BIGQUERY_SQL_START_KEYWORDS = Set.of(
            ";", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "DROP", "ALTER",
            "WITH", "TRUNCATE", "GRANT", "REVOKE", "CALL", "BEGIN", "COMMIT", "ROLLBACK",
            "DECLARE", "SET", "EXPORT", "LOAD", "EXPLAIN", "ANALYZE"
    );

    @Override
    public Set<Integer> getCommentTokens() {
        return BIGQUERY_COMMENT_TOKENS;
    }

    @Override
    public boolean isComment(int tokenType) {
        return getCommentTokens().contains(tokenType);
    }

    @Override
    public Set<String> getSqlStartKeywords() {
        return BIGQUERY_SQL_START_KEYWORDS;
    }
}
