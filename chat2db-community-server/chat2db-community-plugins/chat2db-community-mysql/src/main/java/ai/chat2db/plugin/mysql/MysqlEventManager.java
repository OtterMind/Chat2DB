package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IEventManager;
import ai.chat2db.spi.model.request.EventMetadataRequest;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.chat2db.plugin.mysql.constant.MysqlEventConstants.*;

public class MysqlEventManager implements IEventManager {

    private final IDbMetaData metaData;

    public MysqlEventManager() {
        this(new MysqlMetaData());
    }

    MysqlEventManager(IDbMetaData metaData) {
        this.metaData = metaData;
    }

    @Override
    public List<Map<String, Object>> list(Connection connection, String databaseName) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event event : metaData.events(connection, databaseName, null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(FIELD_EVENT_NAME, event.getEventName());
            row.put(FIELD_DEFINER, event.getDefiner());
            row.put(FIELD_TIME_ZONE, event.getTimeZone());
            row.put(FIELD_EVENT_TYPE, event.getEventType());
            row.put(FIELD_EXECUTE_AT, event.getExecuteAt());
            row.put(FIELD_INTERVAL_VALUE, event.getIntervalValue());
            row.put(FIELD_INTERVAL_FIELD, event.getIntervalField());
            row.put(FIELD_STARTS, event.getStarts());
            row.put(FIELD_ENDS, event.getEnds());
            row.put(FIELD_STATUS, event.getStatus());
            row.put(FIELD_ON_COMPLETION, event.getOnCompletion());
            row.put(FIELD_COMMENT, event.getComment());
            row.put(FIELD_DEFINITION, event.getDefinition());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Event detail(Connection connection, String databaseName, String schemaName, String eventName) {
        return metaData.event(connection, new EventMetadataRequest(databaseName, schemaName, eventName));
    }

    @Override
    public Map<String, Object> schedulerStatus(Connection connection, String databaseName) {
        String scheduler = DefaultSQLExecutor.getInstance().execute(connection, SQL_SCHEDULER_STATE, resultSet -> {
            if (!resultSet.next()) {
                return SCHEDULER_OFF;
            }
            String value = resultSet.getString(2);
            return value == null ? SCHEDULER_OFF : value.toUpperCase(Locale.ROOT);
        });
        Long eventCount = DefaultSQLExecutor.getInstance().preExecute(
                connection,
                SQL_EVENT_COUNT,
                new String[] {databaseName},
                resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L
        );
        Map<String, Object> status = new LinkedHashMap<>();
        status.put(FIELD_SCHEDULER_ENABLED, SCHEDULER_ON.equals(scheduler));
        status.put(FIELD_EVENT_COUNT, eventCount == null ? 0L : eventCount);
        return status;
    }

    @Override
    public String buildDropEvent(String databaseName, String eventName) {
        return String.format(SQL_DROP_EVENT, qualifiedEventName(databaseName, eventName));
    }

    @Override
    public String buildAlterEventEnabled(String databaseName, String eventName, boolean enabled) {
        return String.format(
                SQL_ALTER_EVENT_ENABLED,
                qualifiedEventName(databaseName, eventName),
                enabled ? EVENT_ENABLED : EVENT_DISABLED
        );
    }

    private static String qualifiedEventName(String databaseName, String eventName) {
        return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(databaseName)
                + QUALIFIED_NAME_SEPARATOR
                + MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(eventName);
    }
}
