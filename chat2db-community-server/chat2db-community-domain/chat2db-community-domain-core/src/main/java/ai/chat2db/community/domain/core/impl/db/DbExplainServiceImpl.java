package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbExplainService;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.List;

@Service
public class DbExplainServiceImpl implements IDbExplainService {

    private static final String SQL_EXPLAIN_JSON = "EXPLAIN FORMAT=JSON ";
    private static final String SQL_EXPLAIN_ANALYZE = "EXPLAIN ANALYZE ";
    private static final String FIELD_EXPLAIN = "EXPLAIN";
    private static final String ERROR_KEY_ONLY_SELECT = "sql.explain.onlySelect";
    private static final String ERROR_KEY_UNSUPPORTED = "sql.explain.unsupported";
    private static final String ERROR_KEY_ANALYZE_UNSUPPORTED = "sql.explain.analyzeUnsupported";

    @Override
    public String explainJson(String sql) {
        String selectSql = normalizeSelectStatement(sql);
        requireJsonSupport();
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_EXPLAIN_JSON + selectSql, resultSet -> {
            if (resultSet.next()) {
                return resultSet.getString(FIELD_EXPLAIN);
            }
            return null;
        });
    }

    @Override
    public String explainAnalyze(String sql) {
        String selectSql = normalizeSelectStatement(sql);
        requireAnalyzeSupport();
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_EXPLAIN_ANALYZE + selectSql, resultSet -> {
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
    static boolean isSingleSelectStatement(String sql) {
        return parseSingleSelectStatement(sql) != null;
    }

    private static String normalizeSelectStatement(String sql) {
        SQLSelectStatement statement = parseSingleSelectStatement(sql);
        if (statement == null) {
            throw new BusinessException(ERROR_KEY_ONLY_SELECT);
        }
        return SQLUtils.toMySqlString(statement);
    }

    private static SQLSelectStatement parseSingleSelectStatement(String sql) {
        if (StringUtils.isBlank(sql)) {
            return null;
        }
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, DbType.mysql);
            if (statements.size() == 1 && statements.get(0) instanceof SQLSelectStatement selectStatement) {
                return selectStatement;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean supportsExplainJson(String version) {
        return isAtLeast(version, 5, 7, 0);
    }

    static boolean supportsExplainAnalyze(String version) {
        return isAtLeast(version, 8, 0, 18);
    }

    private static void requireJsonSupport() {
        requireMysql();
        if (!supportsExplainJson(Chat2DBContext.getDbVersion())) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }

    private static void requireAnalyzeSupport() {
        requireMysql();
        if (!supportsExplainAnalyze(Chat2DBContext.getDbVersion())) {
            throw new BusinessException(ERROR_KEY_ANALYZE_UNSUPPORTED);
        }
    }

    private static void requireMysql() {
        String dbType = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
        if (!DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType)) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }

    private static boolean isAtLeast(String version, int requiredMajor, int requiredMinor, int requiredPatch) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.-]");
        if (parts.length < 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return major > requiredMajor
                    || major == requiredMajor && (minor > requiredMinor
                    || minor == requiredMinor && patch >= requiredPatch);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
