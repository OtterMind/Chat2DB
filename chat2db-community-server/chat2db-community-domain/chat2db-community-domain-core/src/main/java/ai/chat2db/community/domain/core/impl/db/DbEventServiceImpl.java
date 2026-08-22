package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbEventService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL Event lifecycle (MYSQL-OBJ-013). Reading events and the scheduler state works on
 * 5.7/8.0; creating/editing uses the SQL editor with a CREATE EVENT template, and
 * enable/disable/delete are generated here with server-validated identifiers.
 */
@Service
public class DbEventServiceImpl implements IDbEventService {

    private static final String SQL_EVENTS =
            "SELECT EVENT_NAME, DEFINER, TIME_ZONE, EVENT_TYPE, EXECUTE_AT, INTERVAL_VALUE, "
                    + "INTERVAL_FIELD, STARTS, ENDS, STATUS, ON_COMPLETION, EVENT_COMMENT, "
                    + "EVENT_DEFINITION "
                    + "FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '%s' ORDER BY EVENT_NAME";
    private static final String SQL_SCHEDULER_STATE = "SHOW VARIABLES LIKE 'event_scheduler'";
    private static final String SQL_EVENT_COUNT =
            "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '%s'";

    @Override
    public List<Map<String, Object>> list(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        String escaped = Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor().escapeString(databaseName);
        Connection connection = Chat2DBContext.getConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_EVENTS, escaped), resultSet -> {
            List<Map<String, Object>> events = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventName", resultSet.getString("EVENT_NAME"));
                row.put("definer", resultSet.getString("DEFINER"));
                row.put("timeZone", resultSet.getString("TIME_ZONE"));
                row.put("eventType", resultSet.getString("EVENT_TYPE"));
                row.put("executeAt", resultSet.getTimestamp("EXECUTE_AT"));
                row.put("intervalValue", resultSet.getString("INTERVAL_VALUE"));
                row.put("intervalField", resultSet.getString("INTERVAL_FIELD"));
                row.put("starts", resultSet.getTimestamp("STARTS"));
                row.put("ends", resultSet.getTimestamp("ENDS"));
                row.put("status", resultSet.getString("STATUS"));
                row.put("onCompletion", resultSet.getString("ON_COMPLETION"));
                row.put("comment", resultSet.getString("EVENT_COMMENT"));
                row.put("definition", resultSet.getString("EVENT_DEFINITION"));
                events.add(row);
            }
            return events;
        });
    }

    @Override
    public Map<String, Object> schedulerStatus() {
        Connection connection = Chat2DBContext.getConnection();
        String scheduler = DefaultSQLExecutor.getInstance().execute(connection, SQL_SCHEDULER_STATE, resultSet -> {
            if (resultSet.next()) {
                String value = resultSet.getString(2);
                return value == null ? "OFF" : value.toUpperCase();
            }
            return "OFF";
        });
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("schedulerEnabled", !"OFF".equals(scheduler));
        return status;
    }

    @Override
    public String dropEventSql(String databaseName, String eventName) {
        return "DROP EVENT IF EXISTS " + qualifiedEventName(databaseName, eventName);
    }

    @Override
    public String setEventEnabledSql(String databaseName, String eventName, boolean enabled) {
        return "ALTER EVENT " + qualifiedEventName(databaseName, eventName)
                + (enabled ? " ENABLE" : " DISABLE");
    }

    private static String qualifiedEventName(String databaseName, String eventName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(eventName)) {
            throw new BusinessException("event.name.required");
        }
        return Chat2DBContext.getDbMetaData().getMetaDataName(databaseName)
                + "."
                + Chat2DBContext.getDbMetaData().getMetaDataName(eventName);
    }
}
