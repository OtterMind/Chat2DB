package ai.chat2db.spi;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDBManagerDatabaseExportToleranceTest {

    private static final String TEST_DB_TYPE = "DEFAULT_DB_MANAGER_DATABASE_EXPORT_TEST";

    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        previousPlugin = Chat2DBContext.PLUGIN_MAP.get(TEST_DB_TYPE);
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(TEST_DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, previousPlugin);
        }
    }

    @Test
    void databaseExportSkipsFailingTableAndContinues() throws Exception {
        installPlugin((connection, request) -> {
            if ("bad_table".equals(request.getTableName())) {
                throw new IllegalStateException("no SHOW VIEW privilege");
            }
            return "CREATE TABLE " + request.getTableName() + " (id INT);";
        });
        RecordingContext context = new RecordingContext();

        new DefaultDBManager().exportDatabase(null, "app", null, false, context.proxy());

        assertEquals(1, context.warnEvents.size());
        assertEquals(TaskEventCode.OBJECT_SKIPPED.name(), context.warnEvents.get(0).code());
        assertTrue(context.warnEvents.get(0).message().contains("bad_table"));
        assertEquals("bad_table", context.warnEvents.get(0).details().get(TaskConstants.TABLE_NAME_DETAIL_KEY));
        assertTrue(context.writes.stream().anyMatch(write -> write.contains("CREATE TABLE good_table")));
        assertFalse(context.writes.stream().anyMatch(write -> write.contains("bad_table")));
    }

    @Test
    void databaseExportStillPropagatesCancellation() {
        TaskCancelledException cancellation = new TaskCancelledException();
        installPlugin((connection, request) -> {
            throw cancellation;
        });
        RecordingContext context = new RecordingContext();

        assertSame(cancellation, assertThrows(TaskCancelledException.class,
                () -> new DefaultDBManager().exportDatabase(null, "app", null, false, context.proxy())));
    }

    private interface TableDdlBehavior {
        String tableDDL(Connection connection, TableMetadataRequest request);
    }

    private void installPlugin(TableDdlBehavior behavior) {
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService() {
                    @Override
                    public List<Table> tables(Connection connection, TablesRequest tablesRequest) {
                        Table bad = new Table();
                        bad.setName("bad_table");
                        Table good = new Table();
                        good.setName("good_table");
                        return List.of(bad, good);
                    }

                    @Override
                    public String tableDDL(Connection connection, TableMetadataRequest request) {
                        return behavior.tableDDL(connection, request);
                    }
                };
            }
        });
    }

    private record WarnEvent(String code, String message, Map<String, Object> details) {
    }

    private static final class RecordingContext {
        private final List<WarnEvent> warnEvents = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();

        private TaskExecutionContext proxy() {
            return (TaskExecutionContext) Proxy.newProxyInstance(
                    DefaultDBManagerDatabaseExportToleranceTest.class.getClassLoader(),
                    new Class<?>[] {TaskExecutionContext.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "logWarn" -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> details = args.length == 3
                                        ? (Map<String, Object>) args[2] : Map.of();
                                warnEvents.add(new WarnEvent((String) args[0], (String) args[1], details));
                            }
                            case "write" -> writes.add((String) args[0]);
                            default -> {
                            }
                        }
                        return defaultValue(method.getReturnType());
                    });
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
    }
}
