package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.model.request.EventMetadataRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlEventManagerTest {

    @Test
    void delegatesMetadataAndMapsEventRows() {
        Event expected = Event.builder()
                .eventName("daily_rollup")
                .status("ENABLED")
                .definition("INSERT INTO rollup VALUES (1)")
                .build();
        RecordingMetaData metaData = new RecordingMetaData(expected);
        MysqlEventManager manager = new MysqlEventManager(metaData);
        Connection connection = connection(new ArrayList<>(), new ArrayList<>());

        List<Map<String, Object>> rows = manager.list(connection, "analytics");
        Event detail = manager.detail(connection, "analytics", null, "daily_rollup");

        assertEquals("daily_rollup", rows.get(0).get("eventName"));
        assertEquals("ENABLED", rows.get(0).get("status"));
        assertEquals("INSERT INTO rollup VALUES (1)", rows.get(0).get("definition"));
        assertSame(expected, detail);
        assertEquals("analytics", metaData.detailRequest.getDatabaseName());
        assertEquals("daily_rollup", metaData.detailRequest.getEventName());
    }

    @Test
    void schedulerStatusUsesBoundDatabaseNameAndConsistentCount() {
        List<String> sql = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        MysqlEventManager manager = new MysqlEventManager(new RecordingMetaData(new Event()));

        Map<String, Object> status = manager.schedulerStatus(connection(sql, parameters),
                "analytics'; DROP DATABASE mysql; --");

        assertEquals(Boolean.TRUE, status.get("schedulerEnabled"));
        assertEquals(3L, status.get("eventCount"));
        assertTrue(sql.stream().anyMatch(statement -> statement.contains("event_scheduler")));
        assertTrue(sql.stream().anyMatch(statement -> statement.contains("EVENT_SCHEMA = ?")));
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("DROP DATABASE")));
        assertEquals(List.of("analytics'; DROP DATABASE mysql; --"), parameters);
    }

    @Test
    void disabledSchedulerIsNotReportedAsEnabled() {
        MysqlEventManager manager = new MysqlEventManager(new RecordingMetaData(new Event()));

        Map<String, Object> status = manager.schedulerStatus(
                connection(new ArrayList<>(), new ArrayList<>(), "DISABLED"),
                "analytics"
        );

        assertEquals(Boolean.FALSE, status.get("schedulerEnabled"));
        assertEquals(3L, status.get("eventCount"));
    }

    @Test
    void lifecycleSqlAlwaysQuotesIdentifiers() {
        MysqlEventManager manager = new MysqlEventManager();

        assertEquals("DROP EVENT IF EXISTS `analytics`.`daily``rollup`",
                manager.buildDropEvent("analytics", "daily`rollup"));
        assertEquals("ALTER EVENT `analytics`.`daily``rollup` ENABLE",
                manager.buildAlterEventEnabled("analytics", "daily`rollup", true));
        assertEquals("ALTER EVENT `analytics`.`daily``rollup` DISABLE",
                manager.buildAlterEventEnabled("analytics", "daily`rollup", false));
    }

    private static Connection connection(List<String> sql, List<String> parameters) {
        return connection(sql, parameters, "ON");
    }

    private static Connection connection(List<String> sql, List<String> parameters, String schedulerState) {
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> {
                String statementSql = (String) args[0];
                sql.add(statementSql);
                yield preparedStatement(statementSql, parameters, schedulerState);
            }
            case "isClosed" -> false;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement preparedStatement(
            String sql,
            List<String> parameters,
            String schedulerState
    ) {
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "setString" -> {
                parameters.add((String) args[1]);
                yield null;
            }
            case "execute" -> true;
            case "getResultSet" -> resultSet(sql.contains("event_scheduler")
                    ? new Object[] {"event_scheduler", schedulerState}
                    : new Object[] {3L});
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(Object[] row) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private boolean read;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> !read && (read = true);
                    case "getString" -> String.valueOf(row[(Integer) args[0] - 1]);
                    case "getLong" -> ((Number) row[(Integer) args[0] - 1]).longValue();
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class || type == long.class) {
            return 0;
        }
        return 0.0;
    }

    private static final class RecordingMetaData extends DefaultMetaService {
        private final Event event;
        private EventMetadataRequest detailRequest;

        private RecordingMetaData(Event event) {
            this.event = event;
        }

        @Override
        public List<Event> events(Connection connection, String databaseName, String schemaName) {
            return List.of(event);
        }

        @Override
        public Event event(Connection connection, EventMetadataRequest eventMetadataRequest) {
            detailRequest = eventMetadataRequest;
            return event;
        }
    }
}
