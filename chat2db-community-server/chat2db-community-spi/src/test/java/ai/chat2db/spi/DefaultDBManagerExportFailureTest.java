package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultDBManagerExportFailureTest {

    private static final String TEST_DB_TYPE = "DEFAULT_DB_MANAGER_EXPORT_TEST";

    private final IllegalStateException failure = new IllegalStateException("Could not read table DDL");

    private IPlugin previousPlugin;

    @BeforeEach
    void setUp() {
        DBConfig config = new DBConfig();
        config.setDbType(TEST_DB_TYPE);
        config.setDefaultDriverConfig(new DriverConfig());
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return new DefaultMetaService() {
                    @Override
                    public String tableDDL(Connection connection, TableMetadataRequest request) {
                        throw failure;
                    }
                };
            }
        });
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
    void singleTableDdlFailurePropagatesToTaskCaller() {
        DefaultDBManager manager = new DefaultDBManager();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> manager.exportTable(null, "app", "public", "orders", false, context()));

        assertSame(failure, thrown);
    }

    @Test
    void pagedTableDataExportReportsEveryThousandRowsAndExactCompletion() throws Exception {
        try (Connection connection = tableDataConnection("paged")) {
            RecordingContext recordingContext = new RecordingContext();

            new DefaultDBManager().exportTableData(connection, null, null, "TEST_LOGS", recordingContext.proxy());

            assertExportEvents(recordingContext.events());
        }
    }

    @Test
    void streamingTableDataExportReportsEveryThousandRowsAndExactCompletion() throws Exception {
        try (Connection connection = tableDataConnection("streaming")) {
            RecordingContext recordingContext = new RecordingContext();

            new StreamingTestDBManager().exportTableDataWithBatchSize(
                    connection, "TEST_LOGS", recordingContext.proxy(), 1_000);

            assertExportEvents(recordingContext.events());
        }
    }

    private static Connection tableDataConnection(String name) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:default_db_manager_" + name);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST_LOGS (ID INT PRIMARY KEY)");
            statement.execute("INSERT INTO TEST_LOGS SELECT X FROM SYSTEM_RANGE(1, 2001)");
        }
        return connection;
    }

    private static void assertExportEvents(List<LogEvent> events) {
        assertEquals(List.of(
                TaskEventCode.QUERY_STARTED.name(),
                TaskEventCode.ROWS_EXPORTED.name(),
                TaskEventCode.ROWS_EXPORTED.name(),
                TaskEventCode.QUERY_COMPLETED.name()),
                events.stream().map(LogEvent::code).toList());
        assertEquals(1_000L, events.get(1).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        assertEquals(2_000L, events.get(2).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        assertEquals(2_001L, events.get(3).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        assertEquals("TEST_LOGS", events.get(3).details().get(TaskConstants.TABLE_NAME_DETAIL_KEY));
        assertFalse(events.stream().map(LogEvent::message)
                .anyMatch(message -> message.matches(".*\\d{4}-\\d{2}-\\d{2}.*")));
    }

    private static TaskExecutionContext context() {
        return (TaskExecutionContext) Proxy.newProxyInstance(DefaultDBManagerExportFailureTest.class.getClassLoader(),
                new Class<?>[] {TaskExecutionContext.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
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

    private record LogEvent(String code, String message, Map<String, Object> details) {
    }

    private static final class RecordingContext {
        private final List<LogEvent> events = new ArrayList<>();

        private TaskExecutionContext proxy() {
            return (TaskExecutionContext) Proxy.newProxyInstance(
                    DefaultDBManagerExportFailureTest.class.getClassLoader(),
                    new Class<?>[] {TaskExecutionContext.class},
                    (proxy, method, args) -> {
                        if ("logInfo".equals(method.getName())) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> details = args.length == 3
                                    ? (Map<String, Object>) args[2] : Map.of();
                            events.add(new LogEvent((String) args[0], (String) args[1], details));
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private List<LogEvent> events() {
            return events;
        }
    }

    private static final class StreamingTestDBManager extends DefaultDBManager {
        private void exportTableDataWithBatchSize(Connection connection, String tableName,
                TaskExecutionContext context, int batchSize) {
            exportTableData(connection, null, null, tableName, context, batchSize);
        }
    }
}
