package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.lock.LockView;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.ILockManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbLockServiceImplTest {

    private static final String DB_TYPE = "LOCK_VIEW_TEST";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void rejectsMissingDatasourceContextBeforeResolvingPlugin() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbLockServiceImpl().lockView(11L));

        assertEquals("datasource.context.required", exception.getCode());
    }

    @Test
    void rejectsMismatchedDatasourceContextBeforeResolvingPlugin() {
        bindContext(11L, new RecordingLockManager());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbLockServiceImpl().lockView(12L));

        assertEquals("datasource.context.mismatch", exception.getCode());
    }

    @Test
    void delegatesToCurrentDatabasePluginWithBoundConnection() {
        RecordingLockManager manager = new RecordingLockManager();
        bindContext(21L, manager);

        LockView view = new DbLockServiceImpl().lockView(21L);

        assertEquals(21L, view.getDataSourceId());
        assertEquals(21L, manager.dataSourceId);
        assertNotNull(manager.connection);
    }

    @Test
    void rejectsPluginWithoutLockCapability() {
        bindContext(31L, null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbLockServiceImpl().lockView(31L));

        assertEquals("lock.inspection.unsupported", exception.getCode());
    }

    private static void bindContext(Long dataSourceId, ILockManager manager) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                config.setDefaultDriverConfig(new DriverConfig());
                return config;
            }

            @Override
            public ILockManager getLockManager() {
                return manager;
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(
                DbLockServiceImplTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class RecordingLockManager implements ILockManager {
        private Connection connection;
        private Long dataSourceId;

        @Override
        public LockView lockView(Connection connection, Long dataSourceId) {
            if (this.connection != null) {
                assertSame(this.connection, connection);
            }
            this.connection = connection;
            this.dataSourceId = dataSourceId;
            LockView view = new LockView();
            view.setDataSourceId(dataSourceId);
            return view;
        }
    }
}
