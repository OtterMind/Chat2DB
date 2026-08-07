package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
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
    private static final String FIELD_CURRENT_CONNECTION_ID = "CONNECTION_ID()";
    private static final String ERROR_KEY_KILL_SELF = "mysql.session.killSelfNotAllowed";

    @Override
    public List<Map<String, Object>> list() {
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
        Connection connection = Chat2DBContext.getConnection();
        // Killing the current connection would sever the session the user is working in.
        Long currentConnectionId = DefaultSQLExecutor.getInstance().execute(connection,
                SQL_CURRENT_CONNECTION_ID, resultSet ->
                        resultSet.next() ? resultSet.getLong(FIELD_CURRENT_CONNECTION_ID) : null);
        if (Objects.equals(currentConnectionId, connectionId)) {
            throw new BusinessException(ERROR_KEY_KILL_SELF);
        }
        String suffix = "CONNECTION".equalsIgnoreCase(killType) ? "" : SQL_KILL_QUERY_SUFFIX;
        String sql = SQL_KILL + suffix + " " + connectionId;
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }
}
