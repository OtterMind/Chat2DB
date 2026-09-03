package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IEventManager;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbEventServiceImplTest {

    private static final String TEST_DB_TYPE = "event-service-test";

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
    }

    @Test
    void delegatesEveryOperationToCurrentDatabasePlugin() {
        RecordingEventManager manager = new RecordingEventManager();
        bindContext(manager);
        DbEventServiceImpl service = new DbEventServiceImpl();

        assertEquals(List.of(Map.of("eventName", "daily_rollup")), service.list("analytics"));
        assertEquals("daily_rollup", service.detail("analytics", null, "daily_rollup").getEventName());
        assertEquals(Map.of("schedulerEnabled", true, "eventCount", 1L), service.schedulerStatus("analytics"));
        assertEquals("DROP", service.dropEventSql("analytics", "daily_rollup"));
        assertEquals("ALTER", service.setEventEnabledSql("analytics", "daily_rollup", false));
        assertEquals(5, manager.calls.get());
    }

    @Test
    void rejectsPluginWithoutEventCapability() {
        bindContext(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> new DbEventServiceImpl().list("analytics"));

        assertEquals("event.management.unsupported", exception.getCode());
    }

    @Test
    void validatesNamesBeforeDelegating() {
        bindContext(new RecordingEventManager());
        DbEventServiceImpl service = new DbEventServiceImpl();

        assertEquals("database.name.required",
                assertThrows(BusinessException.class, () -> service.list(" ")).getCode());
        assertEquals("event.name.required",
                assertThrows(BusinessException.class, () -> service.detail("analytics", null, " ")).getCode());
        assertEquals("event.name.required",
                assertThrows(BusinessException.class, () -> service.dropEventSql("analytics", " ")).getCode());
    }

    private static void bindContext(IEventManager manager) {
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                DBConfig config = new DBConfig();
                config.setDbType(TEST_DB_TYPE);
                return config;
            }

            @Override
            public IEventManager getEventManager() {
                return manager;
            }
        });
        Connection connection = (Connection) Proxy.newProxyInstance(
                DbEventServiceImplTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private static final class RecordingEventManager implements IEventManager {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<Map<String, Object>> list(Connection connection, String databaseName) {
            assertNotNull(connection);
            calls.incrementAndGet();
            return List.of(Map.of("eventName", "daily_rollup"));
        }

        @Override
        public Event detail(Connection connection, String databaseName, String schemaName, String eventName) {
            calls.incrementAndGet();
            return Event.builder().eventName(eventName).build();
        }

        @Override
        public Map<String, Object> schedulerStatus(Connection connection, String databaseName) {
            calls.incrementAndGet();
            return Map.of("schedulerEnabled", true, "eventCount", 1L);
        }

        @Override
        public String buildDropEvent(String databaseName, String eventName) {
            calls.incrementAndGet();
            return "DROP";
        }

        @Override
        public String buildAlterEventEnabled(String databaseName, String eventName, boolean enabled) {
            calls.incrementAndGet();
            return "ALTER";
        }
    }
}
