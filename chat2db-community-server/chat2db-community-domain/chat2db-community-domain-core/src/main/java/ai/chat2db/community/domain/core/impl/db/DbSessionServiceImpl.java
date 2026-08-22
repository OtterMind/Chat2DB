package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DbSessionServiceImpl implements IDbSessionService {

    private static final String SQL_SHOW_PROCESSLIST = "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO FROM information_schema.processlist ORDER BY ID";
    private static final String SQL_KILL = "KILL";
    private static final String SQL_KILL_QUERY_SUFFIX = " QUERY";
    private static final String SQL_CURRENT_CONNECTION_ID = "SELECT CONNECTION_ID()";
    private static final String SQL_PROCESS_OWNER = "SELECT USER FROM information_schema.processlist WHERE ID = ?";
    private static final String FIELD_CURRENT_CONNECTION_ID = "CONNECTION_ID()";
    private static final String ERROR_KEY_KILL_SELF = "mysql.session.killSelfNotAllowed";
    private static final String ERROR_KEY_KILL_NOT_OWNER = "mysql.session.killNotOwner";
    private static final String ERROR_KEY_UNSUPPORTED = "mysql.session.unsupported";

    @Override
    public List<Map<String, Object>> list() {
        requireSupportedMysql();
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_PROCESSLIST, resultSet -> {
            List<Map<String, Object>> sessions = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", resultSet.getLong("ID"));
                row.put("user", resultSet.getString("USER"));
                row.put("host", resultSet.getString("HOST"));
                row.put("db", resultSet.getString("DB"));
                row.put("command", resultSet.getString("COMMAND"));
                row.put("time", resultSet.getLong("TIME"));
                row.put("state", resultSet.getString("STATE"));
                row.put("info", resultSet.getString("INFO"));
                sessions.add(row);
            }
            return sessions;
        });
    }

    @Override
    public void kill(Long connectionId, String killType) {
        requireSupportedMysql();
        long targetConnectionId = requirePositiveConnectionId(connectionId);
        Connection connection = Chat2DBContext.getConnection();
        // Killing the current connection would sever the session the user is working in.
        Long currentConnectionId = DefaultSQLExecutor.getInstance().execute(connection,
                SQL_CURRENT_CONNECTION_ID, resultSet ->
                        resultSet.next() ? resultSet.getLong(FIELD_CURRENT_CONNECTION_ID) : null);
        if (Objects.equals(currentConnectionId, targetConnectionId)) {
            throw new BusinessException(ERROR_KEY_KILL_SELF);
        }
        requireTargetOwnedByConnectionUser(connection, targetConnectionId);
        String sql = "CONNECTION".equalsIgnoreCase(killType) ? SQL_KILL : SQL_KILL + SQL_KILL_QUERY_SUFFIX;
        try (PreparedStatement statement = connection.prepareStatement(sql + " ?")) {
            statement.setLong(1, targetConnectionId);
            statement.execute();
        } catch (SQLException exception) {
            throw new BusinessException("mysql.session.killFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    private static void requireTargetOwnedByConnectionUser(Connection connection, long targetConnectionId) {
        try (PreparedStatement statement = connection.prepareStatement(SQL_PROCESS_OWNER)) {
            statement.setLong(1, targetConnectionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || !sameMysqlUser(resultSet.getString("USER"), connectionUser())) {
                    throw new BusinessException(ERROR_KEY_KILL_NOT_OWNER);
                }
            }
        } catch (SQLException exception) {
            throw new BusinessException("mysql.session.ownerCheckFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    static boolean sameMysqlUser(String sessionUser, String connectionUser) {
        return StringUtils.isNotBlank(sessionUser) && StringUtils.isNotBlank(connectionUser)
                && StringUtils.equalsIgnoreCase(stripHost(sessionUser), stripHost(connectionUser));
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

    private static long requirePositiveConnectionId(Long connectionId) {
        if (connectionId == null || connectionId < 1) {
            throw new BusinessException("mysql.session.invalidConnectionId");
        }
        return connectionId;
    }

    private static String connectionUser() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return connectInfo == null ? null : connectInfo.getUser();
    }

    private static String stripHost(String user) {
        int hostSeparator = user.indexOf('@');
        return hostSeparator < 0 ? user : user.substring(0, hostSeparator);
    }

    private static void requireSupportedMysql() {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null || !DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(connectInfo.getDbType())
                || !supportsSessionInspection(Chat2DBContext.getDbVersion())) {
            throw new BusinessException(ERROR_KEY_UNSUPPORTED);
        }
    }
}
