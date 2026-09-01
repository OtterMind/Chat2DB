package ai.chat2db.spi.util;

import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultSetUtilsTest {

    @Test
    void metadataMappingKeepsNullableForeignKeyActionsNullWhenDriverReportsZeroForNull() {
        ResultSet resultSet = resultSet(
                List.of("FK_NAME", "FKCOLUMN_NAME", "PKTABLE_NAME", "PKCOLUMN_NAME", "KEY_SEQ",
                        "UPDATE_RULE", "DELETE_RULE"),
                List.of(List.of("fk_child_parent", "child_a", "parent", "parent_a", (short) 1,
                        NullShort.ZERO, NullShort.ZERO)));

        List<ForeignKeyInfo> foreignKeys = ResultSetUtils.toObjectList(resultSet, ForeignKeyInfo.class);

        assertEquals(1, foreignKeys.size());
        assertEquals("fk_child_parent", foreignKeys.get(0).getFkName());
        assertNull(foreignKeys.get(0).getUpdateRule());
        assertNull(foreignKeys.get(0).getDeleteRule());
    }

    private static ResultSet resultSet(List<String> columns, List<List<Object>> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int row = -1;
            private boolean wasNull;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getMetaData".equals(name)) {
                    return resultSetMetaData(columns);
                }
                if ("next".equals(name)) {
                    row++;
                    return row < rows.size();
                }
                if ("getObject".equals(name)) {
                    Object value = rows.get(row).get((Integer) args[0] - 1);
                    wasNull = value instanceof NullShort;
                    return wasNull ? ((NullShort) value).reportedValue() : value;
                }
                if ("getString".equals(name)) {
                    Object value = rows.get(row).get((Integer) args[0] - 1);
                    wasNull = value == null;
                    return value == null ? null : value.toString();
                }
                if ("wasNull".equals(name)) {
                    return wasNull;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static ResultSetMetaData resultSetMetaData(List<String> columns) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> columns.size();
            case "getColumnLabel", "getColumnName" -> columns.get((Integer) args[0] - 1);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(target, method, args);
            }
            return handler.invoke(target, method, args);
        });
        return type.cast(proxy);
    }

    private static Object invokeObjectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }

    private enum NullShort {
        ZERO((short) 0);

        private final short reportedValue;

        NullShort(short reportedValue) {
            this.reportedValue = reportedValue;
        }

        private short reportedValue() {
            return reportedValue;
        }
    }
}
