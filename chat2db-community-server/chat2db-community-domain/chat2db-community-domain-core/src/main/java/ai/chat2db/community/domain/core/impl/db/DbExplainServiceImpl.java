package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;

@Service
public class DbExplainServiceImpl implements IDbExplainService {

    @Override
    public String explainJson(String sql) {
        Connection connection = Chat2DBContext.getConnection();
        String explainSql = "EXPLAIN FORMAT=JSON " + sql;
        return DefaultSQLExecutor.getInstance().execute(connection, explainSql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString("EXPLAIN");
            }
            return null;
        });
    }

    @Override
    public String explainAnalyze(String sql) {
        Connection connection = Chat2DBContext.getConnection();
        String explainSql = "EXPLAIN ANALYZE " + sql;
        return DefaultSQLExecutor.getInstance().execute(connection, explainSql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString("EXPLAIN");
            }
            return null;
        });
    }
}
