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

class DbRoutineIdentifierGuardTest {

    private static final String DB_TYPE = "ROUTINE_IDENTIFIER_GUARD_TEST";
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
    void validUnicodeRoutineNamesReachDbManager() {
        new DbFunctionServiceImpl().drop("analytics-db", null, "计算税额");
        new DbProcedureServiceImpl().drop("analytics-db", "reporting", "rebuild_rollups");

        assertEquals(1, dbManager.functionDrops.get());
        assertEquals(1, dbManager.procedureDrops.get());
    }

    @Test
    void unsafeRoutineIdentifiersAreRejectedBeforeDbManager() {
        for (String value : new String[] {null, "", "orders; DROP TABLE x", "name--", "`quoted`", "db.name"}) {
            assertThrows(BusinessException.class,
                    () -> new DbFunctionServiceImpl().drop("analytics", "reporting", value));
            assertThrows(BusinessException.class,
                    () -> new DbProcedureServiceImpl().drop("analytics", "reporting", value));
        }
        for (String value : new String[] {"reporting; DROP", "schema--", "`quoted`", "db.schema"}) {
            assertThrows(BusinessException.class,
                    () -> new DbProcedureServiceImpl().drop("analytics", value, "refresh_data"));
        }

        assertEquals(0, dbManager.functionDrops.get());
        assertEquals(0, dbManager.procedureDrops.get());
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
        private final AtomicInteger functionDrops = new AtomicInteger();
        private final AtomicInteger procedureDrops = new AtomicInteger();

        @Override
        public void dropFunction(Connection connection, String databaseName, String schemaName, String functionName) {
            functionDrops.incrementAndGet();
        }

        @Override
        public void dropProcedure(Connection connection, String databaseName, String schemaName, String procedureName) {
            procedureDrops.incrementAndGet();
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
