package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;

@Service
public class DbExplainServiceImpl implements IDbExplainService {

    private static final String SQL_EXPLAIN_JSON = "EXPLAIN FORMAT=JSON ";
    private static final String SQL_EXPLAIN_ANALYZE = "EXPLAIN ANALYZE ";
    private static final String FIELD_EXPLAIN = "EXPLAIN";
    private static final String ERROR_KEY_ONLY_SELECT = "sql.explain.onlySelect";

    @Override
    public String explainJson(String sql) {
        requireSelectStatement(sql);
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
        requireSelectStatement(sql);
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_EXPLAIN_ANALYZE + sql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(FIELD_EXPLAIN);
            }
            return null;
        });
    }

    /**
     * MySQL 8.0 EXPLAIN also covers UPDATE/INSERT/DELETE and actually executes those
     * statements; EXPLAIN ANALYZE always executes. Only accept SELECT so the explain
     * endpoints can never mutate data.
     */
    private static void requireSelectStatement(String sql) {
        if (StringUtils.isBlank(sql) || !sql.trim().regionMatches(true, 0, "SELECT", 0, 6)) {
            throw new BusinessException(ERROR_KEY_ONLY_SELECT);
        }
    }
}
