package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbTriggerIdentifierGuardTest {

    private static final String DB_TYPE = "TRIGGER_IDENTIFIER_GUARD_TEST";
    private final RecordingDbManager dbManager = new RecordingDbManager();
    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new TestPlugin(dbManager));
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        connectInfo.setConnection(connection());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void validUnicodeTriggerNameReachesDbManager() {
        new DbTriggerServiceImpl().drop("analytics-db", null, "订单更新");
        assertEquals(1, dbManager.triggerDrops.get());
    }

    @Test
    void unsafeTriggerIdentifiersAreRejectedBeforeDbManager() {
        for (String value : new String[] {null, "", "trg; DROP TABLE x", "trg--", "`quoted`", "db.trigger"}) {
            assertThrows(BusinessException.class,
                    () -> new DbTriggerServiceImpl().drop("analytics", "reporting", value));
        }
        for (String value : new String[] {"reporting; DROP", "schema--", "`quoted`", "db.schema"}) {
            assertThrows(BusinessException.class,
                    () -> new DbTriggerServiceImpl().drop("analytics", value, "trg_orders"));
        }
        assertEquals(0, dbManager.triggerDrops.get());
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> false;
                    case "close" -> null;
                    default -> null;
                });
    }

    private static final class RecordingDbManager extends DefaultDBManager {
        private final AtomicInteger triggerDrops = new AtomicInteger();

        @Override
        public void dropTrigger(Connection connection, String databaseName, String schemaName, String triggerName) {
            triggerDrops.incrementAndGet();
        }
    }

    private record TestPlugin(IDbManager dbManager) implements IPlugin {
        @Override
        public DBConfig getDBConfig() {
            DBConfig config = new DBConfig();
            config.setDbType(DB_TYPE);
            return config;
        }

        @Override
        public IDbManager getDbManager() {
            return dbManager;
        }
    }
}
