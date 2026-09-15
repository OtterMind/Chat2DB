package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDiagnosticsManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbDiagnosticsServiceImplTest {

    private static final String DB_TYPE = "DIAGNOSTICS_TEST";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void delegatesInnodbStatusToCurrentDatabasePlugin() {
        Connection connection = connection();
        AtomicReference<Connection> actualConnection = new AtomicReference<>();
        AtomicReference<String> actualVersion = new AtomicReference<>();
        InnodbStatusResponse expected = new InnodbStatusResponse();
        bindContext((receivedConnection, databaseVersion) -> {
            actualConnection.set(receivedConnection);
            actualVersion.set(databaseVersion);
            return expected;
        }, connection);

        InnodbStatusResponse result = new DbDiagnosticsServiceImpl().innodbStatus();

        assertSame(expected, result);
        assertSame(connection, actualConnection.get());
        assertEquals("8.0.36", actualVersion.get());
    }

    @Test
    void rejectsPluginWithoutDiagnosticsCapability() {
        bindContext(null, connection());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbDiagnosticsServiceImpl().innodbStatus());

        assertEquals("mysql.diagnostics.unsupported", exception.getCode());
    }

    private static void bindContext(IDiagnosticsManager manager, Connection connection) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                return config;
            }

            @Override
            public IDiagnosticsManager getDiagnosticsManager() {
                return manager;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDbVersion("8.0.36");
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                DbDiagnosticsServiceImplTest.class.getClassLoader(),
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
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }
}
