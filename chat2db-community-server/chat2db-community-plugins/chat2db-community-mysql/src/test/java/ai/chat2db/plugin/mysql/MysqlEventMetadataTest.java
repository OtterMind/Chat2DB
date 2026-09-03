package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.model.metadata.Event;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MysqlEventMetadataTest {

    @Test
    void eventListBindsDatabaseNameInsteadOfConcatenatingIt() {
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<String> databaseName = new AtomicReference<>();
        ResultSet resultSet = resultSet();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setString" -> {
                        databaseName.set((String) args[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        sql.set((String) args[0]);
                        yield statement;
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                });

        List<Event> events = new MysqlMetaData().events(connection,
                "analytics'; DROP DATABASE mysql; --", null);

        assertEquals(1, events.size());
        assertEquals("daily_rollup", events.get(0).getEventName());
        assertEquals("analytics'; DROP DATABASE mysql; --", databaseName.get());
        assertFalse(sql.get().contains(databaseName.get()));
        assertEquals(true, sql.get().contains("EVENT_SCHEMA = ?"));
    }

    private ResultSet resultSet() {
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                new java.lang.reflect.InvocationHandler() {
                    private boolean read;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        return switch (method.getName()) {
                            case "next" -> !read && (read = true);
                            case "getString" -> "EVENT_NAME".equals(args[0]) ? "daily_rollup" : "value";
                            case "getTimestamp" -> null;
                            case "close" -> null;
                            default -> defaultValue(method.getReturnType());
                        };
                    }
                });
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
}
