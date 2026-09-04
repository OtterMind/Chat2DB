package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.core.impl.db.extension.MetadataAccessPolicyManager;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDatabasePropertiesManager;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbDatabaseServiceImplTest {

    private static final String DB_TYPE = "DATABASE_PROPERTIES_TEST";

    private Map<String, IPlugin> originalPlugins;

    @BeforeEach
    void setUp() {
        originalPlugins = Map.copyOf(Chat2DBContext.PLUGIN_MAP);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.clear();
        Chat2DBContext.PLUGIN_MAP.putAll(originalPlugins);
    }

    @Test
    void delegatesDatabasePropertiesOperationsToCurrentPlugin() {
        RecordingDatabasePropertiesManager manager = new RecordingDatabasePropertiesManager();
        bindContext(manager);
        DbDatabaseServiceImpl service = new DbDatabaseServiceImpl();

        assertEquals(Map.of("charset", "utf8mb4", "collation", "utf8mb4_bin"),
                service.databaseInfo(42L, "app"));
        assertEquals("ALTER DATABASE `app` DEFAULT COLLATE utf8mb4_bin",
                service.previewAlterDatabaseSql(42L, "app", null, "utf8mb4_bin"));

        assertNotNull(manager.connection);
        assertEquals("app", manager.databaseName);
        assertEquals("utf8mb4_bin", manager.collation);
    }

    @Test
    void rejectsPluginWithoutDatabasePropertiesCapability() {
        bindContext(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbDatabaseServiceImpl().databaseInfo(42L, "app"));

        assertEquals("database.properties.unsupported", exception.getCode());
    }

    @Test
    void rejectsDatabasePropertiesOperationsDeniedByMetadataPolicy() {
        RecordingDatabasePropertiesManager manager = new RecordingDatabasePropertiesManager();
        bindContext(manager);
        MetadataAccessPolicyManager policyManager = new MetadataAccessPolicyManager(List.of(
                resources -> resources.stream()
                        .map(resource -> !"hidden".equals(resource.getDatabaseName()))
                        .toList()));
        DbDatabaseServiceImpl service = new DbDatabaseServiceImpl(policyManager);

        BusinessException infoException = assertThrows(BusinessException.class,
                () -> service.databaseInfo(42L, "hidden"));
        BusinessException previewException = assertThrows(BusinessException.class,
                () -> service.previewAlterDatabaseSql(42L, "hidden", "utf8mb4", "utf8mb4_bin"));

        assertEquals("common.permissionDenied", infoException.getCode());
        assertEquals("common.permissionDenied", previewException.getCode());
    }

    @Test
    void unsupportedJdbcUrlDoesNotDiscardSchemas() throws Throwable {
        List<Schema> schemas = new ArrayList<>();
        schemas.add(Schema.builder().name("analytics").build());
        schemas.add(Schema.builder().name("default").build());

        invokeSortSchema(new DbDatabaseServiceImpl(), schemas, connectionWithoutUrl());

        assertEquals(List.of("analytics", "default"), schemas.stream().map(Schema::getName).toList());
    }

    private static void bindContext(IDatabasePropertiesManager manager) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                config.setDefaultDriverConfig(new DriverConfig());
                return config;
            }

            @Override
            public IDatabasePropertiesManager getDatabasePropertiesManager() {
                return manager;
            }
        });
        Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("isClosed".equals(method.getName())) {
                return false;
            }
            return defaultValue(method.getReturnType());
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setConnection(connection);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
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
        return 0;
    }

    private static final class RecordingDatabasePropertiesManager implements IDatabasePropertiesManager {

        private Connection connection;
        private String databaseName;
        private String collation;

        @Override
        public Map<String, String> databaseInfo(Connection connection, String databaseName) {
            this.connection = connection;
            this.databaseName = databaseName;
            return Map.of("charset", "utf8mb4", "collation", "utf8mb4_bin");
        }

        @Override
        public String previewAlterDatabaseSql(Connection connection, String databaseName, String charset,
                                              String collation) {
            this.connection = connection;
            this.databaseName = databaseName;
            this.collation = collation;
            return "ALTER DATABASE `app` DEFAULT COLLATE utf8mb4_bin";
        }
    }
}
