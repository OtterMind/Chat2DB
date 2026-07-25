package ai.chat2db.plugin.h2;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2MetaResultSetLifecycleTest {

    @Test
    void closesColumnsBeforeOpeningIndexesAndClosesIndexes() throws Exception {
        List<String> lifecycle = new ArrayList<>();
        AtomicBoolean columnsClosed = new AtomicBoolean();
        AtomicBoolean indexesClosed = new AtomicBoolean();
        ResultSet columns = resultSet(List.of(Map.of(
            "COLUMN_NAME", "ID",
            "TYPE_NAME", "INTEGER",
            "COLUMN_SIZE", 32,
            "NULLABLE", ResultSetMetaData.columnNoNulls
        )), "columns", columnsClosed, lifecycle);
        ResultSet indexes = resultSet(List.of(Map.of(
            "INDEX_NAME", "IDX_USERS_ID",
            "COLUMN_NAME", "ID"
        )), "indexes", indexesClosed, lifecycle);
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getColumns".equals(method.getName())) {
                return columns;
            }
            if ("getIndexInfo".equals(method.getName())) {
                assertTrue(columnsClosed.get(), "columns must be closed before getIndexInfo is called");
                lifecycle.add("metadata.getIndexInfo");
                return indexes;
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return metaData;
            }
            return defaultValue(method.getReturnType());
        });

        String ddl = new H2Meta().tableDDL(connection, "TEST", "PUBLIC", "USERS");

        assertEquals("CREATE TABLE USERS (\nID INTEGER(32) NOT NULL\n);\n"
            + "CREATE INDEX IDX_USERS_ID ON USERS (ID);", ddl);
        assertTrue(columnsClosed.get());
        assertTrue(indexesClosed.get());
        assertEquals(List.of("columns.close", "metadata.getIndexInfo", "indexes.close"), lifecycle);
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows, String name, AtomicBoolean closed,
        List<String> lifecycle) {
        AtomicInteger row = new AtomicInteger(-1);
        return proxy(ResultSet.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    return row.incrementAndGet() < rows.size();
                case "getString":
                    Object stringValue = rows.get(row.get()).get((String) args[0]);
                    return stringValue == null ? null : stringValue.toString();
                case "getInt":
                    Object intValue = rows.get(row.get()).get((String) args[0]);
                    return intValue == null ? 0 : ((Number) intValue).intValue();
                case "close":
                    closed.set(true);
                    lifecycle.add(name + ".close");
                    return null;
                case "isClosed":
                    return closed.get();
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
