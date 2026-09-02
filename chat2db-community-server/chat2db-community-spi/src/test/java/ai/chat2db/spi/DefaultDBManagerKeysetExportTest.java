package ai.chat2db.spi;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole-database SQL export must page by primary key instead of OFFSET and bundle rows into
 * multi-value statements.
 */
class DefaultDBManagerKeysetExportTest {

    private static final String TEST_DB_TYPE = "DEFAULT_DB_MANAGER_KEYSET_TEST";

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
                return new DefaultMetaService();
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
    void keysetPagesThroughThePrimaryKeyAndEmitsMultiValueStatements() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:keyset_export")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ORDERS (ID INT PRIMARY KEY, NOTE VARCHAR(20))");
                statement.execute("INSERT INTO ORDERS SELECT X, 'row' FROM SYSTEM_RANGE(1, 2001)");
            }
            RecordingContext recording = new RecordingContext();

            new DefaultDBManager().exportTableData(connection, null, null, "ORDERS", recording.proxy());
            
            String sql = String.join("", recording.written);
            List<Integer> exportedIds = new ArrayList<>();
            Matcher matcher = Pattern.compile("\\('(\\d+)','row'\\)").matcher(sql);
            while (matcher.find()) {
                exportedIds.add(Integer.parseInt(matcher.group(1)));
            }
            assertEquals(IntStream.rangeClosed(1, 2001).boxed().toList(), exportedIds);
            long statements = Pattern.compile("INSERT INTO ORDERS \\(ID,NOTE\\)").matcher(sql).results().count();
            assertEquals(3, statements, "2001 rows in 800-row statements");
            assertTrue(sql.contains("('1','row')"), sql);
        }
    }

    @Test
    void tablesWithoutPrimaryKeyKeepTheOffsetPagingPath() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:keyset_fallback")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE HEAP (ID INT)");
                statement.execute("INSERT INTO HEAP SELECT X FROM SYSTEM_RANGE(1, 5)");
            }
            RecordingContext recording = new RecordingContext();

            new DefaultDBManager().exportTableData(connection, null, null, "HEAP", recording.proxy());

            String sql = String.join("", recording.written);
            assertEquals(5, Pattern.compile("\\('(\\d+)'\\)").matcher(sql).results().count());
        }
    }

    private static final class RecordingContext {

        private final List<String> written = new ArrayList<>();

        private TaskExecutionContext proxy() {
            return (TaskExecutionContext) Proxy.newProxyInstance(
                    DefaultDBManagerKeysetExportTest.class.getClassLoader(),
                    new Class<?>[] {TaskExecutionContext.class},
                    (proxy, method, args) -> {
                        if ("write".equals(method.getName())) {
                            written.add(String.valueOf(args[0]));
                        }
                        Class<?> type = method.getReturnType();
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
                    });
        }
    }
}
