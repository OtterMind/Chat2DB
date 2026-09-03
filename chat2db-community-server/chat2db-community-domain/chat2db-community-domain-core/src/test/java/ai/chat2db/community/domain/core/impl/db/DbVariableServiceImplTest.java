package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.service.db.IDbVariableService.EditMeta;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.IVariableManager;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbVariableServiceImplTest {

    private static final String DB_TYPE = "VARIABLE_TEST";
    private static final String DB_VERSION = "8.0.36";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void delegatesOperationsToCurrentDatabasePlugin() {
        RecordingVariableManager manager = new RecordingVariableManager();
        bindContext(manager);
        DbVariableServiceImpl service = new DbVariableServiceImpl();

        assertEquals(List.of(Map.of("name", "autocommit", "value", "ON")),
                service.variables("SESSION", "VARIABLES"));
        assertEquals(new EditMeta("autocommit", "ONOFF", List.of("SESSION"), List.of(),
                false, null, null, null, null), service.editable("autocommit"));
        assertEquals("SET SESSION autocommit = OFF",
                service.previewSetVariableSql("autocommit", "OFF", "SESSION"));

        assertNotNull(manager.connection);
        assertEquals(DB_VERSION, manager.dbVersion);
        assertEquals("SESSION", manager.scope);
        assertEquals("VARIABLES", manager.kind);
        assertEquals("autocommit", manager.variableName);
        assertEquals("OFF", manager.value);
    }

    @Test
    void rejectsPluginWithoutVariableCapability() {
        bindContext(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbVariableServiceImpl().variables("GLOBAL", "STATUS"));

        assertEquals("mysql.variables.unsupported", exception.getCode());
    }

    private static void bindContext(IVariableManager manager) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                return config;
            }

            @Override
            public IVariableManager getVariableManager() {
                return manager;
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(
                DbVariableServiceImplTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("getMetaData".equals(method.getName())) {
                        return Proxy.newProxyInstance(
                                DbVariableServiceImplTest.class.getClassLoader(),
                                new Class<?>[]{DatabaseMetaData.class},
                                (metadata, metadataMethod, metadataArgs) ->
                                        "getDatabaseProductVersion".equals(metadataMethod.getName())
                                                ? DB_VERSION : null);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(43L);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class RecordingVariableManager implements IVariableManager {

        private Connection connection;
        private String dbVersion;
        private String scope;
        private String kind;
        private String variableName;
        private String value;

        @Override
        public List<Map<String, Object>> variables(Connection connection, String dbVersion, String scope, String kind) {
            recordContext(connection, dbVersion);
            this.scope = scope;
            this.kind = kind;
            return List.of(Map.of("name", "autocommit", "value", "ON"));
        }

        @Override
        public EditMeta editable(Connection connection, String dbVersion, String variableName) {
            recordContext(connection, dbVersion);
            this.variableName = variableName;
            return new EditMeta(variableName, "ONOFF", List.of("SESSION"), List.of(),
                    false, null, null, null, null);
        }

        @Override
        public String previewSetVariableSql(Connection connection, String dbVersion, String variableName, String value,
                                            String scope) {
            recordContext(connection, dbVersion);
            this.variableName = variableName;
            this.value = value;
            assertEquals("SESSION", scope);
            return "SET SESSION autocommit = OFF";
        }

        private void recordContext(Connection connection, String dbVersion) {
            if (this.connection != null) {
                assertSame(this.connection, connection);
            }
            this.connection = connection;
            this.dbVersion = dbVersion;
        }
    }
}
