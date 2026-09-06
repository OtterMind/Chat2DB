package ai.chat2db.spi;

import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.spi.model.datasource.ConnectInfo;

import java.sql.Connection;

/**
 * Provides database-specific execution plan inspection.
 */
public interface IExplainManager {

    DbExplainResult explainJson(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                String sql, String requestId);

    DbExplainResult explainAnalyze(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                   String sql, String requestId);

    DbExplainCapability capability(String databaseType, String databaseVersion);

    boolean cancel(ConnectInfo connectInfo, String requestId);
}
