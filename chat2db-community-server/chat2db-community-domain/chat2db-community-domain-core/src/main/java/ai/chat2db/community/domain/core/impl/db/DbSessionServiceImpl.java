package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbSessionService;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DbSessionServiceImpl implements IDbSessionService {

    @Override
    public List<Map<String, Object>> list() {
        Connection connection = Chat2DBContext.getConnection();
        String sql = "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, INFO FROM information_schema.processlist ORDER BY ID";
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
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
        String suffix = "CONNECTION".equalsIgnoreCase(killType) ? "" : " QUERY";
        String sql = "KILL" + suffix + " " + connectionId;
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> null);
    }
}
