package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;

/**
 * Exposes SQL execution plan inspection contracts.
 */
public interface IDbExplainService {

    /**
     * Returns the EXPLAIN FORMAT=JSON output for a SQL statement.
     *
     * @param sql the SQL statement to explain.
     * @param requestId frontend-owned request id used for cancellation.
     * @return raw JSON output plus capability metadata.
     */
    DbExplainResult explainJson(String sql, String requestId);

    /**
     * Returns the EXPLAIN ANALYZE output for a SQL statement (MySQL 8.0.18+).
     *
     * @param sql the SQL statement to analyze.
     * @param requestId frontend-owned request id used for cancellation.
     * @return raw output plus capability metadata.
     */
    DbExplainResult explainAnalyze(String sql, String requestId);

    /**
     * Returns EXPLAIN feature support for the current connection.
     */
    DbExplainCapability capability();

    /**
     * Cancels an active EXPLAIN/ANALYZE request by request id.
     *
     * @param requestId frontend-owned request id.
     * @return true when a running JDBC statement was found and cancellation was requested.
     */
    boolean cancel(String requestId);
}
