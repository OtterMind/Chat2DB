package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.transaction.ActiveTransaction;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IActiveTransactionManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbActiveTransactionServiceImplTest {

    private static final String DB_TYPE = "ACTIVE_TRANSACTION_TEST";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
    }

    @Test
    void delegatesInspectionToCurrentDatabasePlugin() {
        AtomicBoolean called = new AtomicBoolean();
        ActiveTransaction expected = new ActiveTransaction();
        expected.setTrxId("421337");
        bindContext(new IActiveTransactionManager() {
            @Override
            public List<ActiveTransaction> activeTransactions(Connection connection) {
                called.set(true);
                assertNotNull(connection);
                return List.of(expected);
            }
        });

        List<ActiveTransaction> transactions = new DbActiveTransactionServiceImpl().activeTransactions();

        assertTrue(called.get());
        assertEquals(List.of(expected), transactions);
    }

    @Test
    void rejectsPluginWithoutActiveTransactionCapability() {
        bindContext(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbActiveTransactionServiceImpl().activeTransactions());

        assertEquals("activeTransaction.inspection.unsupported", exception.getCode());
    }

    private static void bindContext(IActiveTransactionManager manager) {
        Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(DB_TYPE);
                return config;
            }

            @Override
            public IActiveTransactionManager getActiveTransactionManager() {
                return manager;
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(
                DbActiveTransactionServiceImplTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }
}
