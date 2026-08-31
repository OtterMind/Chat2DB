package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbDatabaseServiceImplTest {

    @Test
    void unsupportedJdbcUrlDoesNotDiscardSchemas() throws Throwable {
        List<Schema> schemas = new ArrayList<>();
        schemas.add(Schema.builder().name("analytics").build());
        schemas.add(Schema.builder().name("default").build());

        invokeSortSchema(new DbDatabaseServiceImpl(), schemas, connectionWithoutUrl());

        assertEquals(List.of("analytics", "default"), schemas.stream().map(Schema::getName).toList());
    }

    private static void invokeSortSchema(DbDatabaseServiceImpl service, List<Schema> schemas,
                                         Connection connection) throws Throwable {
        Method method = DbDatabaseServiceImpl.class.getDeclaredMethod("sortSchema", List.class, Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(service, schemas, connection);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Connection connectionWithoutUrl() {
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getURL".equals(method.getName())) {
                throw new SQLException("Method not supported");
            }
            return defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return metaData;
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
