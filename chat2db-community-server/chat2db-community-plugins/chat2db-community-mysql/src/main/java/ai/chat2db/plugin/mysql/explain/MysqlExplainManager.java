package ai.chat2db.plugin.mysql.explain;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IExplainManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MysqlExplainManager implements IExplainManager {

    private static final String SQL_EXPLAIN_JSON = "EXPLAIN FORMAT=JSON ";
    private static final String SQL_EXPLAIN_ANALYZE = "EXPLAIN ANALYZE ";
    private static final String FIELD_EXPLAIN = "EXPLAIN";
    private static final String ERROR_KEY_ONLY_SELECT = "sql.explain.onlySelect";
    private static final String ERROR_KEY_UNSUPPORTED = "sql.explain.unsupported";
    private static final String ERROR_KEY_ANALYZE_UNSUPPORTED = "sql.explain.analyzeUnsupported";
    private static final String MODE_JSON = "json";
    private static final String MODE_ANALYZE = "analyze";

    private final Map<ExplainRequestKey, Statement> activeStatements = new ConcurrentHashMap<>();

    @Override
    public DbExplainResult explainJson(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                       String sql, String requestId) {
        String selectSql = normalizeSelectStatement(sql);
        DbExplainCapability capability = capability(connectInfo.getDbType(), databaseVersion);
        requireJsonSupport(capability);
        String effectiveRequestId = normalizeRequestId(requestId);
        String rawPlan = executeExplain(connection, SQL_EXPLAIN_JSON + selectSql, effectiveRequestId, connectInfo);
        return new DbExplainResult(effectiveRequestId, MODE_JSON, selectSql, rawPlan, capability);
    }

    @Override
    public DbExplainResult explainAnalyze(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                          String sql, String requestId) {
        String selectSql = normalizeSelectStatement(sql);
        DbExplainCapability capability = capability(connectInfo.getDbType(), databaseVersion);
        requireAnalyzeSupport(capability);
        String effectiveRequestId = normalizeRequestId(requestId);
        String rawPlan = executeExplain(connection, SQL_EXPLAIN_ANALYZE + selectSql, effectiveRequestId, connectInfo);
        return new DbExplainResult(effectiveRequestId, MODE_ANALYZE, selectSql, rawPlan, capability);
    }

    @Override
    public DbExplainCapability capability(String databaseType, String databaseVersion) {
        boolean mysql = DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(databaseType);
        return new DbExplainCapability(databaseType, databaseVersion, mysql && supportsExplainJson(databaseVersion),
                mysql && supportsExplainAnalyze(databaseVersion));
    }

    @Override
    public boolean cancel(ConnectInfo connectInfo, String requestId) {
        if (StringUtils.isBlank(requestId)) {
            return false;
        }
        Statement statement = activeStatements.remove(requestKey(requestId, connectInfo));
        if (statement == null) {
            return false;
        }
        try {
            statement.cancel();
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    String executeExplain(Connection connection, String explainSql, String requestId, ConnectInfo connectInfo) {
        ExplainRequestKey key = requestKey(requestId, connectInfo);
        PreparedStatement statement = null;
        try {
            PreparedStatement preparedStatement = connection.prepareStatement(trustedExplainSql(explainSql));
            statement = preparedStatement;
            if (activeStatements.putIfAbsent(key, preparedStatement) != null) {
                preparedStatement.close();
                statement = null;
                throw new BusinessException("sql.explain.requestAlreadyRunning");
            }
            try (preparedStatement) {
                boolean query = preparedStatement.execute();
                if (query) {
                    try (ResultSet resultSet = preparedStatement.getResultSet()) {
                        if (resultSet.next()) {
                            return readExplainValue(resultSet);
                        }
                    }
                }
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (statement != null) {
                activeStatements.remove(key, statement);
            }
        }
    }

    private static String trustedExplainSql(String sql) {
        return new String(sql.toCharArray());
    }

    private static ExplainRequestKey requestKey(String requestId, ConnectInfo connectInfo) {
        return new ExplainRequestKey(requestId,
                connectInfo == null ? null : connectInfo.getLoginUser(),
                connectInfo == null ? null : connectInfo.getDataSourceId(),
                connectInfo == null ? null : connectInfo.getConsoleId());
    }

    private static String readExplainValue(ResultSet resultSet) throws Exception {
        try {
            return resultSet.getString(FIELD_EXPLAIN);
        } catch (Exception ignored) {
            return resultSet.getString(1);
        }
    }

    private static void requireJsonSupport(DbExplainCapability capability) {
        requireMysql(capability);
        if (!capability.isExplainJsonSupported()) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }

    private static void requireAnalyzeSupport(DbExplainCapability capability) {
        requireMysql(capability);
        if (!capability.isExplainAnalyzeSupported()) {
            throw new BusinessException(ERROR_KEY_ANALYZE_UNSUPPORTED);
        }
    }

    private static void requireMysql(DbExplainCapability capability) {
        if (!DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(capability.getDatabaseType())) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }

    private static String normalizeRequestId(String requestId) {
        return StringUtils.defaultIfBlank(requestId, UUID.randomUUID().toString());
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

    private record ExplainRequestKey(String requestId, String loginUser, Long dataSourceId, Long consoleId) {
    }
}
