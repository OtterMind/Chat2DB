package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseCreateRequest;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbDatabaseServiceImplTest {

    private static final String TEST_DB_TYPE = "DATABASE_MODIFY_FORWARDING_TEST";

    private final CapturingDbManager dbManager = new CapturingDbManager();
    private IPlugin previousPlugin;

    @BeforeEach
    void setUpContext() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin(dbManager));
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDownContext() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void modifyDatabaseForwardsOriginalAndNewDatabaseNamesToDbManager() {
        DbDatabaseCreateRequest request = DbDatabaseCreateRequest.builder()
                .name("legacy_db")
                .newName("renamed_db")
                .build();

        new DbDatabaseServiceImpl().modifyDatabase(request);

        assertEquals("legacy_db", dbManager.databaseName.get());
        assertEquals("renamed_db", dbManager.newDatabaseName.get());
    }

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

    private static final class CapturingDbManager extends DefaultDBManager {
        private final AtomicReference<String> databaseName = new AtomicReference<>();
        private final AtomicReference<String> newDatabaseName = new AtomicReference<>();

        @Override
        public Connection getConnection(ConnectInfo connectInfo) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isClosed" -> false;
                        case "close" -> null;
                        default -> null;
                    });
        }

        @Override
        public void modifyDatabase(Connection connection, String databaseName, String newDatabaseName) {
            this.databaseName.set(databaseName);
            this.newDatabaseName.set(newDatabaseName);
        }
    }

    private record TestPlugin(IDbManager dbManager) implements IPlugin {
        @Override
        public DBConfig getDBConfig() {
            DBConfig dbConfig = new DBConfig();
            dbConfig.setDbType(TEST_DB_TYPE);
            return dbConfig;
        }

        @Override
        public IDbManager getDbManager() {
            return dbManager;
        }
    }
}
