package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ISessionManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MysqlSessionManager implements ISessionManager {

    private static final String SQL_SHOW_PROCESSLIST =
            "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO "
                    + "FROM information_schema.processlist ORDER BY ID";
    private static final String SQL_KILL = "KILL";
    private static final String SQL_KILL_QUERY_SUFFIX = " QUERY";
    private static final String SQL_KILL_CONNECTION_PREPARED = "KILL CONNECTION ?";
    private static final String SQL_KILL_QUERY_PREPARED = "KILL QUERY ?";
    private static final int MYSQL_ERROR_UNKNOWN_THREAD_ID = 1094;
    private static final String SQL_CURRENT_CONNECTION_ID = "SELECT CONNECTION_ID()";
    private static final String SQL_PROCESS_OWNER =
            "SELECT USER FROM information_schema.processlist WHERE ID = ?";
    private static final String SQL_CURRENT_GRANTS = "SHOW GRANTS FOR CURRENT_USER";
    private static final String FIELD_CURRENT_CONNECTION_ID = "CONNECTION_ID()";
    private static final String ERROR_KEY_KILL_SELF = "mysql.session.killSelfNotAllowed";
    private static final String ERROR_KEY_KILL_NOT_OWNER = "mysql.session.killNotOwner";
    private static final String ERROR_KEY_UNSUPPORTED = "mysql.session.unsupported";

    @Override
    public List<Map<String, Object>> list(Connection connection, String databaseVersion) {
        requireSupportedMysql(databaseVersion);
        Long currentConnectionId = currentConnectionId(connection);
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_PROCESSLIST, resultSet -> {
            List<Map<String, Object>> sessions = new ArrayList<>();
            while (resultSet.next()) {
                Long connectionId = resultSet.getLong("ID");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", connectionId);
                row.put("user", resultSet.getString("USER"));
                row.put("host", resultSet.getString("HOST"));
                row.put("db", resultSet.getString("DB"));
                row.put("command", resultSet.getString("COMMAND"));
                row.put("time", resultSet.getLong("TIME"));
                row.put("state", resultSet.getString("STATE"));
                row.put("info", resultSet.getString("INFO"));
                row.put("current", Objects.equals(currentConnectionId, connectionId));
                sessions.add(row);
            }
            return sessions;
        });
    }

    @Override
    public DbSessionKillResult kill(Connection connection, String databaseVersion, String connectionUser,
                                    Long connectionId, String killType) {
        requireSupportedMysql(databaseVersion);
        long targetConnectionId = requirePositiveConnectionId(connectionId);
        String normalizedKillType = normalizeKillType(killType);
        String sql = formatKillSql(targetConnectionId, normalizedKillType);
        if (Objects.equals(currentConnectionId(connection), targetConnectionId)) {
            throw new BusinessException(ERROR_KEY_KILL_SELF);
        }

        if (!requireKillAuthorized(connection, connectionUser, targetConnectionId)) {
            return DbSessionKillResult.alreadyFinished(targetConnectionId, normalizedKillType, sql);
        }
        try (PreparedStatement statement = connection.prepareStatement(killPreparedStatementSql(normalizedKillType))) {
            statement.setLong(1, targetConnectionId);
            statement.execute();
            return DbSessionKillResult.killed(targetConnectionId, normalizedKillType, sql);
        } catch (SQLException exception) {
            if (isAlreadyFinishedError(exception)) {
                return DbSessionKillResult.alreadyFinished(targetConnectionId, normalizedKillType, sql);
            }
            throw new BusinessException("mysql.session.killFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    private static boolean requireKillAuthorized(Connection connection, String connectionUser,
                                                 long targetConnectionId) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_PROCESS_OWNER)) {
            statement.setLong(1, targetConnectionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                if (sameMysqlUser(resultSet.getString("USER"), connectionUser)
                        || canKillOtherUserSessions(connection)) {
                    return true;
                }
                throw new BusinessException(ERROR_KEY_KILL_NOT_OWNER);
            }
        } catch (SQLException exception) {
            throw new BusinessException("mysql.session.authorizationCheckFailed",
                    new Object[]{exception.getMessage()}, exception);
        }
    }

    private static Long currentConnectionId(Connection connection) {
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_CURRENT_CONNECTION_ID, resultSet ->
                resultSet.next() ? resultSet.getLong(FIELD_CURRENT_CONNECTION_ID) : null);
    }

    private static String normalizeKillType(String killType) {
        if ("QUERY".equalsIgnoreCase(killType)) {
            return "QUERY";
        }
        if ("CONNECTION".equalsIgnoreCase(killType)) {
            return "CONNECTION";
        }
        throw new BusinessException("mysql.session.invalidKillType");
    }

    private static String formatKillSql(long connectionId, String killType) {
        if ("CONNECTION".equals(killType)) {
            return SQL_KILL + " CONNECTION " + connectionId;
        }
        return SQL_KILL + SQL_KILL_QUERY_SUFFIX + " " + connectionId;
    }

    private static String killPreparedStatementSql(String killType) {
        return "CONNECTION".equals(killType) ? SQL_KILL_CONNECTION_PREPARED : SQL_KILL_QUERY_PREPARED;
    }

    private static boolean isAlreadyFinishedError(SQLException exception) {
        return exception.getErrorCode() == MYSQL_ERROR_UNKNOWN_THREAD_ID
                || StringUtils.containsIgnoreCase(exception.getMessage(), "Unknown thread id");
    }

    static boolean sameMysqlUser(String sessionUser, String connectionUser) {
        return StringUtils.isNotBlank(sessionUser) && StringUtils.isNotBlank(connectionUser)
                && StringUtils.equalsIgnoreCase(stripHost(sessionUser), stripHost(connectionUser));
    }

    static boolean grantAllowsOtherUserSessionKill(String grantLine) {
        if (StringUtils.isBlank(grantLine)) {
            return false;
        }
        String normalized = grantLine
                .replace('`', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("GRANT ") || !normalized.contains(" ON *.* ")) {
            return false;
        }
        int onIndex = normalized.indexOf(" ON *.* ");
        String privileges = normalized.substring("GRANT ".length(), onIndex);
        if (privileges.contains("ALL PRIVILEGES")) {
            return true;
        }
        for (String privilege : privileges.split(",")) {
            String trimmedPrivilege = privilege.trim();
            if ("CONNECTION_ADMIN".equals(trimmedPrivilege) || "SUPER".equals(trimmedPrivilege)) {
                return true;
            }
        }
        return false;
    }

    static boolean supportsSessionInspection(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || major == 5 && minor >= 7;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean canKillOtherUserSessions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(SQL_CURRENT_GRANTS)) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            while (resultSet.next()) {
                for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                    if (grantAllowsOtherUserSessionKill(resultSet.getString(columnIndex))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static long requirePositiveConnectionId(Long connectionId) {
        if (connectionId == null || connectionId < 1) {
            throw new BusinessException("mysql.session.invalidConnectionId");
        }
        return connectionId;
    }

    private static String stripHost(String user) {
        int hostSeparator = user.indexOf('@');
        return hostSeparator < 0 ? user : user.substring(0, hostSeparator);
    }

    private static void requireSupportedMysql(String databaseVersion) {
        if (!supportsSessionInspection(databaseVersion)) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }
}
