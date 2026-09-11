package ai.chat2db.plugin.hive;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveMetaDataTest {

    @Test
    void configExposesHiveNamespacesAsDatabases() {
        DBConfig config = new HivePlugin().getDBConfig();

        assertTrue(config.isSupportDatabase());
        assertFalse(config.isSupportSchema());
    }

    @Test
    void databasesReadsShowDatabasesResult() {
        AtomicReference<String> executedSql = new AtomicReference<>();
        Connection connection = connectionWithDatabaseRows(List.of("analytics", "default"), executedSql);

        List<Database> databases = new HiveMetaData().databases(connection);

        assertEquals("show databases", executedSql.get());
        assertEquals(List.of("analytics", "default"), databases.stream().map(Database::getName).toList());
    }

    @Test
    void schemasAreEmptyBecauseHiveHasNoSeparateSchemaNamespace() {
        assertTrue(new HiveMetaData().schemas(null, "analytics").isEmpty());
    }

    @Test
    void qualifiedTableNameKeepsHiveDatabasePrefix() {
        HiveMetaData metaData = new HiveMetaData();

        assertEquals("`analytics`.`events`", metaData.getQualifiedTableName("analytics", null, "events"));
        assertEquals("`events`", metaData.getQualifiedTableName(null, null, "events"));
    }

    private static Connection connectionWithDatabaseRows(List<String> names, AtomicReference<String> executedSql) {
        AtomicInteger row = new AtomicInteger(-1);
        ResultSet resultSet = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> row.incrementAndGet() < names.size();
            case "getString" -> names.get(row.get());
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            case "close" -> null;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                executedSql.set((String) args[0]);
                return statement;
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
