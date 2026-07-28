import ai.chat2db.plugin.bigquery.parser.BigQueryParser;
import ai.chat2db.community.domain.api.model.parser.result.SqlParserResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BigQueryCompletionSyntaxCoverageTest {

    private final BigQueryParser parser = new BigQueryParser();

    @Test
    void coversStandardSyntaxThroughBigQueryParser() {
        assertParses("""
                COMMIT;
                ROLLBACK;
                EXPLAIN SELECT * FROM events;
                CALL refresh_stats();
                WITH daily AS (SELECT user_id, COUNT(*) AS cnt FROM events GROUP BY user_id)
                SELECT user_id, cnt, ROW_NUMBER() OVER (ORDER BY cnt DESC) rn FROM daily;
                """);
    }

    @Test
    void parsesBacktickQuotedIdentifiers() {
        assertParses("SELECT * FROM `my-project.dataset.table` WHERE id = 1;");
    }

    @Test
    void documentsBigQueryNativeSyntaxOutsideUnderlyingGrammarBoundary() {
        // MERGE is valid BigQuery but has no equivalent in the borrowed grammar's statement set.
        assertDoesNotParse("""
                MERGE INTO target_table t
                USING source_table s
                ON t.id = s.id
                WHEN MATCHED THEN UPDATE SET name = s.name;
                """);
    }

    private void assertParses(String sql) {
        SqlParserResponse result = parser.parserStatements(sql);
        Assertions.assertTrue(result.getSyntaxErrors().isEmpty(), result.getSyntaxErrors().toString());
    }

    private void assertDoesNotParse(String sql) {
        SqlParserResponse result = parser.parserStatements(sql);
        Assertions.assertFalse(result.getSyntaxErrors().isEmpty());
    }
}
