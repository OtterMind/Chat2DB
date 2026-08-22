package ai.chat2db.community.domain.api.service.db;

/**
 * Exposes SQL execution plan inspection contracts.
 */
public interface IDbExplainService {

    /**
     * Returns the EXPLAIN FORMAT=JSON output for a SQL statement.
     *
     * @param sql the SQL statement to explain.
     * @return raw JSON output from EXPLAIN FORMAT=JSON, or null on error.
     */
    String explainJson(String sql);

    /**
     * Returns the EXPLAIN ANALYZE output for a SQL statement (MySQL 8.0.18+).
     *
     * @param sql the SQL statement to analyze.
     * @return raw output from EXPLAIN ANALYZE, or null on error or unsupported version.
     */
    String explainAnalyze(String sql);
}
