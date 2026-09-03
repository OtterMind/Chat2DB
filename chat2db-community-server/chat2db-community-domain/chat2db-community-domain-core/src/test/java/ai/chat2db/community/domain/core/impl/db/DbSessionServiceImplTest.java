package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.db.DbSessionKillResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.ISessionManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbSessionServiceImplTest {

    private static final String DB_TYPE = "SESSION_TEST";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void delegatesListToCurrentDatabasePlugin() {
        Connection connection = connection();
        AtomicReference<Connection> actualConnection = new AtomicReference<>();
        AtomicReference<String> actualVersion = new AtomicReference<>();
        bindContext(new ISessionManager() {
            @Override
            public List<Map<String, Object>> list(Connection connection, String databaseVersion) {
                actualConnection.set(connection);
                actualVersion.set(databaseVersion);
                return List.of(Map.of("id", 42L));
            }

            @Override
            public DbSessionKillResult kill(Connection connection, String databaseVersion, String connectionUser,
                                            Long connectionId, String killType) {
                throw new UnsupportedOperationException();
            }
        }, connection);

        List<Map<String, Object>> sessions = new DbSessionServiceImpl().list();

        assertEquals(List.of(Map.of("id", 42L)), sessions);
        assertSame(connection, actualConnection.get());
        assertEquals("8.0.36", actualVersion.get());
    }

    @Test
    void delegatesKillArgumentsToCurrentDatabasePlugin() {
        Connection connection = connection();
        AtomicReference<List<Object>> actualArguments = new AtomicReference<>();
        DbSessionKillResult expected = DbSessionKillResult.killed(57L, "QUERY", "KILL QUERY 57");
        bindContext(new ISessionManager() {
            @Override
            public List<Map<String, Object>> list(Connection connection, String databaseVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DbSessionKillResult kill(Connection connection, String databaseVersion, String connectionUser,
                                            Long connectionId, String killType) {
                actualArguments.set(List.of(connection, databaseVersion, connectionUser, connectionId, killType));
                return expected;
            }
        }, connection);

        DbSessionKillResult result = new DbSessionServiceImpl().kill(57L, "QUERY");

        assertSame(expected, result);
        assertEquals(List.of(connection, "8.0.36", "chat2db", 57L, "QUERY"), actualArguments.get());
    }

    @Test
    void rejectsPluginWithoutSessionCapability() {
        bindContext(null, connection());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbSessionServiceImpl().list());

        assertEquals("mysql.session.unsupported", exception.getCode());
    }

    private static void bindContext(ISessionManager manager, Connection connection) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                return config;
            }

            @Override
            public ISessionManager getSessionManager() {
                return manager;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDbVersion("8.0.36");
        connectInfo.setUser("chat2db");
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                DbSessionServiceImplTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("isValid".equals(method.getName())) {
                        return true;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "DbSessionServiceTestConnection";
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }
}
