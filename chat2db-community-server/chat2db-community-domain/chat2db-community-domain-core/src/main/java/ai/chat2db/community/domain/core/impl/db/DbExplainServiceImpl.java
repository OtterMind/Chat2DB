package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;

@Service
public class DbExplainServiceImpl implements IDbExplainService {

    private static final String SQL_EXPLAIN_JSON = "EXPLAIN FORMAT=JSON ";
    private static final String SQL_EXPLAIN_ANALYZE = "EXPLAIN ANALYZE ";
    private static final String FIELD_EXPLAIN = "EXPLAIN";

    @Override
    public String explainJson(String sql) {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_EXPLAIN_JSON + sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(FIELD_EXPLAIN);
            }
            return null;
        });
    }

    @Override
    public String explainAnalyze(String sql) {
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_EXPLAIN_ANALYZE + sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(FIELD_EXPLAIN);
            }
            return null;
        });
    }
}
