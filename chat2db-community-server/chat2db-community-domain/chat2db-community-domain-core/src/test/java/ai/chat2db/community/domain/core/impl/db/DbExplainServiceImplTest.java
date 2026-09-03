package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.result.DbExplainCapability;
import ai.chat2db.community.domain.api.model.result.DbExplainResult;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IExplainManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbExplainServiceImplTest {

    private static final String DB_TYPE = "EXPLAIN_TEST";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void delegatesExplainRequestsToCurrentDatabasePlugin() {
        RecordingExplainManager manager = new RecordingExplainManager();
        Connection connection = connection();
        bindContext(manager, connection);

        DbExplainResult jsonResult = new DbExplainServiceImpl().explainJson("SELECT 1", "json-request");
        DbExplainResult analyzeResult = new DbExplainServiceImpl().explainAnalyze("SELECT 2", "analyze-request");

        assertSame(manager.jsonResult, jsonResult);
        assertSame(manager.analyzeResult, analyzeResult);
        assertSame(connection, manager.connection);
        assertSame(Chat2DBContext.getConnectInfo(), manager.connectInfo);
        assertEquals("8.0.36", manager.databaseVersion);
        assertEquals("SELECT 2", manager.sql);
        assertEquals("analyze-request", manager.requestId);
    }

    @Test
    void delegatesCapabilityAndCancellationToCurrentDatabasePlugin() {
        RecordingExplainManager manager = new RecordingExplainManager();
        bindContext(manager, connection());

        DbExplainCapability capability = new DbExplainServiceImpl().capability();
        boolean cancelled = new DbExplainServiceImpl().cancel("cancel-request");

        assertSame(manager.capability, capability);
        assertTrue(cancelled);
        assertEquals(DB_TYPE, manager.databaseType);
        assertEquals("8.0.36", manager.databaseVersion);
        assertEquals("cancel-request", manager.requestId);
    }

    @Test
    void reportsUnsupportedCapabilityAndRejectsExecutionWithoutPluginManager() {
        bindContext(null, connection());

        DbExplainCapability capability = new DbExplainServiceImpl().capability();

        assertEquals(DB_TYPE, capability.getDatabaseType());
        assertFalse(capability.isExplainJsonSupported());
        assertFalse(capability.isExplainAnalyzeSupported());
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbExplainServiceImpl().explainJson("SELECT 1", "request"));
        assertEquals("sql.explain.unsupported", exception.getCode());
    }

    private static void bindContext(IExplainManager manager, Connection connection) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                return config;
            }

            @Override
            public IExplainManager getExplainManager() {
                return manager;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setConsoleId(84L);
        connectInfo.setLoginUser("tester");
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDbVersion("8.0.36");
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(
                DbExplainServiceImplTest.class.getClassLoader(),
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

    private static final class RecordingExplainManager implements IExplainManager {
        private final DbExplainResult jsonResult = new DbExplainResult();
        private final DbExplainResult analyzeResult = new DbExplainResult();
        private final DbExplainCapability capability = new DbExplainCapability();
        private Connection connection;
        private ConnectInfo connectInfo;
        private String databaseType;
        private String databaseVersion;
        private String sql;
        private String requestId;

        @Override
        public DbExplainResult explainJson(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                           String sql, String requestId) {
            record(connection, connectInfo, databaseVersion, sql, requestId);
            return jsonResult;
        }

        @Override
        public DbExplainResult explainAnalyze(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                                              String sql, String requestId) {
            record(connection, connectInfo, databaseVersion, sql, requestId);
            return analyzeResult;
        }

        @Override
        public DbExplainCapability capability(String databaseType, String databaseVersion) {
            this.databaseType = databaseType;
            this.databaseVersion = databaseVersion;
            return capability;
        }

        @Override
        public boolean cancel(ConnectInfo connectInfo, String requestId) {
            this.connectInfo = connectInfo;
            this.requestId = requestId;
            return true;
        }

        private void record(Connection connection, ConnectInfo connectInfo, String databaseVersion,
                            String sql, String requestId) {
            this.connection = connection;
            this.connectInfo = connectInfo;
            this.databaseVersion = databaseVersion;
            this.sql = sql;
            this.requestId = requestId;
        }
    }
}
