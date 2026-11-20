package ai.chat2db.plugin.xugudb;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class XUGUDBMetaDataTest {

    @Test
    void columnsUsesColumnNumberForOrdinalAndKeepsScaleAsColumnSize() {
        List<TableColumn> columns = new XUGUDBMetaData().columns(
                connection(columnResultSet(7, 2)), "DB", "SCH", "T1");

        assertEquals(1, columns.size());
        assertEquals(7, columns.get(0).getOrdinalPosition());
        assertEquals(2, columns.get(0).getColumnSize());
    }

    @Test
    void columnsPreservesNullOrdinalAndMinusOneScaleAsNull() {
        List<TableColumn> columns = new XUGUDBMetaData().columns(
                connection(columnResultSet(null, -1)), "DB", "SCH", "T1");

        assertEquals(1, columns.size());
        assertNull(columns.get(0).getOrdinalPosition());
        assertNull(columns.get(0).getColumnSize());
    }

    private static Connection connection(ResultSet resultSet) {
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                XUGUDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> true;
                    case "getResultSet" -> resultSet;
                    default -> defaultValue(method.getReturnType());
                });
        return (Connection) Proxy.newProxyInstance(
                XUGUDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> "prepareStatement".equals(method.getName())
                        ? statement : defaultValue(method.getReturnType()));
    }

    private static ResultSet columnResultSet(Integer columnNumber, Integer scale) {
        boolean[] unread = {true};
        boolean[] wasNull = {false};
        return (ResultSet) Proxy.newProxyInstance(
                XUGUDBMetaDataTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean hasRow = unread[0];
                        unread[0] = false;
                        yield hasRow;
                    }
                    case "getString" -> {
                        String value = switch ((String) args[0]) {
                            case "COL_NAME" -> "C1";
                            case "TYPE_NAME" -> "VARCHAR";
                            case "DEF_VAL" -> null;
                            case "COMMENTS" -> "comment";
                            default -> throw new AssertionError("Unexpected string column: " + args[0]);
                        };
                        wasNull[0] = value == null;
                        yield value;
                    }
                    case "getBoolean" -> {
                        String column = (String) args[0];
                        if (!"VARYING".equals(column) && !"NOT_NULL".equals(column)) {
                            throw new AssertionError("Unexpected boolean column: " + column);
                        }
                        wasNull[0] = false;
                        yield false;
                    }
                    case "getInt" -> {
                        Integer value = switch ((String) args[0]) {
                            case "COL_NO" -> columnNumber;
                            case "SCALE" -> scale;
                            default -> throw new AssertionError("Unexpected integer column: " + args[0]);
                        };
                        wasNull[0] = value == null;
                        yield value == null ? 0 : value;
                    }
                    case "wasNull" -> wasNull[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
