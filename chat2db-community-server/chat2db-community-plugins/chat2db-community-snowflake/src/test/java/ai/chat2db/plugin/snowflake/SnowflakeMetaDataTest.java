package ai.chat2db.plugin.snowflake;

import ai.chat2db.community.domain.api.model.metadata.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SnowflakeMetaDataTest {

    @Test
    void viewPreservesDefinitionContainingUppercaseAs() {
        String definition = "SELECT PRICE AS UNIT_PRICE FROM SALES";

        assertEquals(definition, viewWithDefinition(definition).getDdl());
    }

    @Test
    void viewPreservesDefinitionContainingLowercaseAlias() {
        String definition = "select price as unit_price from sales";

        assertEquals(definition, viewWithDefinition(definition).getDdl());
    }

    @Test
    void viewPreservesDefinitionWithoutAs() {
        String definition = "SELECT PRICE FROM SALES";

        assertEquals(definition, viewWithDefinition(definition).getDdl());
    }

    @Test
    void viewPreservesNullDefinition() {
        assertNull(viewWithDefinition(null).getDdl());
    }

    @Test
    void viewKeepsIdentityWhenResultSetIsEmpty() {
        Table view = new SnowflakeMetaData().view(connection(false, null), "WAREHOUSE", "REPORTING", "SALES_VIEW");

        assertEquals("WAREHOUSE", view.getDatabaseName());
        assertEquals("REPORTING", view.getSchemaName());
        assertEquals("SALES_VIEW", view.getName());
        assertNull(view.getDdl());
    }

    private static Table viewWithDefinition(String definition) {
        return new SnowflakeMetaData().view(connection(true, definition),
                "WAREHOUSE", "REPORTING", "SALES_VIEW");
    }

    private static Connection connection(boolean hasRow, String definition) {
        ResultSet resultSet = resultSet(hasRow, definition);
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> statement;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(boolean hasRow, String definition) {
        boolean[] consumed = {false};
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> {
                if (!hasRow || consumed[0]) {
                    yield false;
                }
                consumed[0] = true;
                yield true;
            }
            case "getString" -> "DEFINITION".equals(args[0]) ? definition : null;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, args) -> method.getDeclaringClass() == Object.class
                        ? objectMethod(target, method, args)
                        : handler.invoke(target, method, args));
        return type.cast(proxy);
    }

    private static Object objectMethod(Object target, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> target.getClass().getInterfaces()[0].getSimpleName() + "Proxy";
            case "hashCode" -> System.identityHashCode(target);
            case "equals" -> target == args[0];
            default -> null;
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
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
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
