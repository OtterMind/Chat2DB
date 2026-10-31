package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDBManagerExportStreamingTest {

    private static final String TEST_DB_TYPE = "DEFAULT_DB_MANAGER_EXPORT_STREAMING_TEST";

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
    void tableDataExportIssuesOneStreamingQueryWithoutOffsetPaging() throws Exception {
        try (Connection connection = tableDataConnection()) {
            List<String> preparedStatements = new ArrayList<>();
            RecordingContext context = new RecordingContext();

            new DefaultDBManager().exportTableData(recordingConnection(connection, preparedStatements),
                    null, null, "TEST_LOGS", context.proxy());

            assertEquals(1, preparedStatements.size(), preparedStatements.toString());
            String query = preparedStatements.get(0).toUpperCase(Locale.ROOT);
            assertTrue(query.startsWith("SELECT"), preparedStatements.get(0));
            assertFalse(query.contains("OFFSET"), preparedStatements.get(0));
            assertFalse(query.contains("LIMIT"), preparedStatements.get(0));

            List<String> inserts = context.writes().stream()
                    .filter(write -> write.toUpperCase(Locale.ROOT).startsWith("INSERT"))
                    .toList();
            assertEquals(3, inserts.size(), context.writes().toString());
            assertTrue(context.writes().get(0).contains("Records of TEST_LOGS"), context.writes().get(0));

            assertEquals(List.of(TaskEventCode.QUERY_STARTED.name(), TaskEventCode.QUERY_COMPLETED.name()),
                    context.events().stream().map(LogEvent::code).toList());
            assertEquals(3L, context.events().get(1).details().get(TaskConstants.EXPORTED_ROWS_DETAIL_KEY));
        }
    }

    private static Connection tableDataConnection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:default_db_manager_export_streaming");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST_LOGS (ID INT PRIMARY KEY)");
            statement.execute("INSERT INTO TEST_LOGS SELECT X FROM SYSTEM_RANGE(1, 3)");
        }
        return connection;
    }

    private static Connection recordingConnection(Connection connection, List<String> preparedStatements) {
        return (Connection) Proxy.newProxyInstance(DefaultDBManagerExportStreamingTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        preparedStatements.add((String) args[0]);
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
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

    private record LogEvent(String code, String message, Map<String, Object> details) {
    }

    private static final class RecordingContext {

        private final List<String> writes = new ArrayList<>();

        private final List<LogEvent> events = new ArrayList<>();

        private TaskExecutionContext proxy() {
            return (TaskExecutionContext) Proxy.newProxyInstance(
                    DefaultDBManagerExportStreamingTest.class.getClassLoader(),
                    new Class<?>[] {TaskExecutionContext.class},
                    (proxy, method, args) -> {
                        if ("write".equals(method.getName())) {
                            writes.add((String) args[0]);
                        }
                        if ("logInfo".equals(method.getName())) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> details = args.length == 3
                                    ? (Map<String, Object>) args[2] : Map.of();
                            events.add(new LogEvent((String) args[0], (String) args[1], details));
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private List<String> writes() {
            return writes;
        }

        private List<LogEvent> events() {
            return events;
        }
    }
}
